package com.hanwha.setdata.extract.joinage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of {@code parse_age_table} — 보험기간 × 성별 매트릭스. */
public final class JaAgeTable {

    private JaAgeTable() {}

    private static final int UF = Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern PAYM_PATTERN = Pattern.compile(
            "^(전기납|일시납|종신납|\\d+년납|\\d+세납)", UF);

    public static List<Map<String, Object>> parse(List<List<List<String>>> tables, List<String> lines) {
        List<Map<String, Object>> results = new ArrayList<>();
        int tableGroupIdx = 0;

        for (List<List<String>> table : tables) {
            if (table == null || table.size() < 2) continue;

            List<String> header0 = normalizedRow(table.get(0));
            List<String> header1 = table.size() > 1 ? normalizedRow(table.get(1)) : new ArrayList<>();

            boolean hasManki = anyManki(header0);
            boolean splitHeaderMerged = false;
            int headerSkipRows = 0;
            if (!hasManki && !header1.isEmpty()) {
                boolean hasMankiInH1 = false;
                for (String h : header1) if ("만기".equals(h)) { hasMankiInH1 = true; break; }
                if (hasMankiInH1) {
                    hasManki = true;
                    splitHeaderMerged = true;
                    headerSkipRows = 1;
                    List<String> merged = new ArrayList<>();
                    for (int ci = 0; ci < header0.size(); ci++) {
                        String h0 = header0.get(ci);
                        String h1 = ci < header1.size() ? header1.get(ci) : "";
                        if ("만기".equals(h1) && !h0.isEmpty() && !h0.equals("만기")) {
                            merged.add(h0 + "만기");
                        } else {
                            merged.add(h0);
                        }
                    }
                    header0 = merged;
                    header1 = table.size() > 2 ? normalizedRow(table.get(2)) : new ArrayList<>();
                }
            }
            if (!hasManki && !header1.isEmpty()) {
                boolean any = false;
                for (String h : header1) if (h.contains("세만기")) { any = true; break; }
                if (any) {
                    hasManki = true;
                    headerSkipRows = 1;
                    header0 = header1;
                    header1 = table.size() > 2 ? normalizedRow(table.get(2)) : new ArrayList<>();
                }
            }
            if (!hasManki && table.size() > 2) {
                int limit = Math.min(4, table.size());
                for (int skip = 2; skip < limit; skip++) {
                    List<String> candidate = normalizedRow(table.get(skip));
                    boolean any = false;
                    for (String h : candidate) if (h.contains("세만기") || h.contains("만기")) { any = true; break; }
                    if (any) {
                        hasManki = true;
                        headerSkipRows = skip;
                        header0 = candidate;
                        header1 = table.size() > skip + 1 ? normalizedRow(table.get(skip + 1)) : new ArrayList<>();
                        break;
                    }
                }
            }

            boolean hasGenderInHeader = false;
            for (String h : header1) {
                if (h.contains("남자") || h.contains("여자") || h.contains("남 자") || h.contains("여 자")) {
                    hasGenderInHeader = true; break;
                }
            }
            boolean hasPaymInHeader1 = false;
            for (String h : header1) {
                if (!h.isEmpty() && PAYM_PATTERN.matcher(h).lookingAt()) { hasPaymInHeader1 = true; break; }
            }
            boolean rowLevelGender = hasGenderInHeader && hasPaymInHeader1;
            boolean hasGender = hasGenderInHeader && !rowLevelGender;

            if (!hasManki) continue;

            int curTableGroup = tableGroupIdx++;

            Map<Integer, String> colInsurance = new LinkedHashMap<>();
            Map<Integer, String> colGender = new LinkedHashMap<>();

            String lastIns = "";
            for (int ci = 0; ci < header0.size(); ci++) {
                String cell = header0.get(ci);
                if (cell.contains("세만기") || cell.contains("만기") || cell.equals("종신")) lastIns = cell;
                if (!lastIns.isEmpty() && ci > 0) colInsurance.put(ci, lastIns);
            }

            Map<Integer, String> genderMarkers = new LinkedHashMap<>();
            if (hasGender) {
                for (int ci = 0; ci < header1.size(); ci++) {
                    String cell = header1.get(ci);
                    if (cell.contains("남자") || cell.contains("남 자")) genderMarkers.put(ci, "1");
                    else if (cell.contains("여자") || cell.contains("여 자")) genderMarkers.put(ci, "2");
                }
                String lastGender = "";
                List<Integer> sortedIns = new ArrayList<>(colInsurance.keySet());
                Collections.sort(sortedIns);
                for (int ci : sortedIns) {
                    if (genderMarkers.containsKey(ci)) lastGender = genderMarkers.get(ci);
                    colGender.put(ci, lastGender);
                }
            }

            if (colInsurance.isEmpty()) continue;
            if (colGender.isEmpty()) for (int ci : colInsurance.keySet()) colGender.put(ci, "");

            List<String> insPeriodsOrdered = new ArrayList<>();
            String lastInsVal = "";
            List<Integer> sortedIns2 = new ArrayList<>(colInsurance.keySet());
            Collections.sort(sortedIns2);
            for (int ci : sortedIns2) {
                if (!colInsurance.get(ci).equals(lastInsVal)) {
                    lastInsVal = colInsurance.get(ci);
                    insPeriodsOrdered.add(lastInsVal);
                }
            }

            List<String[]> ageSlotSeq = new ArrayList<>();
            if (hasGender) {
                List<String> gendersSeen = new ArrayList<>();
                List<Integer> gk = new ArrayList<>(genderMarkers.keySet());
                Collections.sort(gk);
                for (int ci : gk) gendersSeen.add(genderMarkers.get(ci));
                int gpp = Math.max(1, gendersSeen.size() / Math.max(insPeriodsOrdered.size(), 1));
                if (gpp == 0) gpp = 1;
                for (String insP : insPeriodsOrdered) {
                    for (int gi = 0; gi < Math.min(gpp, gendersSeen.size()); gi++) {
                        ageSlotSeq.add(new String[]{insP, gendersSeen.get(gi)});
                    }
                }
            } else {
                for (String insP : insPeriodsOrdered) ageSlotSeq.add(new String[]{insP, ""});
            }

            int dataStart = hasGender ? 2 : 1;
            if (headerSkipRows > 0) dataStart = headerSkipRows + (hasGender ? 2 : 1);

            String prevContext = "";
            String prevPaym = "";
            String rowGender = "";
            int firstDataCol = (Collections.min(colInsurance.keySet())) - 1;

            for (int ri = dataStart; ri < table.size(); ri++) {
                List<String> row = table.get(ri);
                List<String> cells = normalizedRow(row);

                String paymText;
                int dataStartCol;
                String context;

                if (rowLevelGender) {
                    String cell0 = cells.isEmpty() ? "" : cells.get(0);
                    if (cell0.contains("남자") || cell0.contains("남 자")) rowGender = "1";
                    else if (cell0.contains("여자") || cell0.contains("여 자")) rowGender = "2";
                    paymText = prevPaym;
                    for (String c : cells) {
                        if (PAYM_PATTERN.matcher(c).lookingAt()) {
                            paymText = c;
                            prevPaym = c;
                            break;
                        }
                    }
                    context = "";
                    dataStartCol = 1;
                } else {
                    String cell0 = cells.isEmpty() ? "" : cells.get(0);
                    String cell1 = cells.size() > 1 ? cells.get(1) : "";
                    boolean isPaymPattern = PAYM_PATTERN.matcher(cell0).lookingAt();
                    if (isPaymPattern) {
                        context = prevContext;
                        paymText = cell0;
                        if (!cell0.isEmpty()) prevPaym = cell0;
                        dataStartCol = 1;
                    } else {
                        context = !cell0.isEmpty() ? cell0 : prevContext;
                        if (!cell0.isEmpty()) prevContext = cell0;
                        paymText = !cell1.isEmpty() ? cell1 : prevPaym;
                        if (!cell1.isEmpty()) prevPaym = cell1;
                        dataStartCol = 2;
                    }
                }

                List<String[]> ageValues = new ArrayList<>();
                for (int ci = dataStartCol; ci < cells.size(); ci++) {
                    String rawVal = ci < row.size() ? row.get(ci) : null;
                    if (rawVal == null || rawVal.trim().isEmpty()) continue;
                    String cellText = cells.get(ci);
                    String[] parsed = JaText.parseAgeCell(cellText);
                    if (parsed != null) ageValues.add(parsed);
                    else if ("-".equals(cellText)) ageValues.add(null);
                }

                int slotIdx = 0;
                for (String[] parsed : ageValues) {
                    if (slotIdx >= ageSlotSeq.size()) break;
                    String insPeriod = ageSlotSeq.get(slotIdx)[0];
                    String gender = ageSlotSeq.get(slotIdx)[1];
                    if (rowLevelGender) gender = rowGender;
                    slotIdx++;
                    if (parsed == null) continue;
                    String minA = parsed[0];
                    String maxA = parsed[1];
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("context", context);
                    rec.put("보험기간", insPeriod);
                    rec.put("납입기간", paymText);
                    rec.put("성별", gender);
                    rec.put("최소가입나이", minA == null ? "" : minA);
                    rec.put("최대가입나이", maxA);
                    rec.put("table_group", curTableGroup);
                    results.add(rec);
                }
            }
        }

        if (!results.isEmpty()) {
            String prevCtx = "";
            for (Map<String, Object> entry : results) {
                Object c = entry.get("context");
                String ctx = c == null ? "" : c.toString();
                if (!ctx.isEmpty()) prevCtx = ctx;
                else if (!prevCtx.isEmpty()) entry.put("context", prevCtx);
            }
        }

        return results;
    }

    private static List<String> normalizedRow(List<String> row) {
        List<String> out = new ArrayList<>();
        for (String c : row) out.add(JaText.normalizeWs(c == null ? "" : c));
        return out;
    }

    private static boolean anyManki(List<String> row) {
        for (String h : row) {
            if (h.contains("세만기") || h.contains("만기") || "종신".equals(h)) return true;
        }
        return false;
    }
}
