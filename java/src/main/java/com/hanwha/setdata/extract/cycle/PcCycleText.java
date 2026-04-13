package com.hanwha.setdata.extract.cycle;

import com.hanwha.setdata.util.Normalizer;

import java.text.Normalizer.Form;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text helpers ported from {@code extract_payment_cycle_v2.py}:
 * cycle-name extraction, context-token extraction, parenthesized-section
 * scanning, normalization, etc.
 */
public final class PcCycleText {

    private PcCycleText() {}

    public static final List<String> CYCLE_ORDER = List.of("0", "1", "3", "6", "12");

    /** Python: normalize_match_key — NFKC + remove all whitespace. */
    public static String normalizeMatchKey(String value) {
        if (value == null) return "";
        String nfkc = java.text.Normalizer.normalize(value, Form.NFKC);
        return Normalizer.stripAllWs(nfkc);
    }

    /** Python: normalize_cycle_name. */
    public static String normalizeCycleName(String token) {
        return switch (token) {
            case "일시납" -> "일시납";
            case "수시납", "월납" -> "월납";
            case "3개월납", "분기납" -> "3개월납";
            case "6개월납", "반기납" -> "6개월납";
            case "연납", "년납" -> "연납";
            default -> null;
        };
    }

    /** Python: cycle_value. */
    public static String cycleValue(String name) {
        return switch (name) {
            case "일시납" -> "0";
            case "월납" -> "1";
            case "3개월납" -> "3";
            case "6개월납" -> "6";
            case "연납" -> "12";
            default -> "";
        };
    }

    private static final Pattern CYCLE_TOKEN_RE = Pattern.compile(
            "일시납|수시납|월납|3개월납|분기납|6개월납|반기납|연납|년납|일회납");

    /** Python: extract_cycle_names. */
    public static List<String> extractCycleNames(String text) {
        if (text == null || text.isEmpty()) return new ArrayList<>();
        List<String> names = new ArrayList<>();
        Matcher m = CYCLE_TOKEN_RE.matcher(text);
        while (m.find()) {
            String token = m.group();
            // "10년납" 같은 기간 표기는 제외
            if ("년납".equals(token) && m.start() > 0) {
                char prev = text.charAt(m.start() - 1);
                if (Character.isDigit(prev)) continue;
            }
            String name = normalizeCycleName(token);
            if (name == null) continue;
            if (names.isEmpty() || !names.get(names.size() - 1).equals(name)) {
                names.add(name);
            }
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        // 중복 제거 (insertion order preserved)
        List<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String n : names) {
            if (seen.add(n)) out.add(n);
        }
        return out;
    }

    /** Python: cycle_records_from_names. */
    public static List<Map<String, String>> cycleRecordsFromNames(List<String> names) {
        List<Map<String, String>> items = new ArrayList<>();
        for (String raw : names) {
            String name = raw;
            if ("년납".equals(name)) name = "연납";
            String val = cycleValue(name);
            if (val.isEmpty()) continue;
            LinkedHashMap<String, String> m = new LinkedHashMap<>();
            String label = switch (val) {
                case "0" -> "일시납";
                case "1" -> "월납";
                case "3" -> "3월납";
                case "6" -> "6월납";
                default -> "년납";
            };
            m.put("납입주기명", label);
            m.put("납입주기값", val);
            m.put("납입주기구분코드", "M");
            items.add(m);
        }
        return items;
    }

    private static final Pattern CATEGORY_PAREN_RE = Pattern.compile("\\d+종\\([^)]*\\)");
    private static final Pattern DIGIT_YEAR_OR_AGE_RE = Pattern.compile("\\d+년|\\d+세");
    private static final List<String> KEYWORD_PATTERNS = List.of(
            "보장형 계약",
            "적립형 계약",
            "스마트전환형 계약[보증비용부과형]",
            "스마트전환형 계약",
            "해약환급금 미보증",
            "해약환급금 보증",
            "일부지급형",
            "만기환급형",
            "해약환급금 일부지급형",
            "해약환급금 미지급형",
            "해약환급금 보증",
            "스마트전환형",
            "적립형",
            "거치형"
    );

    /** Python: extract_context_tokens. */
    public static List<String> extractContextTokens(String text, List<String> detailTokens) {
        String txt = Normalizer.normalizeWs(text);
        List<String> out = new ArrayList<>();

        Matcher m1 = CATEGORY_PAREN_RE.matcher(txt);
        while (m1.find()) out.add(m1.group());

        for (String k : KEYWORD_PATTERNS) {
            if (txt.contains(k)) out.add(k);
        }

        Matcher m2 = CATEGORY_PAREN_RE.matcher(txt);
        while (m2.find()) {
            String tok = m2.group();
            if (!out.contains(tok)) out.add(tok);
        }

        if (detailTokens != null) {
            String normalizedText = normalizeMatchKey(txt);
            for (String token : detailTokens) {
                if (DIGIT_YEAR_OR_AGE_RE.matcher(token).matches()) continue;
                String normalizedToken = normalizeMatchKey(token);
                if (!normalizedToken.isEmpty()
                        && normalizedText.contains(normalizedToken)
                        && !out.contains(token)) {
                    out.add(token);
                }
            }
        }

        List<String> uniq = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String token : out) {
            String norm = normalizeMatchKey(token);
            if (seen.add(norm)) uniq.add(token);
        }
        return uniq;
    }

    /** Python: extract_parenthesized_sections — returns top-level parenthesized
     *  substrings (ignoring nested parens). */
    public static List<String> extractParenthesizedSections(String text) {
        List<String> sections = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '(') {
                depth++;
                if (depth == 1) start = i + 1;
            } else if (ch == ')' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    sections.add(text.substring(start, i));
                    start = -1;
                }
            }
        }
        return sections;
    }

    /** Python: normalize_context_union. */
    public static List<String> normalizeContextUnion(List<String> left, List<String> right) {
        List<String> merged = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (List<String> src : List.of(left, right)) {
            for (String token : src) {
                String norm = normalizeMatchKey(token);
                if (seen.add(norm)) merged.add(token);
            }
        }
        return merged;
    }
}
