package com.hanwha.setdata.extract.joinage;

import com.hanwha.setdata.extract.period.IpText;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-level helpers for 가입가능나이 (join age) extraction.
 * Mirrors top-level utilities from {@code extract_join_age_v2.py}.
 */
public final class JaText {

    private JaText() {}

    private static final int UFLAGS = Pattern.UNICODE_CHARACTER_CLASS;

    public static String normalizeWs(String s) {
        return IpText.normalizeWs(s);
    }

    public static String normalizeName(String s) {
        return IpText.normalizeName(s);
    }

    public static IpText.Period formatPeriod(String s) {
        return IpText.formatPeriod(s);
    }

    public static boolean isAnnuityProduct(java.util.Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        for (Object v : row.values()) {
            if (v == null) continue;
            String s = v.toString();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
        return sb.toString().contains("연금");
    }

    // ── _find_age_section ─────────────────────────────────────
    private static final Pattern AGE_SEC_NUM = Pattern.compile("^(\\d+)\\.\\s*보험기간", UFLAGS);
    private static final Pattern AGE_SEC_HAN = Pattern.compile("^[가-힣]\\.\\s*.*가입나이", UFLAGS);
    private static final Pattern NUM_DOT = Pattern.compile("^(\\d+)\\.", UFLAGS);
    private static final Pattern SEC_BOUND = Pattern.compile("^나\\.\\s*(?:종속특약|부가특약)", UFLAGS);

    public static int[] findAgeSection(List<String> lines) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = AGE_SEC_NUM.matcher(line);
            if (m.lookingAt() && line.contains("가입나이")) { start = i; break; }
            if (AGE_SEC_HAN.matcher(line).lookingAt()) { start = i; break; }
        }
        if (start < 0) {
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.contains("가입최저나이") || line.contains("가입최고나이")) {
                    start = Math.max(0, i - 2); break;
                }
            }
        }
        if (start < 0) return new int[]{0, lines.size()};

        Matcher startM = NUM_DOT.matcher(lines.get(start));
        int end = lines.size();
        if (startM.lookingAt()) {
            int startNum = Integer.parseInt(startM.group(1));
            for (int j = start + 1; j < lines.size(); j++) {
                Matcher m2 = NUM_DOT.matcher(lines.get(j));
                if (m2.lookingAt() && Integer.parseInt(m2.group(1)) > startNum) {
                    end = j; break;
                }
            }
        }
        for (int j = start + 1; j < end; j++) {
            if (SEC_BOUND.matcher(lines.get(j)).lookingAt()) {
                end = j; break;
            }
        }
        return new int[]{start, end};
    }

    // ── extract_min_age ─────────────────────────────────────
    private static final Pattern MIN_AGE_RE = Pattern.compile(
            "(?:가입\\s*최저\\s*나이|최저\\s*가입\\s*나이)\\s*[:：]?\\s*만?\\s*(\\d+)\\s*세", UFLAGS);
    private static final Pattern MIN_AGE_FALLBACK = Pattern.compile(
            "만?\\s*(\\d+)\\s*세?\\s*(?:\\([^)]*\\))?\\s*[~～\\-]\\s*(?:\\d+\\s*세|\\()", UFLAGS);

    public static String extractMinAge(List<String> lines) {
        for (String line : lines) {
            Matcher m = MIN_AGE_RE.matcher(line);
            if (m.find()) return m.group(1);
        }
        int[] sec = findAgeSection(lines);
        Integer min = null;
        for (int i = sec[0]; i < sec[1]; i++) {
            Matcher m = MIN_AGE_FALLBACK.matcher(lines.get(i));
            if (m.find()) {
                int v = Integer.parseInt(m.group(1));
                if (min == null || v < min) min = v;
            }
        }
        return min == null ? null : min.toString();
    }

    // ── detect_max_age_strategy ─────────────────────────────
    private static final Pattern FORMULA_1 = Pattern.compile(
            "가입최고나이\\s*[:：]?\\s*\\(?\\s*연금개시나이", UFLAGS);
    private static final Pattern FORMULA_2 = Pattern.compile(
            "연금개시나이\\s*[-\\-]\\s*1", UFLAGS);
    private static final Pattern TABLE_RE_1 = Pattern.compile(
            "(?:가입\\s*최고\\s*나이|최고\\s*가입\\s*나이).*아래와\\s*같", UFLAGS);
    private static final Pattern TABLE_RE_2 = Pattern.compile(
            "(?:가입\\s*최고\\s*나이|최고\\s*가입\\s*나이).*보험기간.*성별", UFLAGS);
    private static final Pattern TABLE_RE_3 = Pattern.compile(
            "(?:가입\\s*최고\\s*나이|최고\\s*가입\\s*나이).*성별.*보험기간", UFLAGS);
    private static final Pattern TABLE_RE_4 = Pattern.compile(
            "(?:가입\\s*최고\\s*나이|최고\\s*가입\\s*나이).*납입기간별", UFLAGS);
    private static final Pattern TABLE_RE_5 = Pattern.compile(
            "(?:가입\\s*최고\\s*나이|최고\\s*가입\\s*나이).*성별.*납입기간", UFLAGS);

    public static String detectMaxAgeStrategy(List<String> lines, List<List<List<String>>> tables) {
        for (String line : lines) {
            if (line.contains("연금개시나이") && line.contains("납입기간")
                    && (line.contains("가입최고") || line.contains("-"))) return "formula";
            if (FORMULA_1.matcher(line).find()) return "formula";
            if (line.contains("연금개시나이") && FORMULA_2.matcher(line).find()) return "formula";
        }
        for (String line : lines) {
            if (TABLE_RE_1.matcher(line).find()) return "table";
            if (TABLE_RE_2.matcher(line).find()) return "table";
            if (TABLE_RE_3.matcher(line).find()) return "table";
            if (TABLE_RE_4.matcher(line).find()) return "table";
            if (TABLE_RE_5.matcher(line).find()) return "table";
        }
        for (List<List<String>> table : tables) {
            if (table == null || table.isEmpty()) continue;
            StringBuilder hdr = new StringBuilder();
            for (String c : table.get(0)) {
                if (hdr.length() > 0) hdr.append(' ');
                hdr.append(c == null ? "" : c);
            }
            String h = hdr.toString();
            if (h.contains("최고가입나이") || h.contains("최고나이")) return "table";
        }
        return "simple";
    }

    // ── extract_simple_max_age ──────────────────────────────
    private static final Pattern SIMPLE_MAX = Pattern.compile(
            "(?:가입\\s*최고\\s*나이|최고\\s*가입\\s*나이)\\s*[:：]\\s*만?\\s*(\\d+)\\s*세", UFLAGS);

    public static String extractSimpleMaxAge(List<String> lines) {
        int[] sec = findAgeSection(lines);
        for (int i = sec[0]; i < sec[1]; i++) {
            Matcher m = SIMPLE_MAX.matcher(lines.get(i));
            if (m.find()) return m.group(1);
        }
        return null;
    }

    // ── detect_gender_split ─────────────────────────────────
    private static final Pattern SPIN_SEC = Pattern.compile(
            "^[가-힣라마]\\.\\s*연금개시나이", UFLAGS);

    public static boolean detectGenderSplit(List<String> lines, List<List<List<String>>> tables) {
        int[] sec = findAgeSection(lines);
        boolean passedSpin = false;
        for (int i = sec[0]; i < sec[1]; i++) {
            String line = lines.get(i);
            if (SPIN_SEC.matcher(line).lookingAt() || (line.trim().equals("연금개시나이") && i > sec[0])) {
                passedSpin = true;
            }
            if (line.contains("남자") && line.contains("여자") && !passedSpin) return true;
        }
        for (List<List<String>> table : tables) {
            if (table == null || table.isEmpty()) continue;
            StringBuilder hb = new StringBuilder();
            for (String c : table.get(0)) {
                if (hb.length() > 0) hb.append(' ');
                hb.append(c == null ? "" : c);
            }
            String headerText = hb.toString();
            if (headerText.contains("연금개시나이") && !headerText.contains("가입")) continue;
            int limit = Math.min(3, table.size());
            for (int r = 0; r < limit; r++) {
                StringBuilder rb = new StringBuilder();
                for (String c : table.get(r)) {
                    if (rb.length() > 0) rb.append(' ');
                    rb.append(c == null ? "" : c);
                }
                String rowText = rb.toString();
                if (rowText.contains("남자") && rowText.contains("여자")) return true;
            }
        }
        return false;
    }

    // ── _parse_age_cell ─────────────────────────────────────
    private static final Pattern AGE_CELL_FULL = Pattern.compile(
            "만?(\\d+)\\s*세\\s*(?:\\([^)]*\\))?\\s*[~\\-～]\\s*(\\d+)\\s*세", UFLAGS);
    private static final Pattern AGE_CELL_LOOSE = Pattern.compile(
            "만?(\\d+)\\s*세?\\s*[~\\-～]\\s*(\\d+)\\s*세?", UFLAGS);
    private static final Pattern AGE_CELL_SINGLE = Pattern.compile(
            "(\\d+)\\s*세", UFLAGS);

    /** Returns [min, max] or null. min may be null→""  represented as String "". */
    public static String[] parseAgeCell(String text) {
        String t = normalizeWs(text);
        if (t.isEmpty() || t.equals("-")) return null;
        Matcher m = AGE_CELL_FULL.matcher(t);
        if (!m.find()) {
            m = AGE_CELL_LOOSE.matcher(t);
            if (!m.find()) {
                Matcher m2 = AGE_CELL_SINGLE.matcher(t);
                if (m2.find()) return new String[]{null, m2.group(1)};
                return null;
            }
        }
        return new String[]{m.group(1), m.group(2)};
    }

    // ── _normalize_roman, _token_in_name, match_context_to_product ──
    public static String normalizeRoman(String text) {
        return text.replace("Ⅰ", "I").replace("Ⅱ", "II").replace("Ⅲ", "III");
    }

    private static boolean tokenInName(String token, String nameCompact) {
        int idx = nameCompact.indexOf(token);
        if (idx < 0) return false;
        int endIdx = idx + token.length();
        if (!token.isEmpty()) {
            char last = token.charAt(token.length() - 1);
            if ("IVX".indexOf(last) >= 0) {
                if (endIdx < nameCompact.length() && "IVX".indexOf(nameCompact.charAt(endIdx)) >= 0) return false;
            }
            char first = token.charAt(0);
            if (Character.isDigit(first) && idx > 0 && Character.isDigit(nameCompact.charAt(idx - 1))) return false;
        }
        return true;
    }

    private static final Pattern STRIP_RE = Pattern.compile("[\\s\\(\\)\\[\\]（）]");
    private static final Pattern PAYM_ONLY_CTX = Pattern.compile(
            "^(\\d+년납|\\d+세납|전기납|일시납|종신납)$", UFLAGS);
    private static final Pattern TOKENIZE = Pattern.compile("[\\s\\(\\)\\[\\]（）,，]+");
    private static final Pattern YEAR_HYUNG = Pattern.compile("(\\d+년)형$", UFLAGS);

    public static boolean matchContextToProduct(String context, String productName) {
        if (context == null || context.isEmpty()) return true;
        if (PAYM_ONLY_CTX.matcher(context.trim()).matches()) return true;
        String ctxCompact = normalizeRoman(STRIP_RE.matcher(context).replaceAll(""));
        String nameCompact = normalizeRoman(STRIP_RE.matcher(productName).replaceAll(""));
        int idx = nameCompact.indexOf(ctxCompact);
        if (idx >= 0) {
            int endIdx = idx + ctxCompact.length();
            if (!ctxCompact.isEmpty() && "IVX".indexOf(ctxCompact.charAt(ctxCompact.length() - 1)) >= 0) {
                if (endIdx < nameCompact.length() && "IVX".indexOf(nameCompact.charAt(endIdx)) >= 0) {
                    // fail, fall through
                } else {
                    return true;
                }
            } else {
                return true;
            }
        }
        // token-based
        List<String> tokens = new ArrayList<>();
        for (String t : TOKENIZE.split(normalizeRoman(context))) {
            if (!t.isEmpty()) tokens.add(t);
        }
        if (!tokens.isEmpty()) {
            boolean all = true;
            for (String t : tokens) if (!tokenInName(t, nameCompact)) { all = false; break; }
            if (all) return true;
            List<String> relaxed = new ArrayList<>();
            boolean changed = false;
            for (String t : tokens) {
                String r = YEAR_HYUNG.matcher(t).replaceAll("$1");
                if (!r.equals(t)) changed = true;
                relaxed.add(r);
            }
            if (changed) {
                boolean all2 = true;
                for (String t : relaxed) if (!tokenInName(t, nameCompact)) { all2 = false; break; }
                if (all2) return true;
            }
        }
        return false;
    }
}
