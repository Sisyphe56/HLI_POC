package com.hanwha.setdata.extract.annuity;

import com.fasterxml.jackson.databind.JsonNode;
import com.hanwha.setdata.config.OverridesConfig;
import com.hanwha.setdata.util.Normalizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text utilities and shared constants/config for
 * {@code extract_annuity_age_v2.py} Java port.
 */
public final class AaText {

    private AaText() {}

    public static final String CONTEXT_KEY_SEPARATOR = "||";

    /** Default annuity context tokens (Python _DEFAULT_ANNUITY_CONTEXT_TOKENS). */
    public static final Map<String, String> DEFAULT_ANNUITY_CONTEXT_TOKENS;
    static {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("1종(신계약체결용)", "S1");
        m.put("2종(계좌이체용)", "S1");
        m.put("종신연금형", "S2");
        m.put("확정기간연금형", "S2");
        m.put("상속연금형", "S2");
        m.put("거치형", "S3");
        m.put("적립형", "S3");
        m.put("개인형", "S4");
        m.put("신부부형", "S4");
        m.put("표준형", "S2");
        m.put("기본형", "S2");
        m.put("종신플랜", "S5");
        m.put("환급플랜", "S5");
        DEFAULT_ANNUITY_CONTEXT_TOKENS = m;
    }

    /** The effective tokens map (may be overridden by product_overrides.json). */
    private static Map<String, String> ANNUITY_CONTEXT_TOKENS = DEFAULT_ANNUITY_CONTEXT_TOKENS;
    private static List<String> CONTEXT_GROUPS = computeGroups(DEFAULT_ANNUITY_CONTEXT_TOKENS);

    private static List<String> computeGroups(Map<String, String> tokens) {
        TreeSet<String> groups = new TreeSet<>(tokens.values());
        return new ArrayList<>(groups);
    }

