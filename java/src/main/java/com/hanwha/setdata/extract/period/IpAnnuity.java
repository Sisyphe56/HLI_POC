package com.hanwha.setdata.extract.period;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Annuity start age range extraction. Ports annuity-age helpers from
 * {@code extract_insurance_period_v2.py}.
 */
public final class IpAnnuity {

    private static final int UC = Pattern.UNICODE_CHARACTER_CLASS;

    private IpAnnuity() {}

    public record AgeRange(Integer min, Integer max) {
        public static final AgeRange EMPTY = new AgeRange(null, null);
    }

    // ── Section header detection ─────────────────────────────────────
    private static final Pattern HEADER_KOR = Pattern.compile("^[가-힣]\\.\\s*연금개시나이", UC);
    private static final Pattern PAREN_HDR = Pattern.compile("^\\(\\d+\\)\\s*연금개시나이$", UC);

    public static boolean isAnnuitySectionHeader(String prev, String line, String next) {
        if (!line.contains("연금개시나이")) return false;
        if (HEADER_KOR.matcher(line).find() && !line.contains("가입나이")) return true;
        if ("연금개시나이".equals(line)
                && (prev.contains("연금개시나이") || prev.contains("연금개시"))) return true;
        if ("연금개시나이".equals(line)
                && (next.contains("구분") || next.contains("종신연금형") || next.contains("확정기간연금형"))) return true;
        if (PAREN_HDR.matcher(line).find()) return true;
        return false;
    }

    private static final Pattern AGE_RANGE = Pattern.compile(
            "(?:만\\s*)?(\\d{2,3})\\s*세?\\s*[~\\-]\\s*(\\d{2,3})\\s*세?", UC);
    private static final Pattern INLINE_AGE = Pattern.compile("연금개시나이\\s*[:：]\\s*(.+)", UC);
    private static final Pattern SUB_SECTION = Pattern.compile("^[가-하]+\\.", UC);
    private static final Pattern NUM_SECTION = Pattern.compile("^\\d+\\.", UC);
    private static final Pattern PAREN_NUM = Pattern.compile("^\\(\\d+\\)", UC);

    public static Set<Integer> extractAgesFromText(String text) {
        Set<Integer> ages = new LinkedHashSet<>();
        Matcher m = AGE_RANGE.matcher(text);
        while (m.find()) {
            int low = Integer.parseInt(m.group(1));
            int high = Integer.parseInt(m.group(2));
            if (low >= 20 && low <= 120 && high >= 20 && high <= 120 && low <= high) {
                for (int a = low; a <= high; a++) ages.add(a);
            }
        }
        return ages;
    }

    public static AgeRange extractAnnuityAgeRangeByType(
            List<List<List<String>>> allTables, String annuityType, String contextFilter) {
        for (List<List<String>> table : allTables) {
            int colIdx = -1;
            int headerRowIdx = -1;
            for (int ri = 0; ri < table.size(); ri++) {
                List<String> row = table.get(ri);
                for (int ci = 0; ci < row.size(); ci++) {
                    String cell = (row.get(ci) == null ? "" : row.get(ci)).replace("\n", " ");
                    if (cell.contains(annuityType)) {
                        colIdx = ci;
                        headerRowIdx = ri;
                        break;
                    }
                }
                if (colIdx >= 0) break;
            }
            if (colIdx < 0) continue;

            Set<Integer> ages = new LinkedHashSet<>();
            String lastVal = "";
            String lastCtx = "";
            for (int ri = headerRowIdx + 1; ri < table.size(); ri++) {
                List<String> row = table.get(ri);
                String ctxCell = row.isEmpty() ? "" :
                        (row.get(0) == null ? "" : row.get(0)).replace("\n", " ").trim();
                if (!ctxCell.isEmpty()) lastCtx = ctxCell;
                if (colIdx >= row.size()) continue;
                String cellVal = (row.get(colIdx) == null ? "" : row.get(colIdx)).replace("\n", " ").trim();
                if (!cellVal.isEmpty() && !"-".equals(cellVal)) lastVal = cellVal;
                else if (cellVal.isEmpty() && !lastVal.isEmpty()) cellVal = lastVal;

                if (contextFilter != null && !contextFilter.isEmpty() && !lastCtx.contains(contextFilter)) continue;
                ages.addAll(extractAgesFromText(cellVal));
            }
            if (!ages.isEmpty()) {
                int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE;
                for (int a : ages) { mn = Math.min(mn, a); mx = Math.max(mx, a); }
                return new AgeRange(mn, mx);
            }
        }
        return AgeRange.EMPTY;
    }

    public static AgeRange extractAnnuityAgeRangeBySubtype(List<String> lines, String subtype) {
        for (int i = 0; i < lines.size(); i++) {
            String prev = i > 0 ? lines.get(i - 1) : "";
            String line = lines.get(i);
            String next = i + 1 < lines.size() ? lines.get(i + 1) : "";
            if (!isAnnuitySectionHeader(prev, line, next)) continue;

            for (int j = i + 1; j < Math.min(lines.size(), i + 30); j++) {
                String nxt = lines.get(j);
                if (SUB_SECTION.matcher(nxt).find()
                        || NUM_SECTION.matcher(nxt).find()
                        || (PAREN_NUM.matcher(nxt).find() && !nxt.contains("연금개시나이"))) break;
                if (nxt.contains(subtype)) {
                    Matcher m = AGE_RANGE.matcher(nxt);
                    if (m.find()) {
                        int low = Integer.parseInt(m.group(1));
                        int high = Integer.parseInt(m.group(2));
                        if (low >= 20 && low <= 120 && high >= 20 && high <= 120 && low <= high) {
                            return new AgeRange(low, high);
                        }
                    }
                }
            }
        }
        return AgeRange.EMPTY;
    }

    public static AgeRange extractAnnuityAgeRange(List<String> lines) {
        Set<Integer> ages = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String prev = i > 0 ? lines.get(i - 1) : "";
            String line = lines.get(i);
            String next = i + 1 < lines.size() ? lines.get(i + 1) : "";
            if (!isAnnuitySectionHeader(prev, line, next)) continue;

            Matcher inline = INLINE_AGE.matcher(line);
            if (inline.find()) ages.addAll(extractAgesFromText(inline.group(1)));

            for (int j = i + 1; j < Math.min(lines.size(), i + 30); j++) {
                String nxt = lines.get(j);
                if (SUB_SECTION.matcher(nxt).find()
                        || NUM_SECTION.matcher(nxt).find()
                        || (PAREN_NUM.matcher(nxt).find() && !nxt.contains("연금개시나이"))) break;
                if ("연금개시나이".equals(nxt)) continue;
                ages.addAll(extractAgesFromText(nxt));
            }
        }
        if (ages.isEmpty()) return AgeRange.EMPTY;
        int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE;
        for (int a : ages) { mn = Math.min(mn, a); mx = Math.max(mx, a); }
        return new AgeRange(mn, mx);
    }
}
