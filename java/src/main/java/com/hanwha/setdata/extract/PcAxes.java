package com.hanwha.setdata.extract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Table-based and text-based axes/row-combo extraction ported from
 * {@code extract_product_classification_v2.py} (lines 656–1010).
 */
public final class PcAxes {

    private PcAxes() {}

    /** Python: extract_axes_from_detail_table. */
    public static List<List<String>> extractAxesFromDetailTable(List<List<String>> table) {
        if (table == null || table.isEmpty()) return new ArrayList<>();
        int maxCols = 0;
        for (List<String> row : table) maxCols = Math.max(maxCols, row.size());
        int headerIdx = -1;
        for (int i = 0; i < table.size(); i++) {
            StringBuilder sb = new StringBuilder();
            for (String c : table.get(i)) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(PcText.cleanItem(c == null ? "" : c));
            }
            if (sb.toString().contains("세부보험종목")) { headerIdx = i; break; }
        }
        if (headerIdx < 0) return new ArrayList<>();

        List<List<String>> axes = new ArrayList<>();
        for (int c = 0; c < maxCols; c++) {
            List<String> mergedLines = new ArrayList<>();
            for (int r = headerIdx + 1; r < table.size(); r++) {
                List<String> row = table.get(r);
                if (c >= row.size()) continue;
                String cell = PcText.cleanItem(row.get(c) == null ? "" : row.get(c));
                if (!cell.isEmpty()) mergedLines.add(cell);
            }
            List<String> vals = PcText.splitDetailCandidates(String.join("\n", mergedLines));
            if (!vals.isEmpty()) axes.add(vals);
        }
        return axes;
    }

    /** Python: extract_row_combos_from_detail_table. */
    public static List<List<String>> extractRowCombosFromDetailTable(
            List<List<String>> table, boolean excludeStandard) {
        if (table == null || table.isEmpty()) return new ArrayList<>();
        int maxCols = 0;
        for (List<String> row : table) maxCols = Math.max(maxCols, row.size());

        int headerIdx = -1;
        for (int i = 0; i < table.size(); i++) {
            StringBuilder sb = new StringBuilder();
            for (String c : table.get(i)) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(PcText.cleanItem(c == null ? "" : c));
            }
            if (sb.toString().contains("세부보험종목")) { headerIdx = i; break; }
        }
        if (headerIdx < 0) return new ArrayList<>();

        List<List<String>> combos = new ArrayList<>();
        List<List<String>> context = new ArrayList<>();
        for (int c = 0; c < maxCols; c++) context.add(new ArrayList<>());

        for (int r = headerIdx + 1; r < table.size(); r++) {
            List<String> row = table.get(r);
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < maxCols; c++) {
                if (c < row.size()) {
                    cells.add(PcText.cleanItem(row.get(c) == null ? "" : row.get(c)));
                } else {
                    cells.add("");
                }
            }
            List<Integer> nonEmptyCells = new ArrayList<>();
            for (int idx = 0; idx < cells.size(); idx++) {
                if (PcText.cellHasValue(cells.get(idx))) nonEmptyCells.add(idx);
            }
            if (nonEmptyCells.isEmpty()) continue;
            int firstNonEmpty = nonEmptyCells.get(0);
            int lastNonEmptyIdx = PcText.lastNonEmpty(cells);
            int nonEmptyCount = nonEmptyCells.size();

            StringBuilder rowJoinedB = new StringBuilder();
            for (String x : cells) {
                if (!x.isEmpty()) {
                    if (rowJoinedB.length() > 0) rowJoinedB.append(' ');
                    rowJoinedB.append(x);
                }
            }
            String rowJoined = rowJoinedB.toString();
            boolean suppressRootInherit = firstNonEmpty > 0
                    && (rowJoined.contains("상속연금형") || rowJoined.contains("확정기간연금형"));

            List<List<String>> levelOptions = new ArrayList<>();
            boolean continueRow = false;
            for (int c = 0; c < maxCols; c++) {
                String raw = cells.get(c);
                List<String> values = new ArrayList<>();
                if (PcText.cellHasValue(raw)) {
                    values = PcText.splitDetailCandidates(raw);
                    if (!excludeStandard && values.contains("표준형")) {
                        boolean hasRefundVariant = false;
                        for (String v : values) {
                            if (!v.equals("표준형")
                                    && (v.contains("해약환급금") || v.contains("일부지급형") || v.contains("미지급형"))) {
                                hasRefundVariant = true;
                                break;
                            }
                        }
                        if (hasRefundVariant) {
                            List<String> next = new ArrayList<>();
                            for (String v : values) if (!v.equals("표준형")) next.add(v);
                            values = next;
                        }
                    }
                } else if (nonEmptyCount > 1 && c > lastNonEmptyIdx) {
                    values = Arrays.asList("");
                } else if (nonEmptyCount == 1 && firstNonEmpty == 0 && c > firstNonEmpty) {
                    if (c < cells.size()
                            && Pattern.matches("\\d+종", cells.get(firstNonEmpty))
                            && !context.get(c).isEmpty()) {
                        values = new ArrayList<>(context.get(c));
                    } else {
                        values = Arrays.asList("");
                    }
                } else if (c < firstNonEmpty && !context.get(c).isEmpty()) {
                    if (suppressRootInherit && c == 0) {
                        continueRow = true;
                        break;
                    }
                    values = new ArrayList<>(context.get(c));
                } else {
                    continue;
                }

                if (excludeStandard) {
                    List<String> next = new ArrayList<>();
                    for (String v : values) if (!v.equals("표준형")) next.add(v);
                    values = next;
                }
                if (values.isEmpty()) continue;

                levelOptions.add(values);
                context.set(c, new ArrayList<>(values));
            }

            if (continueRow || levelOptions.isEmpty()) continue;

            // Cartesian product of levelOptions, then drop empty strings from each combo.
            List<List<String>> cartesian = cartesian(levelOptions);
            for (List<String> c : cartesian) {
                List<String> combo = new ArrayList<>();
                for (String v : c) if (!v.isEmpty()) combo.add(v);
                combos.add(combo);
            }
        }

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<List<String>> out = new ArrayList<>();
        for (List<String> combo : combos) {
            String key = String.join("\u0001", combo);
            if (seen.add(key)) out.add(combo);
        }
        return out;
    }

    private static List<List<String>> cartesian(List<List<String>> options) {
        List<List<String>> result = new ArrayList<>();
        result.add(new ArrayList<>());
        for (List<String> choices : options) {
            List<List<String>> next = new ArrayList<>();
            for (List<String> prefix : result) {
                for (String v : choices) {
                    List<String> copy = new ArrayList<>(prefix);
                    copy.add(v);
                    next.add(copy);
                }
            }
            result = next;
        }
        return result;
    }

    // ── extract_row_combos_from_text_section ───────────────────────
    private static final Pattern PAREN_NUM_LEAD = Pattern.compile("^\\(\\d+\\)\\s*");
    private static final Pattern JONG_PAREN_FULL = Pattern.compile("^\\d+종\\([^)]*\\)$");
    private static final Pattern SANGHAE_JILBYEONG = Pattern.compile("^(상해|질병)(입원형|통원형|형)$");
    private static final Pattern JONG_PAREN_GLOBAL = Pattern.compile("\\d+종\\([^)]*\\)");

    public static List<List<String>> extractRowCombosFromTextSection(
            String sectionText, boolean excludeStandard, List<String> productNames) {
        if (sectionText == null || sectionText.isEmpty()) return new ArrayList<>();
        List<String> tokens = new ArrayList<>();

        for (String raw : sectionText.split("\\n", -1)) {
            String line = PcText.cleanItem(raw);
            if (line.isEmpty()) continue;
            if (line.startsWith("※") || PcText.looksLikeNoise(line)) continue;
            line = PAREN_NUM_LEAD.matcher(line).replaceAll("");
            for (String item : PcText.splitDetailCandidates(line)) {
                if (item.isEmpty() || item.equals("-")) continue;
                if (PcText.looksLikeNoise(item)) continue;
                if (excludeStandard && item.equals("표준형")) continue;
                tokens.add(item);
            }
        }
        if (tokens.isEmpty()) return new ArrayList<>();

        List<List<String>> combos = new ArrayList<>();
        String currentTop = "";
        String currentMid = "";
        List<String> pendingTypes = new ArrayList<>();
        boolean hasGuaranteeFeeMarker = sectionText.contains("보증비용부과형");
        boolean hasEJung = productNames != null && productNames.stream().anyMatch(n -> n.contains("한화생명 e정기보험"));

        String pendingRefund = null;
        boolean pendingRefundUsed = false;
        String pendingPairAxis = null;
        String pendingERefundCoverage = null;

        for (String tok : tokens) {
            if (PcText.isRefundToken(tok)) {
                if (pendingERefundCoverage != null
                        && PcText.canPairWithPendingRefund(pendingERefundCoverage, productNames)) {
                    combos.add(Arrays.asList(pendingERefundCoverage, tok));
                    pendingRefundUsed = true;
                    pendingERefundCoverage = null;
                }
                if (pendingRefund != null && !pendingRefundUsed) {
                    combos.add(Arrays.asList(pendingRefund));
                }
                pendingRefund = tok;
                pendingRefundUsed = false;
                if (hasEJung) pendingPairAxis = null;
                continue;
            }

            if (PcText.canPairWithPendingRefund(tok, productNames)) {
                if (pendingRefund != null) {
                    combos.add(Arrays.asList(tok, pendingRefund));
                    pendingRefundUsed = true;
                    pendingERefundCoverage = null;
                    continue;
                }
            }

            if (hasEJung && tok.contains("보장형") && pendingRefund != null && !pendingRefundUsed) {
                combos.add(Arrays.asList(tok, pendingRefund));
                pendingRefundUsed = true;
                pendingERefundCoverage = null;
                continue;
            }

            if (hasEJung && tok.contains("보장형") && pendingRefund == null) {
                pendingERefundCoverage = tok;
            }

            if (tok.startsWith("적립형 계약")) {
                currentTop = "적립형 계약";
                currentMid = "";
                combos.add(Arrays.asList(currentTop));
                continue;
            }
            if (tok.startsWith("보장형 계약")) {
                currentTop = "보장형 계약";
                currentMid = "";
                for (String pt : pendingTypes) combos.add(Arrays.asList(currentTop, "", pt));
                pendingTypes = new ArrayList<>();
                continue;
            }
            if (tok.contains("스마트전환형 계약") || tok.startsWith("[보증비용부과형]")) {
                String line = tok;
                if (tok.startsWith("[보증비용부과형]")) line = "스마트전환형 계약" + tok;
                String primary = (line.contains("보증비용부과형") || hasGuaranteeFeeMarker)
                        ? "스마트전환형 계약[보증비용부과형]"
                        : "스마트전환형 계약";
                currentTop = primary;
                List<String> secs = new ArrayList<>();
                if (line.contains("해약환급금 보증")) secs.add("해약환급금 보증");
                if (line.contains("해약환급금 미보증")) secs.add("해약환급금 미보증");
                List<String> types = new ArrayList<>();
                Matcher m = JONG_PAREN_GLOBAL.matcher(line);
                while (m.find()) types.add(m.group());
                if (!secs.isEmpty() && !types.isEmpty()) {
                    for (String sec : secs) for (String typ : types) combos.add(Arrays.asList(primary, sec, typ));
                } else if (!secs.isEmpty()) {
                    for (String sec : secs) combos.add(Arrays.asList(primary, sec));
                } else if (!types.isEmpty()) {
                    for (String typ : types) combos.add(Arrays.asList(primary, typ));
                } else {
                    combos.add(Arrays.asList(primary));
                }
                currentMid = secs.isEmpty() ? "" : secs.get(0);
                continue;
            }
            if (tok.equals("해약환급금 보증") || tok.equals("해약환급금 미보증")) {
                currentMid = tok;
                continue;
            }
            if (JONG_PAREN_FULL.matcher(tok).matches()) {
                if ("보장형 계약".equals(currentTop)) {
                    combos.add(Arrays.asList(currentTop, "", tok));
                } else if (!currentTop.isEmpty()) {
                    if (!currentMid.isEmpty()) combos.add(Arrays.asList(currentTop, currentMid, tok));
                    else combos.add(Arrays.asList(currentTop, tok));
                } else {
                    pendingTypes.add(tok);
                }
                continue;
            }
            if (SANGHAE_JILBYEONG.matcher(tok).matches()) {
                combos.add(Arrays.asList(tok));
                continue;
            }

            if (hasEJung && tok.equals("만기환급형")) {
                pendingPairAxis = tok;
                continue;
            }
            if (hasEJung && "만기환급형".equals(pendingPairAxis) && tok.equals("표준형")) {
                combos.add(Arrays.asList(pendingPairAxis, tok));
                pendingPairAxis = null;
                continue;
            }
            if (hasEJung && pendingPairAxis != null) pendingPairAxis = null;
        }

        if (pendingRefund != null && !pendingRefundUsed) {
            combos.add(Arrays.asList(pendingRefund));
        }

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<List<String>> out = new ArrayList<>();
        for (List<String> combo : combos) {
            String key = String.join("\u0001", combo);
            if (seen.add(key)) out.add(combo);
        }
        return out;
    }

    // ── dedupe_axes / expand_annuity_combo ─────────────────────────
    public static List<List<String>> dedupeAxes(List<List<String>> axes) {
        List<List<String>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<String> axis : axes) {
            if (axis.isEmpty()) continue;
            String key = String.join("\u0001", axis);
            if (seen.add(key)) out.add(axis);
        }
        return out;
    }

    private static final Pattern YEAR_NUMBER = Pattern.compile("(\\d+)\\s*년");
    private static final Pattern HYUNG_TOKENS = Pattern.compile("([가-힣A-Za-z]+형)");

    public static List<List<String>> expandAnnuityCombo(List<String> combo) {
        List<List<String>> results = new ArrayList<>();
        if (combo == null || combo.isEmpty()) {
            results.add(combo == null ? new ArrayList<>() : new ArrayList<>(combo));
            return results;
        }
        results.add(new ArrayList<>());
        boolean hasType2 = false;
        for (String x : combo) if (PcText.cleanItem(x).startsWith("2종(")) { hasType2 = true; break; }

        for (String token : combo) {
            String t = PcText.cleanItem(token);
            List<List<String>> variants = new ArrayList<>();
            variants.add(Arrays.asList(t));

            if (t.contains("종신연금형")) {
                variants = new ArrayList<>();
                variants.add(Arrays.asList("종신연금형"));
            } else if (t.contains("확정연금형") || t.contains("확정기간연금형")) {
                List<String> years = findAll(YEAR_NUMBER, t);
                if (hasType2) {
                    LinkedHashSet<String> uniq = new LinkedHashSet<>(years);
                    List<String> sortedUniq = new ArrayList<>(uniq);
                    java.util.Collections.sort(sortedUniq);
                    if (sortedUniq.equals(Arrays.asList("10", "15", "20"))) {
                        years = Arrays.asList("5", "10", "15", "20");
                    }
                }
                if (!years.isEmpty()) {
                    variants = new ArrayList<>();
                    for (String y : years) variants.add(Arrays.asList("확정기간연금형", y + "년"));
                } else {
                    variants = new ArrayList<>();
                    variants.add(Arrays.asList("확정기간연금형"));
                }
            } else if (t.contains("환급플랜")) {
                List<String> years = findAll(YEAR_NUMBER, t);
                if (!years.isEmpty()) {
                    variants = new ArrayList<>();
                    for (String y : years) variants.add(Arrays.asList("환급플랜", y + "년형"));
                } else {
                    variants = new ArrayList<>();
                    variants.add(Arrays.asList("환급플랜"));
                }
            } else if (t.contains("보증기간부") || t.contains("보증금액부")) {
                List<String> kinds = findAll(HYUNG_TOKENS, t);
                List<String> keep = new ArrayList<>();
                for (String k : kinds) if (!k.equals("보증기간부") && !k.equals("보증금액부")) keep.add(k);
                variants = new ArrayList<>();
                if (!keep.isEmpty()) variants.add(Arrays.asList(keep.get(0)));
                else variants.add(new ArrayList<>());
            } else if (t.contains("상속연금형 종신플랜")) {
                variants = new ArrayList<>();
                variants.add(Arrays.asList("상속연금형", "종신플랜"));
            } else if (t.contains("상속연금형") && t.contains("환급플랜")) {
                List<String> years = findAll(YEAR_NUMBER, t);
                variants = new ArrayList<>();
                if (!years.isEmpty()) {
                    for (String y : years) variants.add(Arrays.asList("상속연금형", "환급플랜", y + "년형"));
                } else {
                    variants.add(Arrays.asList("상속연금형", "환급플랜"));
                }
            }

            List<List<String>> nextResults = new ArrayList<>();
            for (List<String> base : results) {
                for (List<String> v : variants) {
                    List<String> combined = new ArrayList<>(base);
                    combined.addAll(v);
                    nextResults.add(combined);
                }
            }
            results = nextResults;
        }
        return results;
    }

    private static List<String> findAll(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) out.add(m.groupCount() > 0 ? m.group(1) : m.group());
        return out;
    }
}