    public static void loadFromOverrides(OverridesConfig cfg) {
        if (cfg == null) {
            ANNUITY_CONTEXT_TOKENS = DEFAULT_ANNUITY_CONTEXT_TOKENS;
            CONTEXT_GROUPS = computeGroups(DEFAULT_ANNUITY_CONTEXT_TOKENS);
            return;
        }
        JsonNode node = cfg.section("annuity_context_tokens").get("tokens");
        if (node != null && node.isObject()) {
            LinkedHashMap<String, String> tokens = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                tokens.put(e.getKey(), e.getValue().asText());
            }
            if (!tokens.isEmpty()) {
                ANNUITY_CONTEXT_TOKENS = tokens;
                CONTEXT_GROUPS = computeGroups(tokens);
                return;
            }
        }
        ANNUITY_CONTEXT_TOKENS = DEFAULT_ANNUITY_CONTEXT_TOKENS;
        CONTEXT_GROUPS = computeGroups(DEFAULT_ANNUITY_CONTEXT_TOKENS);
    }

    public static Map<String, String> tokens() { return ANNUITY_CONTEXT_TOKENS; }
    public static List<String> contextGroups() { return CONTEXT_GROUPS; }

    // ── normalize helpers ──────────────────────────────────────────
    private static final Pattern WS = Pattern.compile("\\s+");

    public static String normalizeWs(String value) {
        String text = Normalizer.nfc(value == null ? "" : value).replace("\u200b", "");
        return WS.matcher(text).replaceAll(" ").trim();
    }

    public static String normalizeMatchToken(String value) {
        return WS.matcher(normalizeWs(value)).replaceAll("");
    }

    private static final Pattern NAME_EXT = Pattern.compile("\\.(pdf|docx|json)$");
    private static final Pattern NAME_UP_TO_SAEOP = Pattern.compile("^(.+사업방법서)");

    public static String normalizeName(String value) {
        String text = normalizeWs(value);
        text = NAME_EXT.matcher(text).replaceAll("");
        Matcher m = NAME_UP_TO_SAEOP.matcher(text);
        if (m.find()) text = m.group(1);
        text = WS.matcher(text).replaceAll("");
        return text;
    }

    // ── context_key helpers ────────────────────────────────────────
    public static List<String> splitContextKey(String key) {
        List<String> parts = new ArrayList<>();
        for (String part : normalizeWs(key).split(Pattern.quote(CONTEXT_KEY_SEPARATOR), -1)) {
            if (!part.isEmpty()) parts.add(normalizeMatchToken(part));
        }
        return parts;
    }

    public static List<String> buildContextKey(Map<String, Set<String>> groups) {
        List<List<String>> keys = new ArrayList<>();
        for (String group : CONTEXT_GROUPS) {
            Set<String> vals = groups.get(group);
            if (vals != null && !vals.isEmpty()) {
                List<String> sorted = new ArrayList<>(vals);
                java.util.Collections.sort(sorted);
                keys.add(sorted);
            }
        }
        if (keys.isEmpty()) return new ArrayList<>();

        List<List<String>> combinations = new ArrayList<>();
        combinations.add(new ArrayList<>());
        for (List<String> values : keys) {
            List<List<String>> next = new ArrayList<>();
            for (List<String> base : combinations) {
                for (String value : values) {
                    List<String> extended = new ArrayList<>(base);
                    extended.add(value);
                    next.add(extended);
                }
            }
            combinations = next;
        }
        List<String> out = new ArrayList<>();
        for (List<String> combo : combinations) {
            out.add(String.join(CONTEXT_KEY_SEPARATOR, combo));
        }
        return out;
    }

    public static Map<String, Set<String>> extractContextTokens(String line) {
        Map<String, Set<String>> found = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : ANNUITY_CONTEXT_TOKENS.entrySet()) {
            String token = e.getKey();
            String group = e.getValue();
            if (line.contains(token)) {
                found.computeIfAbsent(group, k -> new LinkedHashSet<>()).add(token);
            }
        }
        return found;
    }

    private static final Pattern YEAR_RE = Pattern.compile("(\\d{1,3})년");

    public static Set<String> extractRowContextTokens(Map<String, Object> row) {
        String[] fields = new String[]{"상품명", "상품명칭", "세부종목1", "세부종목2", "세부종목3", "세부종목4"};
        StringBuilder sb = new StringBuilder();
        for (String f : fields) {
            Object v = row.get(f);
            if (v == null) continue;
            String s = v.toString();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
        String merged = normalizeWs(sb.toString());
        Set<String> tokens = new LinkedHashSet<>();
        for (String tok : ANNUITY_CONTEXT_TOKENS.keySet()) {
            if (merged.contains(tok)) tokens.add(normalizeMatchToken(tok));
        }
        Matcher m = YEAR_RE.matcher(merged);
        while (m.find()) {
            tokens.add(normalizeMatchToken(m.group(1) + "년"));
        }
        return tokens;
    }

    // ── row_text ───────────────────────────────────────────────────
    public static String rowText(Map<String, Object> row) {
        String[] keys = {"상품명칭", "상품명", "세부종목1", "세부종목2", "세부종목3", "세부종목4"};
        List<String> chunks = new ArrayList<>();
        for (String k : keys) {
            Object v = row.get(k);
            String s = normalizeWs(v == null ? "" : v.toString());
            if (!s.isEmpty()) chunks.add(s);
        }
        return String.join(" ", chunks);
    }

    // ── split_age_values ───────────────────────────────────────────
    private static final Pattern RANGE_NORMAL = Pattern.compile("(\\d{2,3})\\s*세?\\s*[~\\-]\\s*(\\d{2,3})\\s*세?");
    private static final Pattern RANGE_ISANG_IHA_MID = Pattern.compile("(\\d{2,3})\\s*세\\s*이상\\s*(?:이고\\s*)?(?:연금개시나이\\s*)?[,\\s]*(\\d{2,3})\\s*세\\s*이하");
    private static final Pattern RANGE_ISANG_IHA = Pattern.compile("(\\d{2,3})\\s*세\\s*이상\\s*[,\\s]*(\\d{2,3})\\s*세\\s*이하");
    private static final Pattern COMMA_LIST = Pattern.compile("\\d{2,3}세(?:\\s*,\\s*\\d{2,3}세)+");
    private static final Pattern ENUM_LIST = Pattern.compile("(?:\\d{2,3}세(?:\\s*(?:/|및|또는)\\s*)+)+");
    private static final Pattern AGE_IN_GROUP = Pattern.compile("(\\d{2,3})세");
    private static final Pattern SINGLE_AGE = Pattern.compile("(?:^|[^\\d~])((?:만\\s*)?(\\d{2,3})세)\\b",
            Pattern.UNICODE_CHARACTER_CLASS);

    public static List<String> splitAgeValues(String text) {
        String s = normalizeWs(text);
        s = s.replace("\u223c", "~").replace("\uff5e", "~").replace("\u301c", "~");
        s = s.replace("\u2010", "-").replace("\u2012", "-").replace("\u2013", "-");
        s = s.replace("\u2014", "-");
        List<String> raw = new ArrayList<>();

        Matcher m = RANGE_NORMAL.matcher(s);
        while (m.find()) {
            appendUnique(raw, ageRangeList(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        }
        m = RANGE_ISANG_IHA_MID.matcher(s);
        while (m.find()) {
            appendUnique(raw, ageRangeList(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        }
        m = RANGE_ISANG_IHA.matcher(s);
        while (m.find()) {
            appendUnique(raw, ageRangeList(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        }
        m = COMMA_LIST.matcher(s);
        while (m.find()) {
            Matcher nm = AGE_IN_GROUP.matcher(m.group());
            while (nm.find()) appendUnique(raw, Arrays.asList(nm.group(1)));
        }
        m = ENUM_LIST.matcher(s);
        while (m.find()) {
            Matcher nm = AGE_IN_GROUP.matcher(m.group());
            while (nm.find()) appendUnique(raw, Arrays.asList(nm.group(1)));
        }
        if (s.contains("연금개시나이") && (s.contains("~") || s.contains("-"))) {
            boolean hasBound = s.contains("이상") || s.contains("이하");
            boolean hasPayParen = s.contains("(") && s.contains(")") && s.contains("납입");
            if (hasBound || hasPayParen) return raw;
        }
        m = SINGLE_AGE.matcher(s);
        while (m.find()) {
            appendUnique(raw, Arrays.asList(m.group(2)));
        }
        return raw;
    }

    public static List<String> ageRangeList(int start, int end) {
        if (start > end) { int t = start; start = end; end = t; }
        if (end - start > 120) return new ArrayList<>(Arrays.asList(String.valueOf(start), String.valueOf(end)));
        List<String> out = new ArrayList<>();
        for (int y = start; y <= end; y++) out.add(String.valueOf(y));
        return out;
    }

    public static void appendUnique(List<String> target, List<String> values) {
        for (String v : values) {
            if (v != null && !v.isEmpty() && !target.contains(v)) target.add(v);
        }
    }

    public static void addValues(Map<String, List<String>> target, String gender, List<String> values) {
        String key = normalizeWs(gender);
        String bucket;
        if (key.equals("남자")) bucket = "남자";
        else if (key.equals("여자")) bucket = "여자";
        else bucket = "";
        List<String> list = target.computeIfAbsent(bucket, k -> new ArrayList<>());
        appendUnique(list, values);
    }

    public static void assignContextValues(Map<String, Map<String, List<String>>> categoryValues,
                                           List<String> contextKeys, String gender, List<String> values) {
        if (contextKeys == null || contextKeys.isEmpty()) return;
        for (String key : contextKeys) {
            Map<String, List<String>> bucket = categoryValues.computeIfAbsent(key, k -> new LinkedHashMap<>());
            addValues(bucket, gender, values);
        }
    }

    // ── to_age_value / age_token_to_value ──────────────────────────
    private static final Pattern LEAD_DIGITS = Pattern.compile("^(\\d{2,3})");
    private static final Pattern LEAD_DIGITS_OPT_SE = Pattern.compile("(\\d{2,3})(?:세)?");

    public static String ageTokenToValue(String token) {
        String t = normalizeWs(token);
        if (t.isEmpty()) return "";
        Matcher m = LEAD_DIGITS_OPT_SE.matcher(t);
        return m.find() && m.start() == 0 ? m.group(1) : "";
    }

    public static String toAgeValue(String token) {
        String t = normalizeWs(token);
        if (t.contains("~")) t = t.substring(0, t.indexOf('~'));
        if (t.contains(",")) t = t.substring(0, t.indexOf(','));
        Matcher m = LEAD_DIGITS.matcher(t);
        if (m.find()) return m.group(1);
        return ageTokenToValue(token);
    }

    // ── section header detection ───────────────────────────────────
    private static final Pattern HEADER_KOR_DOT = Pattern.compile("^[가-힣]\\.\\s*연금개시나이");
    private static final Pattern HEADER_PAREN_NUM = Pattern.compile("^\\(\\d+\\)\\s*연금개시나이$");

    public static boolean isAnnuitySectionHeader(String prev, String line, String next) {
        if (!line.contains("연금개시나이")) return false;
        if (HEADER_KOR_DOT.matcher(line).find() && !line.contains("가입나이")) return true;
        if (line.equals("연금개시나이") && (prev.contains("연금개시나이") || prev.contains("연금개시"))) return true;
        if (line.equals("연금개시나이") && (next.contains("구분") || next.contains("종신연금형") || next.contains("확정기간연금형"))) return true;
        if (HEADER_PAREN_NUM.matcher(line).find()) return true;
        return false;
    }

    // ── next section boundary ──────────────────────────────────────
    private static final Pattern NX_DOT = Pattern.compile("^[가-하]+\\.",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern NX_PAREN = Pattern.compile("^\\([가-하]+\\)",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern NX_NUMDOT = Pattern.compile("^\\d+\\.");
    private static final Pattern NX_CIRC = Pattern.compile("^[①-⑳]+\\)");

    public static boolean isNextSectionBoundary(String nxt) {
        if (NX_DOT.matcher(nxt).find()) return true;
        if (NX_PAREN.matcher(nxt).find()) return true;
        if (NX_NUMDOT.matcher(nxt).find()) return true;
        if (NX_CIRC.matcher(nxt).find() && nxt.length() > 2) return true;
        return false;
    }

    // ── is_annuity_row / is_life_or_escalation_row ─────────────────
    public static boolean isAnnuityRow(Map<String, Object> row) {
        String text = rowText(row);
        if (text.isEmpty()) return false;
        return text.contains("연금") || text.contains("연금저축") || text.contains("직장인연금");
    }

    public static boolean isLifeOrEscalationRow(Map<String, Object> row) {
        String text = rowText(row);
        if (text.isEmpty()) return false;
        return text.contains("종신") || text.contains("체증") || text.contains("보장형");
    }
}
