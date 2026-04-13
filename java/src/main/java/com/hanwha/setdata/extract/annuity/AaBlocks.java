package com.hanwha.setdata.extract.annuity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts annuity age "blocks" from docx tables and plain text lines.
 * Port of Python {@code extract_annuity_blocks_from_tables} and
 * {@code extract_annuity_blocks}.
 */
public final class AaBlocks {

    /** Single annuity block. */
    public static final class Block {
        public final Map<String, List<String>> genericValues;
        public final Map<String, Map<String, List<String>>> categoryValues;
        public final String source; // "" or "table"
        public Block(Map<String, List<String>> generic,
                     Map<String, Map<String, List<String>>> category,
                     String source) {
            this.genericValues = generic;
            this.categoryValues = category;
            this.source = source;
        }
    }

    private AaBlocks() {}

    private static boolean isAnnuityTable(List<List<String>> table) {
        StringBuilder flat = new StringBuilder();
        int limit = Math.min(4, table.size());
        for (int i = 0; i < limit; i++) {
            for (String cell : table.get(i)) {
                if (flat.length() > 0) flat.append(' ');
                flat.append(cell);
            }
        }
        String joined = flat.toString();
        return joined.contains("연금개시나이")
                || (joined.contains("종신연금형") && joined.contains("확정기간연금형"));
    }

    // ── from tables ────────────────────────────────────────────────
    public static List<Block> fromTables(List<List<List<String>>> tables) {
        List<Block> blocks = new ArrayList<>();

        for (List<List<String>> table : tables) {
            if (!isAnnuityTable(table)) continue;

            int headerRowIdx = -1;
            List<String> colCategories = new ArrayList<>();
            int fallbackHeaderIdx = -1;
            List<String> fallbackColCategories = new ArrayList<>();

            for (int ridx = 0; ridx < table.size(); ridx++) {
                List<String> row = table.get(ridx);
                boolean hasSpecific = false;
                for (String cell : row) {
                    if (cell.contains("종신연금형") || cell.contains("확정기간연금형")) {
                        hasSpecific = true;
                        break;
                    }
                }
                boolean hasGeneric = false;
                for (String cell : row) {
                    if (cell.contains("연금개시나이") || cell.contains("연금형")) {
                        hasGeneric = true;
                        break;
                    }
                }
                if (hasSpecific) {
                    headerRowIdx = ridx;
                    colCategories = new ArrayList<>(row);
                    break;
                }
                if (hasGeneric && fallbackHeaderIdx < 0) {
                    fallbackHeaderIdx = ridx;
                    fallbackColCategories = new ArrayList<>(row);
                }
            }
            if (headerRowIdx < 0 && fallbackHeaderIdx >= 0) {
                headerRowIdx = fallbackHeaderIdx;
                colCategories = fallbackColCategories;
            }
            if (headerRowIdx < 0) continue;

            // Gender row
            int genderRowIdx = -1;
            List<String> colGenders = new ArrayList<>();
            int genderSearchEnd = Math.min(headerRowIdx + 3, table.size());
            for (int ridx = headerRowIdx + 1; ridx < genderSearchEnd; ridx++) {
                List<String> row = table.get(ridx);
                boolean hasGender = false;
                for (String cell : row) {
                    if (cell.contains("남자") || cell.contains("여자")) { hasGender = true; break; }
                }
                if (hasGender) {
                    genderRowIdx = ridx;
                    colGenders = new ArrayList<>(row);
                    break;
                }
            }

            int dataStart = (genderRowIdx >= 0) ? genderRowIdx + 1 : headerRowIdx + 1;

            Map<String, Map<String, List<String>>> categoryToValues = new LinkedHashMap<>();
            Map<String, List<String>> genericValues = new LinkedHashMap<>();

            Map<Integer, List<String>> prevColVals = new LinkedHashMap<>();
            Map<Integer, String> prevColGender = new LinkedHashMap<>();

            String prevRowCtx = "";
            for (int ridx = dataStart; ridx < table.size(); ridx++) {
                List<String> row = table.get(ridx);

                String rowCtxRaw = row.isEmpty() ? "" : row.get(0);
                if (rowCtxRaw.isEmpty()) rowCtxRaw = prevRowCtx;
                else prevRowCtx = rowCtxRaw;

                Map<String, Set<String>> rowCtxTokens = AaText.extractContextTokens(rowCtxRaw);

                for (int cidx = 0; cidx < row.size(); cidx++) {
                    String cell = row.get(cidx);
                    String colCat = cidx < colCategories.size() ? colCategories.get(cidx) : "";
                    String colGenderStr = cidx < colGenders.size() ? colGenders.get(cidx) : "";

                    List<String> vals;
                    String gender;

                    if (cell.isEmpty()) {
                        Map<String, Set<String>> colCatTokens = AaText.extractContextTokens(colCat);
                        boolean hasGenderHeader = colGenderStr.contains("남자") || colGenderStr.contains("여자");
                        if ((!colCatTokens.isEmpty() || hasGenderHeader) && prevColVals.containsKey(cidx)) {
                            vals = prevColVals.get(cidx);
                            gender = prevColGender.getOrDefault(cidx, "");
                        } else {
                            continue;
                        }
                    } else {
                        vals = AaText.splitAgeValues(cell);
                        if (vals.isEmpty()) continue;
                        gender = "";
                        if (colGenderStr.contains("남자") || cell.contains("남자")) gender = "남자";
                        else if (colGenderStr.contains("여자") || cell.contains("여자")) gender = "여자";
                        prevColVals.put(cidx, vals);
                        prevColGender.put(cidx, gender);
                    }

                    Map<String, Set<String>> colCatTokens = AaText.extractContextTokens(colCat);

                    Map<String, Set<String>> merged = new LinkedHashMap<>();
                    for (Map.Entry<String, Set<String>> e : rowCtxTokens.entrySet()) {
                        merged.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
                    }
                    for (Map.Entry<String, Set<String>> e : colCatTokens.entrySet()) {
                        merged.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
                    }

                    List<String> contextKeys = AaText.buildContextKey(merged);

                    if (!contextKeys.isEmpty()) {
                        AaText.assignContextValues(categoryToValues, contextKeys, gender, vals);
                    } else {
                        AaText.addValues(genericValues, gender, vals);
                    }
                }
            }

            if (!categoryToValues.isEmpty() || !genericValues.isEmpty()) {
                blocks.add(new Block(genericValues, categoryToValues, "table"));
            }
        }
        return blocks;
    }

