package com.hanwha.setdata.extract;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-processing helpers ported from
 * {@code extract_product_classification_v2.py} (lines 27–76, 318–342, 400–478).
 *
 * <p>Each public static method maps 1:1 to a Python top-level function so
 * the rest of the port reads like the original.
 */
public final class PcText {

    private PcText() {}

    // ── normalize_ws ───────────────────────────────────────────────
    private static final Pattern WS = Pattern.compile("\\s+");

    public static String normalizeWs(String value) {
        String s = value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFC);
        return WS.matcher(s).replaceAll(" ").trim();
    }

    // ── clean_item ─────────────────────────────────────────────────
    private static final Pattern CLEAN_SPACE_LBRACKET = Pattern.compile("\\s+\\[");
    private static final Pattern CLEAN_RBRACKET_SPACE = Pattern.compile("\\]\\s+");
    private static final Pattern CLEAN_LEAD_COLON = Pattern.compile("^[:：]\\s*");
    private static final Pattern CLEAN_LEAD_BULLETS = Pattern.compile("^[·•▪∙◦\\s]+\\s*");
    private static final Pattern CLEAN_LEAD_ROLE = Pattern.compile("^(주계약|종속특약)\\s*[∙·:]\\s*");
    private static final Pattern CLEAN_LEAD_DASH = Pattern.compile("^[\\-\\*]+\\s*");

    public static String cleanItem(String value) {
        String s = normalizeWs(value);
        s = s.replace('·', ' ').replace('•', ' ').replace('▪', ' ');
        s = s.replace('∙', ' ').replace('◦', ' ');
        s = CLEAN_SPACE_LBRACKET.matcher(s).replaceAll("[");
        s = CLEAN_RBRACKET_SPACE.matcher(s).replaceAll("] ");
        s = CLEAN_LEAD_COLON.matcher(s).replaceAll("");
        s = CLEAN_LEAD_BULLETS.matcher(s).replaceAll("");
        s = CLEAN_LEAD_ROLE.matcher(s).replaceAll("");
        s = CLEAN_LEAD_DASH.matcher(s).replaceAll("");
        // Python: s.strip(' ,;')
        int start = 0, end = s.length();
        while (start < end && " ,;".indexOf(s.charAt(start)) >= 0) start++;
        while (end > start && " ,;".indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(start, end);
    }

    // ── strip_role_prefix ──────────────────────────────────────────
    private static final Pattern STRIP_ROLE = Pattern.compile(
            "^(주\\s*계\\s*약|종속\\s*특약|주계약|종속특약)\\s*[·•:\\-]?\\s*");

    public static String stripRolePrefix(String value) {
        String t = STRIP_ROLE.matcher(cleanItem(value)).replaceAll("");
        return WS.matcher(t).replaceAll(" ").trim();
    }

    // ── normalize_special_terms ────────────────────────────────────
    private static final Pattern GROUP_BRACKET =
            Pattern.compile("\\[\\s*계약전환\\s*·?\\s*단체개인전환\\s*·?\\s*개인중지재개용\\s*\\]");
    private static final Pattern GROUP_PAREN =
            Pattern.compile("\\(\\s*계약전환\\s*·?\\s*단체개인전환\\s*·?\\s*개인중지재개용\\s*\\)");
    private static final Pattern GROUP_BARE =
            Pattern.compile("계약전환\\s*·?\\s*단체개인전환\\s*·?\\s*개인중지재개용");
    private static final Pattern SP_RPAREN = Pattern.compile("\\s+\\)");
    private static final Pattern SP_LPAREN = Pattern.compile("\\s+\\(");
    private static final Pattern DOT_OR_SPACE_GROUP =
            Pattern.compile("계약전환[·\\s]*단체개인전환[·\\s]*개인중지재개용");

    public static String normalizeSpecialTerms(String value, String filename) {
        String s = cleanItem(value);
        if (filename != null && filename.contains("스마트H")) {
            s = s.replace("스마트V", "스마트H");
        }
        if (s.contains("계약전환") && s.contains("단체개인전환") && s.contains("개인중지재개용")) {
            s = GROUP_BRACKET.matcher(s).replaceAll(" ");
            s = GROUP_PAREN.matcher(s).replaceAll(" ");
            s = GROUP_BARE.matcher(s).replaceAll(" ");
        }
        s = SP_RPAREN.matcher(s).replaceAll(")");
        s = SP_LPAREN.matcher(s).replaceAll("(");
        if (s.contains("계약전환·단체개인전환·개인중지재개용")
                && filename != null
                && s.contains("한화생명 기본형 급여 실손의료비보장보험")
                && !s.contains("e")) {
            s = s.replace("한화생명 기본형 급여 ", "한화생명  기본형  급여  ");
        }
        s = DOT_OR_SPACE_GROUP.matcher(s).replaceAll("계약전환·단체개인전환·개인중지재개용");
        return s;
    }

    // ── split_outside_parentheses ──────────────────────────────────
    public static List<String> splitOutsideParentheses(String text, char sep) {
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int pDepth = 0, bDepth = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '(') pDepth++;
            else if (ch == ')' && pDepth > 0) pDepth--;
            else if (ch == '[') bDepth++;
            else if (ch == ']' && bDepth > 0) bDepth--;
            if (ch == sep && pDepth == 0 && bDepth == 0) {
                chunks.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(ch);
        }
        if (cur.length() > 0) chunks.add(cur.toString());
        return chunks;
    }

    public static List<String> splitOutsideParentheses(String text) {
        return splitOutsideParentheses(text, ',');
    }

    // ── looks_like_noise ───────────────────────────────────────────
    private static final String[] NOISE = {
            "사업방법서", "보험기간", "보험료", "피보험자", "가입최고나이", "가입최저나이",
            "보험종목의 구성", "보험종목에 관한 사항", "세부보험종목", "구 분", "판매하지 않",
            "비교용", "이라 한다", "계약자 확인", "가입이 불가능", "이하", "의 경우",
            "적용하며", "적용한다", "으로 함", "계약 체결시", "(주)", "주계약", "종속특약",
            "최초계약",
    };

    public static boolean looksLikeNoise(String token) {
        for (String b : NOISE) if (token.contains(b)) return true;
        return false;
    }

    // ── is_detail_token ────────────────────────────────────────────
    private static final Pattern NUMBER_PAREN_BOM = Pattern.compile("^\\d+\\)\\s*.*(보험|특약)");
    private static final Pattern DIGIT_YEAR = Pattern.compile("^\\d+년$");
    private static final Pattern DIGIT_SE_HYUNG = Pattern.compile("^\\d+세형$");
    private static final Pattern DIGIT_SE_ANY = Pattern.compile("\\d+세");
    private static final Pattern DIGIT_YEAR_HYUNG = Pattern.compile("^\\d+년형$");
    private static final Pattern DIGIT_JONG_PAREN_START = Pattern.compile("^\\d+종\\(");
    private static final Pattern DIGIT_JONG = Pattern.compile("^\\d+종$");
    private static final Pattern DIGIT_HYUNG_PAREN = Pattern.compile("^\\d+형\\([^)]*\\)$");
    private static final Pattern DIGIT_HYUNG = Pattern.compile("^\\d+형$");
    private static final List<String> DETAIL_KEYWORDS = Arrays.asList(
            "가입형", "고지형", "계약", "해약환급금", "표준형", "거치형", "적립형", "연금형",
            "연금플러스형", "입원형", "통원형", "개인형", "부부형", "환급플랜", "종신플랜",
            "일반형", "초기집중형", "상해", "질병", "비급여", "급여", "보장형",
            "계약전환용", "단체개인전환용", "개인중지재개용"
    );

    public static boolean isDetailToken(String token) {
        String t = cleanItem(token);
        if (t.isEmpty()) return false;
        if (looksLikeNoise(t)) return false;
        if (NUMBER_PAREN_BOM.matcher(t).find()) return false;
        if (t.startsWith("(")) return false;
        if (t.equals("남") || t.equals("여") || t.equals("남자") || t.equals("여자")) return false;
        if (DIGIT_YEAR.matcher(t).matches()) return false;
        for (String k : DETAIL_KEYWORDS) if (t.contains(k)) return true;
        if (t.startsWith("만기환급형")) return true;
        if (t.contains("종신갱신형")) return true;
        if (DIGIT_SE_HYUNG.matcher(t).matches()) return true;
        if (DIGIT_SE_ANY.matcher(t).find() && t.contains("형")) return true;
        if (DIGIT_YEAR_HYUNG.matcher(t).matches()) return true;
        if (DIGIT_JONG_PAREN_START.matcher(t).find()) return true;
        if (t.endsWith("체형")) return true;
        if (DIGIT_JONG.matcher(t).matches()) return true;
        if (DIGIT_HYUNG_PAREN.matcher(t).matches()) return true;
        if (DIGIT_HYUNG.matcher(t).matches()) return true;
        return false;
    }

    // ── split_detail_candidates ────────────────────────────────────
    private static final Pattern BREAK_BEFORE_JONG = Pattern.compile("(?<=\\))\\s+(?=\\d+종\\()");
    private static final Pattern FIX_BOJANG_GEONG1 =
            Pattern.compile("\\b적립형계약\\b", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern FIX_BOJANG_GEONG2 =
            Pattern.compile("\\b보장형계약\\b", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern TRAIL_DASH = Pattern.compile("(적립형|보장형)\\s*계약\\s*[-—–]\\s*$");
    private static final Pattern JUST_CONTRACT = Pattern.compile("^(적립형|보장형)\\s*계약$");

    public static List<String> splitDetailCandidates(String text) {
        String buf = (text == null ? "" : text).replace("\\n", "\n");
        buf = BREAK_BEFORE_JONG.matcher(buf).replaceAll("\n");
        buf = buf.replace("),", ")\n").replace("), ", ")\n");
        List<String> lines = new ArrayList<>();
        for (String chunk : buf.split("\n", -1)) {
            for (String part : splitOutsideParentheses(chunk)) {
                String item = cleanItem(part);
                item = FIX_BOJANG_GEONG1.matcher(item).replaceAll("적립형 계약");
                item = FIX_BOJANG_GEONG2.matcher(item).replaceAll("보장형 계약");
                Matcher m = TRAIL_DASH.matcher(item);
                if (m.find()) {
                    item = m.replaceAll("$1 계약");
                }
                if (JUST_CONTRACT.matcher(item).matches()) {
                    lines.add(item);
                    continue;
                }
                if (!isDetailToken(item)) {
                    if (item.contains(" ")) {
                        String[] split = item.split("\\s+", 2);
                        if (split.length == 2 && isDetailToken(split[0]) && isDetailToken(split[1])) {
                            lines.add(split[0]);
                            lines.add(split[1]);
                            continue;
                        }
                    }
                    continue;
                }
                lines.add(item);
            }
        }
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String x : lines) if (seen.add(x)) out.add(x);
        return out;
    }

    // ── is_refund_token / can_pair_with_pending_refund ─────────────
    public static boolean isRefundToken(String token) {
        return token != null && token.startsWith("해약환급금");
    }

    public static boolean canPairWithPendingRefund(String token, List<String> productNames) {
        if (token == null || token.isEmpty() || productNames == null) return false;
        boolean hasECancer = productNames.stream().anyMatch(n -> n.contains("한화생명 e암보험(비갱신형)"));
        boolean hasETerm = productNames.stream().anyMatch(n -> n.contains("한화생명 e정기보험"));
        if (hasECancer) return token.endsWith("체형");
        if (hasETerm) return token.contains("보장형");
        return false;
    }

    // ── cell_has_value / last_non_empty ────────────────────────────
    public static boolean cellHasValue(String value) {
        if (value == null || value.isEmpty()) return false;
        return !value.equals("-") && !value.equals("―") && !value.equals("—");
    }

    public static int lastNonEmpty(List<String> cells) {
        for (int i = cells.size() - 1; i >= 0; i--) {
            if (cellHasValue(cells.get(i))) return i;
        }
        return -1;
    }
}
