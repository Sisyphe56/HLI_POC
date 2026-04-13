package com.hanwha.setdata.extract.period;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Insurance/payment period extraction and context filtering.
 * Ports the section-finding, period extraction, context-map helpers, and
 * matrix-invalid-pair extraction from
 * {@code extract_insurance_period_v2.py}.
 */
public final class IpPeriods {

    private static final int UC = Pattern.UNICODE_CHARACTER_CLASS;

    private IpPeriods() {}

    // ── Section finding ──────────────────────────────────────────────
    private static final Pattern NUM_SECTION = Pattern.compile("^(\\d+)\\.", UC);
    private static final Pattern INS_SECTION_NUM = Pattern.compile("^\\d+\\.\\s*보험기간", UC);
    private static final Pattern INS_SECTION_KOR = Pattern.compile("^[가-힣]\\.\\s*보험기간", UC);
    private static final Pattern SUB_DEP = Pattern.compile("^나\\.\\s*(?:종속특약|부가특약)", UC);

    public record Range(int start, int end) {}

    public static Range findPeriodSection(List<String> lines) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (INS_SECTION_NUM.matcher(line).find() && line.contains("납입기간")) {
                start = i; break;
            }
            if (INS_SECTION_KOR.matcher(line).find()) {
                start = i; break;
            }
        }
        if (start < 0) return new Range(0, lines.size());
        Matcher sm = NUM_SECTION.matcher(lines.get(start));
        Integer startNum = sm.find() ? Integer.parseInt(sm.group(1)) : null;
        int end = lines.size();
        for (int j = start + 1; j < lines.size(); j++) {
            Matcher m = NUM_SECTION.matcher(lines.get(j));
            if (m.find() && startNum != null && Integer.parseInt(m.group(1)) > startNum) {
                end = j; break;
            }
        }
        for (int j = start + 1; j < end; j++) {
            if (SUB_DEP.matcher(lines.get(j)).find()) {
                end = j; break;
            }
        }
        return new Range(start, end);
    }

    // ── Insurance period extraction ──────────────────────────────────
    private static final Pattern SE_MANKI = Pattern.compile("(\\d{2,3})\\s*세\\s*만기", UC);
    private static final Pattern YEAR_MANKI = Pattern.compile("(\\d{1,3})\\s*년\\s*만기", UC);
    private static final Pattern YEAR_STANDALONE = Pattern.compile("(?<!\\d)(\\d{1,3})\\s*년(?!\\s*[납만세])", UC);
    private static final Pattern JONGSIN_EXPLICIT = Pattern.compile("종신만기|보험기간\\s*[:：]?\\s*종신", UC);
    private static final Pattern JONGSIN_OTHER = Pattern.compile("(?<![가-힣])종신(?!납|연금|보험|플랜|지급|보장|갱신)", UC);
    private static final Pattern JONGSIN_DATA_ROW = Pattern.compile("(?<![가-힣])종신\\s+(?:일시납|종신납|\\d+년납|\\d+세납)", UC);
    private static final Pattern GU_BUN_HEAD = Pattern.compile("^구\\s*분", UC);
    private static final Pattern TABLE_HEADER = Pattern.compile("^[가-힣]\\.", UC);
    private static final Pattern NUM_SEC_HEADER = Pattern.compile("^\\d+\\.", UC);
    private static final Pattern YEAR_ROW_LEAD = Pattern.compile("^(\\d{1,3})\\s*년\\b", UC);
    private static final Pattern AGE_NOT_MANKI = Pattern.compile("(\\d{2,3})\\s*세(?!\\s*만기)", UC);
    private static final Pattern MANKI_WORD = Pattern.compile("만기", UC);

    public static Set<String> extractInsurancePeriods(List<String> lines, String productName) {
        Range r = findPeriodSection(lines);
        List<String> section = lines.subList(r.start, r.end);
        Set<String> periods = new LinkedHashSet<>();

        for (String line : section) {
            Matcher m = SE_MANKI.matcher(line);
            while (m.find()) periods.add(m.group(1) + "세");
            m = YEAR_MANKI.matcher(line);
            while (m.find()) periods.add(m.group(1) + "년");

            if (line.contains("보험기간")) {
                m = YEAR_STANDALONE.matcher(line);
                while (m.find()) periods.add(m.group(1) + "년");
            }
            if (JONGSIN_EXPLICIT.matcher(line).find()) {
                periods.add("종신");
            } else if (JONGSIN_OTHER.matcher(line).find()) {
                if (!JONGSIN_DATA_ROW.matcher(line).find()
                        && !GU_BUN_HEAD.matcher(line).find()) {
                    periods.add("종신");
                }
            }
        }

        // Table row: header "보험기간 가입나이 납입기간" next rows with "n년"
        for (int i = r.start; i < r.end; i++) {
            String line = lines.get(i);
            if (line.contains("보험기간") && line.contains("가입나이") && line.contains("납입기간")) {
                for (int j = i + 1; j < Math.min(i + 10, r.end); j++) {
                    String rowLine = lines.get(j);
                    if (TABLE_HEADER.matcher(rowLine).find() || NUM_SEC_HEADER.matcher(rowLine).find()) break;
                    Matcher mm = YEAR_ROW_LEAD.matcher(rowLine);
                    if (mm.find()) periods.add(mm.group(1) + "년");
                }
            }
        }

        // Merged cell pattern: "10년 60세 65세 ..." then "만기 만기 ..."
        for (int i = r.start; i < r.end - 1; i++) {
            String line = lines.get(i);
            if (line.contains("~")) continue;
            List<String> ageMatches = new ArrayList<>();
            Matcher am = AGE_NOT_MANKI.matcher(line);
            while (am.find()) ageMatches.add(am.group(1));
            if (ageMatches.size() < 2) continue;
            boolean foundManki = false;
            for (int j = i + 1; j < Math.min(i + 4, r.end); j++) {
                Matcher mm = MANKI_WORD.matcher(lines.get(j));
                int count = 0;
                while (mm.find()) count++;
                if (count >= 2) { foundManki = true; break; }
            }
            if (foundManki) {
                for (String age : ageMatches) periods.add(age + "세");
            }
        }

        return periods;
    }

    // ── Payment period extraction ────────────────────────────────────
    public record PayResult(Set<String> periods, boolean hasJeonginap) {}

    private static final Pattern RANGE_YEAR_NAP = Pattern.compile("(\\d{1,3})\\s*[~\\-]\\s*(\\d{1,3})\\s*년\\s*납", UC);
    private static final Pattern YEAR_NAP = Pattern.compile("(\\d{1,3})\\s*년\\s*납", UC);
    private static final Pattern SE_NAP = Pattern.compile("(\\d{2,3})\\s*세\\s*납", UC);
    private static final Pattern LUMP_DATA_ROW = Pattern.compile("^(?:\\d+세만기|\\d+년만기|종신)\\s+일시납", UC);
    private static final Pattern LUMP_QUALIFIED = Pattern.compile(
            "(?:전환형|거치형|즉시형).{0,10}일시납"
            + "|일시납.{0,10}경우|단[,，].{0,40}일시납|경우.{0,10}일시납"
            + "|(?:계좌이체|승계).{0,15}일시납", UC);

    public static PayResult extractPaymentPeriods(List<String> lines, String productName) {
        Range r = findPeriodSection(lines);
        Set<String> periods = new LinkedHashSet<>();
        boolean hasJeonginap = false;

        for (int idx = r.start; idx < r.end; idx++) {
            String line = lines.get(idx);

            Matcher m = RANGE_YEAR_NAP.matcher(line);
            while (m.find()) {
                int lo = Integer.parseInt(m.group(1));
                int hi = Integer.parseInt(m.group(2));
                if (lo <= hi && hi <= 100) {
                    for (int y = lo; y <= hi; y++) periods.add(y + "년납");
                }
            }
            m = YEAR_NAP.matcher(line);
            while (m.find()) periods.add(m.group(1) + "년납");
            m = SE_NAP.matcher(line);
            while (m.find()) periods.add(m.group(1) + "세납");

            if (line.contains("일시납")) {
                boolean isTableDataRow = LUMP_DATA_ROW.matcher(line).find();
                boolean isQualified = LUMP_QUALIFIED.matcher(line).find();
                if (!isTableDataRow && !isQualified) periods.add("일시납");
            }
            if (line.contains("전기납")) hasJeonginap = true;
            if (line.contains("종신납") && (line.contains("납입기간") || line.contains("납입주기"))) {
                periods.add("종신");
            }
        }
        return new PayResult(periods, hasJeonginap);
    }

    // ── Table context → period map ───────────────────────────────────
    private static final Pattern PERIOD_CELL_PAT = Pattern.compile(
            "\\d+년납|\\d+세납|종신납|일시납", UC);
    private static final Pattern EXCL_COND = Pattern.compile("미만|이상|이하|초과", UC);
    private static final Pattern INS_CELL_PAT = Pattern.compile(
            "종신|세만기|년만기|\\d+세|\\d+년", UC);

    public static Map<String, Set<String>> extractContextPeriodMap(
            List<List<List<String>>> allTables, String periodType) {
        Map<String, Set<String>> result = new LinkedHashMap<>();

        for (List<List<String>> table : allTables) {
            if (table.isEmpty() || table.get(0).isEmpty()) continue;
            StringBuilder hb = new StringBuilder();
            for (Object c : table.get(0)) {
                if (hb.length() > 0) hb.append(' ');
                hb.append(c == null ? "" : String.valueOf(c));
            }
            String header = IpText.normalizeWs(hb.toString());
            if (!header.contains(periodType)) continue;

            int maxLen = 0;
            for (List<String> row : table) maxLen = Math.max(maxLen, row.size());
            String[] prevCells = new String[maxLen];
            for (int k = 0; k < maxLen; k++) prevCells[k] = "";

            for (int ri = 1; ri < table.size(); ri++) {
                List<String> row = table.get(ri);
                List<String> cells = new ArrayList<>();
                for (int ci = 0; ci < row.size(); ci++) {
                    String val = IpText.normalizeWs(row.get(ci) == null ? "" : row.get(ci));
                    if (val.isEmpty() && ci < prevCells.length) val = prevCells[ci];
                    cells.add(val);
                    if (ci < prevCells.length) prevCells[ci] = val;
                }

                String periodVal = "";
                List<String> contextParts = new ArrayList<>();
                for (String cell : cells) {
                    String clean = cell.trim();
                    if (clean.isEmpty()) continue;
                    if (PERIOD_CELL_PAT.matcher(clean).find()
                            && !EXCL_COND.matcher(clean).find()) {
                        periodVal = clean;
                    } else if ("보험기간".equals(periodType)
                            && INS_CELL_PAT.matcher(clean).find()) {
                        periodVal = clean;
                    } else {
                        contextParts.add(clean);
                    }
                }
                if (periodVal.isEmpty()) continue;

                String contextKey;
                if (contextParts.isEmpty()) contextKey = "__default__";
                else contextKey = String.join("·", contextParts);

                result.computeIfAbsent(contextKey, k -> new LinkedHashSet<>()).add(periodVal);
            }
        }

        return result;
    }

    // ── Filter periods by context ────────────────────────────────────
    private static final Pattern CTX_ALT_SPLIT = Pattern.compile("[,，]\\s*", UC);
    private static final Pattern LEADING_DIGITS = Pattern.compile("^(\\d+)");
    private static final Pattern YEARNAP_ONLY = Pattern.compile("^\\d+년납$", UC);

    public static Set<String> filterPeriodsByContext(
            Set<String> allPeriods, Map<String, Set<String>> contextMap, String productName) {
        if (contextMap == null || contextMap.isEmpty() || productName == null || productName.isEmpty()) {
            return allPeriods;
        }
        String nameNorm = IpText.normalizeWs(productName);
        Set<String> matchedPeriods = new LinkedHashSet<>();

        boolean hasGeneral = false, hasSangsaeng = false;
        for (String k : contextMap.keySet()) {
            if (k.contains("일반형")) hasGeneral = true;
            if (k.contains("상생협력형")) hasSangsaeng = true;
        }
        boolean nameHasSs = nameNorm.contains("상생");

        if (hasGeneral && hasSangsaeng && nameHasSs) {
            for (Map.Entry<String, Set<String>> e : contextMap.entrySet()) {
                if (e.getKey().contains("상생협력형")) matchedPeriods.addAll(e.getValue());
            }
        }
        if (!(hasGeneral && hasSangsaeng && nameHasSs)) {
            for (Map.Entry<String, Set<String>> e : contextMap.entrySet()) {
                String ctxKey = e.getKey();
                if ("__default__".equals(ctxKey)) continue;
                if (hasGeneral && hasSangsaeng && ctxKey.contains("상생협력형")) continue;
                String[] alternatives = CTX_ALT_SPLIT.split(ctxKey);
                boolean anyAltMatch = false;
                for (String alt : alternatives) {
                    List<String> ctxTokens = new ArrayList<>();
                    for (String t : alt.split("·")) {
                        String n = IpText.normalizeWs(t);
                        if (!n.isEmpty()) ctxTokens.add(n);
                    }
                    if (ctxTokens.isEmpty()) continue;
                    boolean allMatch = true;
                    for (String token : ctxTokens) {
                        String tokenCompact = IpText.stripAllWs(token);
                        String nameCompact = IpText.stripAllWs(nameNorm);
                        if (!nameCompact.contains(tokenCompact)) { allMatch = false; break; }
                    }
                    if (allMatch) { anyAltMatch = true; break; }
                }
                if (anyAltMatch) matchedPeriods.addAll(e.getValue());
            }
        }

        if (matchedPeriods.isEmpty()) return allPeriods;

        Set<String> filtered = new LinkedHashSet<>();
        for (String p : allPeriods) {
            String pClean = IpText.stripAllWs(p);
            for (String mp : matchedPeriods) {
                String mpClean = IpText.stripAllWs(mp);
                Matcher pn = LEADING_DIGITS.matcher(pClean);
                Matcher mpn = LEADING_DIGITS.matcher(mpClean);
                if (pn.find() && mpn.find()) {
                    String pSuffix = pClean.substring(pn.end());
                    String mpSuffix = mpClean.substring(mpn.end());
                    if (pn.group(1).equals(mpn.group(1))
                            && (pSuffix.equals(mpSuffix) || pSuffix.contains(mpSuffix) || mpSuffix.contains(pSuffix))) {
                        filtered.add(p); break;
                    }
                } else {
                    if (pClean.contains(mpClean) || mpClean.contains(pClean)) {
                        filtered.add(p); break;
                    }
                }
            }
        }
        // add context-derived range values
        for (String mp : matchedPeriods) {
            String mpClean = IpText.stripAllWs(mp);
            if (YEARNAP_ONLY.matcher(mpClean).matches()) filtered.add(mpClean);
        }

        if (hasGeneral && hasSangsaeng && !matchedPeriods.isEmpty()) {
            return filtered;
        }
        return filtered.isEmpty() ? allPeriods : filtered;
    }

    // ── Section "(N) context" map ────────────────────────────────────
    public record SectionCtx(Set<String> ins, Set<String> pay) {
        public SectionCtx() { this(new LinkedHashSet<>(), new LinkedHashSet<>()); }
    }

    private static final Pattern PAREN_DIGIT_CTX = Pattern.compile("^\\([\\d]+\\)\\s*(.+)", UC);
    private static final Pattern PAREN_REF = Pattern.compile("^제\\(?\\d", UC);
    private static final Pattern PAREN_SUB_CTX = Pattern.compile("^\\(([가-힣])\\)\\s*(.+)", UC);
    private static final Pattern SECT_HDR = Pattern.compile("^[가-힣]\\.\\s", UC);

    public static Map<String, SectionCtx> extractSectionContextMap(List<String> lines) {
        Range r = findPeriodSection(lines);
        Map<String, SectionCtx> result = new LinkedHashMap<>();
        String currentCtx = "", currentSubCtx = "";
        for (int i = r.start; i < r.end; i++) {
            String line = lines.get(i);
            if (SECT_HDR.matcher(line).find()) {
                currentCtx = ""; currentSubCtx = ""; continue;
            }
            Matcher m = PAREN_DIGIT_CTX.matcher(line);
            if (m.find()) {
                String ctxText = IpText.normalizeWs(m.group(1).stripTrailing());
                if (PAREN_REF.matcher(ctxText).find()) continue;
                currentCtx = ctxText;
                currentSubCtx = "";
                result.computeIfAbsent(currentCtx, k -> new SectionCtx());
                continue;
            }
            Matcher sm = PAREN_SUB_CTX.matcher(line);
            if (sm.find()) {
                String subText = IpText.normalizeWs(sm.group(2).stripTrailing());
                if (!subText.isEmpty() && !PAREN_REF.matcher(subText).find()) {
                    currentSubCtx = subText;
                    result.computeIfAbsent(currentSubCtx, k -> new SectionCtx());
                    continue;
                }
            }
            if (currentCtx.isEmpty() && currentSubCtx.isEmpty()) continue;
            String effective = currentSubCtx.isEmpty() ? currentCtx : currentSubCtx;
            SectionCtx data = result.computeIfAbsent(effective, k -> new SectionCtx());

            Matcher mm = YEAR_MANKI.matcher(line);
            while (mm.find()) data.ins.add(mm.group(1) + "년");
            mm = SE_MANKI.matcher(line);
            while (mm.find()) data.ins.add(mm.group(1) + "세");
            if (JONGSIN_EXPLICIT.matcher(line).find()) data.ins.add("종신");

            mm = YEAR_NAP.matcher(line);
            while (mm.find()) data.pay.add(mm.group(1) + "년납");
            mm = SE_NAP.matcher(line);
            while (mm.find()) data.pay.add(mm.group(1) + "세납");
            if (line.contains("전기납")) data.pay.add("전기납");
            if (line.contains("일시납")) data.pay.add("일시납");
        }

        Map<String, SectionCtx> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, SectionCtx> e : result.entrySet()) {
            SectionCtx v = e.getValue();
            if (!v.ins.isEmpty() || !v.pay.isEmpty()) filtered.put(e.getKey(), v);
        }
        return filtered;
    }

    // ── Text context → pay map ───────────────────────────────────────
    private static final Pattern NAP_SECT_HEADER_1 = Pattern.compile("^[가-힣]\\.\\s*.*납입기간", UC);
    private static final Pattern NAP_SECT_HEADER_2 = Pattern.compile("^[가-힣]\\.\\s*보험료\\s*납입기간", UC);
    private static final Pattern NEXT_SECT = Pattern.compile("^[가-힣]\\.\\s*(?!.*납입)", UC);
    private static final Pattern CTX_LINE_PAT = Pattern.compile("^\\(?[\\d가-힣]+\\)?\\s*(.+?)\\s*[:：]\\s*(.+)", UC);
    private static final Pattern SUB_CTX_LINE = Pattern.compile("^\\(?[가-힣]+\\)?\\s*(.+?)\\s*[:：]\\s*(.+)", UC);
    private static final Pattern RANGE_YEAR_NAP_OPT = Pattern.compile("(\\d+)\\s*[~\\-]\\s*(\\d+)\\s*년\\s*납?", UC);
    private static final Pattern YEAR_NAP_OPT = Pattern.compile("(?<!\\d)(\\d{1,3})\\s*년\\s*납?", UC);

    public static Map<String, Set<String>> extractTextContextPayMap(List<String> lines) {
        Range r = findPeriodSection(lines);
        Map<String, Set<String>> result = new LinkedHashMap<>();

        boolean inPaySection = false;
        String currentContext = "";
        for (int i = r.start; i < r.end; i++) {
            String line = lines.get(i);
            if (NAP_SECT_HEADER_1.matcher(line).find() || NAP_SECT_HEADER_2.matcher(line).find()) {
                inPaySection = true; continue;
            }
            if (inPaySection && NEXT_SECT.matcher(line).find()) break;
            if (!inPaySection) continue;

            Matcher m = CTX_LINE_PAT.matcher(line);
            if (m.find()) {
                String ctx = IpText.normalizeWs(m.group(1));
                String valsText = m.group(2);
                Set<String> periods = parsePayPeriodsFromText(valsText);
                if (!periods.isEmpty()) result.put(ctx, periods);
                currentContext = ctx;
                continue;
            }
            Matcher sm = SUB_CTX_LINE.matcher(line);
            if (sm.find() && !currentContext.isEmpty()) {
                String subCtx = IpText.normalizeWs(sm.group(1));
                String ctx = currentContext + "·" + subCtx;
                String valsText = sm.group(2);
                Set<String> periods = parsePayPeriodsFromText(valsText);
                if (!periods.isEmpty()) result.put(ctx, periods);
            }
        }
        return result;
    }

    private static Set<String> parsePayPeriodsFromText(String valsText) {
        Set<String> periods = new LinkedHashSet<>();
        Matcher rm = RANGE_YEAR_NAP_OPT.matcher(valsText);
        while (rm.find()) {
            int lo = Integer.parseInt(rm.group(1));
            int hi = Integer.parseInt(rm.group(2));
            for (int y = lo; y <= hi; y++) periods.add(y + "년납");
        }
        rm = YEAR_NAP_OPT.matcher(valsText);
        while (rm.find()) {
            int endPos = rm.end();
            String after = valsText.substring(endPos, Math.min(endPos + 2, valsText.length()));
            if (after.startsWith("이상") || after.startsWith("이내") || after.startsWith("이하")) continue;
            periods.add(rm.group(1) + "년납");
        }
        if (valsText.contains("일시납")) periods.add("일시납");
        if (valsText.contains("전기납")) periods.add("전기납");
        if (valsText.contains("종신납")) periods.add("종신");
        return periods;
    }

    // ── Matrix invalid pairs ─────────────────────────────────────────
    public record Pair(String ins, String pay) {}

    private static final Pattern GUBUN_HEAD = Pattern.compile("^\\s*구분\\s", UC);
    private static final Pattern HEADER_PERIOD = Pattern.compile("(\\d+(?:년|세))\\s*만기", UC);
    private static final Pattern PAY_ROW = Pattern.compile("^(\\d+(?:년|세)\\s*납|전기납|일시납)\\s+(.+)", UC);
    private static final Pattern PAY_ROW_START = Pattern.compile("^(\\d+(?:년|세)\\s*납|전기납|일시납)", UC);

    public static Set<Pair> extractMatrixInvalidPairs(List<String> lines) {
        Set<Pair> invalidPairs = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!GUBUN_HEAD.matcher(line).find()) continue;
            List<String> headerPeriods = new ArrayList<>();
            Matcher hm = HEADER_PERIOD.matcher(line);
            while (hm.find()) headerPeriods.add(hm.group(1));
            if (headerPeriods.size() < 2) continue;

            for (int j = i + 1; j < Math.min(i + 20, lines.size()); j++) {
                String dataLine = lines.get(j).trim();
                if (dataLine.isEmpty()) continue;
                Matcher pm = PAY_ROW.matcher(dataLine);
                if (!pm.find()) break;
                String payLabel = IpText.stripAllWs(pm.group(1));
                String rest = pm.group(2);
                String[] cells = rest.trim().split("\\s+");
                for (int colIdx = 0; colIdx < cells.length; colIdx++) {
                    if (colIdx >= headerPeriods.size()) break;
                    String cellClean = cells[colIdx].trim();
                    if ("-".equals(cellClean)) {
                        invalidPairs.add(new Pair(headerPeriods.get(colIdx), payLabel));
                    }
                }
            }
            break;
        }
        return invalidPairs;
    }

    public static Set<Pair> extractMatrixInvalidPairsFromTables(List<List<List<String>>> tables) {
        Set<Pair> invalidPairs = new LinkedHashSet<>();
        for (List<List<String>> table : tables) {
            if (table == null || table.size() < 2) continue;
            List<String> header = new ArrayList<>();
            for (Object c : table.get(0)) header.add(c == null ? "" : String.valueOf(c).trim());
            if (header.isEmpty() || !header.get(0).contains("구분")) continue;
            List<int[]> colPeriods = new ArrayList<>();
            List<String> colLabels = new ArrayList<>();
            for (int ci = 1; ci < header.size(); ci++) {
                Matcher m = HEADER_PERIOD.matcher(header.get(ci));
                if (m.find()) {
                    colPeriods.add(new int[]{ci});
                    colLabels.add(m.group(1));
                }
            }
            if (colPeriods.size() < 2) continue;

            Set<Pair> local = new LinkedHashSet<>();
            for (int ri = 1; ri < table.size(); ri++) {
                List<String> row = table.get(ri);
                if (row == null || row.isEmpty()) continue;
                String cell0 = row.get(0) == null ? "" : row.get(0).trim();
                Matcher pm = PAY_ROW_START.matcher(cell0);
                if (!pm.find()) continue;
                String payLabel = IpText.stripAllWs(pm.group(1));
                for (int k = 0; k < colPeriods.size(); k++) {
                    int ci = colPeriods.get(k)[0];
                    if (ci < row.size()) {
                        String val = row.get(ci) == null ? "" : row.get(ci).trim();
                        if ("-".equals(val)) local.add(new Pair(colLabels.get(k), payLabel));
                    }
                }
            }
            if (!local.isEmpty()) { invalidPairs.addAll(local); break; }
        }
        return invalidPairs;
    }
}