    // ── from text lines ────────────────────────────────────────────
    private static final Pattern INLINE_COLON = Pattern.compile("연금개시나이\\s*[:：]\\s*(.+)");
    private static final Pattern RANGE_ANY = Pattern.compile("\\d{2,3}\\s*세?\\s*[~\\-]\\s*\\d{2,3}");
    private static final Pattern RANGE_PAIRS = Pattern.compile("(\\d{2,3})\\s*세?\\s*[~\\-]\\s*(\\d{2,3})");

    public static List<Block> fromLines(List<String> lines) {
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String prev = i > 0 ? lines.get(i - 1) : "";
            String next = i + 1 < lines.size() ? lines.get(i + 1) : "";
            if (!AaText.isAnnuitySectionHeader(prev, line, next)) continue;

            List<String> blockLines = new ArrayList<>();
            Matcher inline = INLINE_COLON.matcher(line);
            if (inline.find()) blockLines.add(inline.group(1).trim());

            for (int j = i + 1; j < lines.size(); j++) {
                String nxt = lines.get(j);
                if (nxt.equals("연금개시나이")) continue;
                if (AaText.isNextSectionBoundary(nxt)) break;
                blockLines.add(nxt);
            }

            if (blockLines.isEmpty()) continue;

            boolean genderHeader = false;
            Map<String, Map<String, List<String>>> categoryToValues = new LinkedHashMap<>();
            Map<String, List<String>> genericValues = new LinkedHashMap<>();
            List<String> genericSeen = new ArrayList<>();
            Map<String, Set<String>> contextState = new LinkedHashMap<>();

            for (String bl : blockLines) {
                Map<String, Set<String>> ctxUpdate = AaText.extractContextTokens(bl);
                for (Map.Entry<String, Set<String>> e : ctxUpdate.entrySet()) {
                    contextState.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
                }

                if (bl.contains("남자") && bl.contains("여자")) {
                    genderHeader = true;
                    continue;
                }

                List<String> vals = AaText.splitAgeValues(bl);
                if (vals.isEmpty()) continue;

                boolean isRange = RANGE_ANY.matcher(bl).find()
                        || (bl.contains("이상") && bl.contains("이하"))
                        || vals.size() > 2;
                List<String> contextKeys = AaText.buildContextKey(contextState);
                boolean hasContext = !contextKeys.isEmpty();

                if (bl.contains("남자") && !bl.contains("여자")) {
                    List<String> assign = isRange ? vals : singleton(vals.get(0));
                    if (hasContext) AaText.assignContextValues(categoryToValues, contextKeys, "남자", assign);
                    else AaText.addValues(genericValues, "남자", assign);
                    continue;
                }
                if (bl.contains("여자") && !bl.contains("남자")) {
                    List<String> assign = isRange ? vals : singleton(vals.get(0));
                    if (hasContext) AaText.assignContextValues(categoryToValues, contextKeys, "여자", assign);
                    else AaText.addValues(genericValues, "여자", assign);
                    continue;
                }

                if (vals.size() >= 2 && !isRange) {
                    if (genderHeader) {
                        if (hasContext) {
                            AaText.assignContextValues(categoryToValues, contextKeys, "남자", singleton(vals.get(0)));
                            AaText.assignContextValues(categoryToValues, contextKeys, "여자", singleton(vals.get(1)));
                        } else {
                            AaText.appendUnique(
                                    genericValues.computeIfAbsent("남자", k -> new ArrayList<>()),
                                    singleton(vals.get(0)));
                            AaText.appendUnique(
                                    genericValues.computeIfAbsent("여자", k -> new ArrayList<>()),
                                    singleton(vals.get(1)));
                        }
                        continue;
                    }
                    if (hasContext) {
                        AaText.assignContextValues(categoryToValues, contextKeys, "남자", singleton(vals.get(0)));
                        AaText.assignContextValues(categoryToValues, contextKeys, "여자", singleton(vals.get(1)));
                    } else {
                        AaText.appendUnique(
                                genericValues.computeIfAbsent("남자", k -> new ArrayList<>()),
                                singleton(vals.get(0)));
                        AaText.appendUnique(
                                genericValues.computeIfAbsent("여자", k -> new ArrayList<>()),
                                singleton(vals.get(1)));
                    }
                    continue;
                }

                if (vals.size() >= 1 && isRange) {
                    if (genderHeader) {
                        List<int[]> ranges = new ArrayList<>();
                        Matcher rm = RANGE_PAIRS.matcher(bl);
                        while (rm.find()) {
                            ranges.add(new int[]{Integer.parseInt(rm.group(1)), Integer.parseInt(rm.group(2))});
                        }
                        if (ranges.size() >= 2) {
                            List<String> maleVals = AaText.splitAgeValues(ranges.get(0)[0] + "~" + ranges.get(0)[1]);
                            List<String> femaleVals = AaText.splitAgeValues(ranges.get(1)[0] + "~" + ranges.get(1)[1]);
                            if (hasContext) {
                                AaText.assignContextValues(categoryToValues, contextKeys, "남자", maleVals);
                                AaText.assignContextValues(categoryToValues, contextKeys, "여자", femaleVals);
                            } else {
                                AaText.appendUnique(
                                        genericValues.computeIfAbsent("남자", k -> new ArrayList<>()), maleVals);
                                AaText.appendUnique(
                                        genericValues.computeIfAbsent("여자", k -> new ArrayList<>()), femaleVals);
                            }
                            continue;
                        }
                    }
                    if (hasContext) AaText.assignContextValues(categoryToValues, contextKeys, "", vals);
                    else AaText.appendUnique(genericValues.computeIfAbsent("", k -> new ArrayList<>()), vals);
                    continue;
                }

                if (vals.size() == 1 && genderHeader) {
                    String first = vals.get(0);
                    if (genericSeen.size() < 2) {
                        String gender = genericSeen.isEmpty() ? "남자" : "여자";
                        if (hasContext) AaText.assignContextValues(categoryToValues, contextKeys, gender, singleton(first));
                        else AaText.appendUnique(
                                genericValues.computeIfAbsent(gender, k -> new ArrayList<>()),
                                singleton(first));
                        genericSeen.add(gender);
                    }
                    continue;
                }

                if (vals.size() == 1 && !genderHeader) {
                    if (hasContext) AaText.assignContextValues(categoryToValues, contextKeys, "", singleton(vals.get(0)));
                    else AaText.appendUnique(genericValues.computeIfAbsent("", k -> new ArrayList<>()), vals);
                }

                if (vals.size() > 1 && !isRange && genericValues.isEmpty()) {
                    genericSeen.add("남자");
                    genericSeen.add("여자");
                    AaText.appendUnique(genericValues.computeIfAbsent("남자", k -> new ArrayList<>()), singleton(vals.get(0)));
                    AaText.appendUnique(genericValues.computeIfAbsent("여자", k -> new ArrayList<>()), singleton(vals.get(1)));
                }
            }

            blocks.add(new Block(genericValues, categoryToValues, ""));
        }
        return blocks;
    }

    private static List<String> singleton(String v) {
        List<String> out = new ArrayList<>();
        out.add(v);
        return out;
    }

    /** Combine useful table blocks with text blocks, mirroring _load_annuity_blocks. */
    public static List<Block> load(List<String> lines, List<List<List<String>>> tables) {
        List<Block> tableBlocks = fromTables(tables);
        List<Block> textBlocks = fromLines(lines);
        List<Block> useful = new ArrayList<>();
        for (Block b : tableBlocks) {
            if (b.categoryValues.size() >= 2) useful.add(b);
        }
        useful.addAll(textBlocks);
        return useful;
    }
}
