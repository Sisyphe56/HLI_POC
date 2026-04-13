package com.hanwha.setdata.compare;

import java.text.Normalizer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Port of text/code normalizers in {@code compare_product_data.py}.
 *
 * <p>These are intentionally local to the compare package rather than reusing
 * {@link com.hanwha.setdata.util.Normalizer#normalizeWs(String)} because the
 * Python comparator applies an extra NFKC pass, Roman-numeral substitutions
 * and a leading-bullet strip that the extractor-side util does not.
 */
public final class CompareNormalizers {

    private static final Pattern LEADING_BULLET = Pattern.compile("^[\\s·•▪∙◦]+\\s*");
    private static final Pattern WS = Pattern.compile("\\s+");

    private CompareNormalizers() {}

    /** Python {@code normalize_text}. */
    public static String normalizeText(Object value) {
        if (value == null) return "";
        String raw;
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d)) return "";
            // Python str(float) — but comparator always wraps str(value) before, so
            // we replicate str(number) via toString which is fine for ints; floats
            // won't appear in our CSV/JSON inputs.
            raw = value.toString();
        } else {
            raw = value.toString();
        }
        if (raw.isEmpty()) return "";
        String s = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC);
        s = s.replace("Ⅰ", "I").replace("Ⅱ", "II").replace("Ⅲ", "III");
        s = s.replace('\u00a0', ' ');
        s = LEADING_BULLET.matcher(s).replaceAll("");
        s = WS.matcher(s).replaceAll(" ");
        return s;
    }

    /** Python {@code normalize_code}: strip leading zeros; empty → "0". */
    public static String normalizeCode(Object value) {
        String s = normalizeText(value);
        int i = 0;
        while (i < s.length() && s.charAt(i) == '0') i++;
        s = s.substring(i);
        if (s.isEmpty()) s = "0";
        return s;
    }

    /** Python {@code _normalize_age_code}: '00' → '0'; empty → '0'. */
    public static String normalizeAgeCode(Object value) {
        String s = normalizeText(value);
        if ("00".equals(s)) return "0";
        if (s.isEmpty()) return "0";
        return s;
    }

    /** Python {@code _normalize_gender}: '1'/'남자' → '남자', '2'/'여자' → '여자', else ''. */
    public static String normalizeGender(Object value) {
        String s = normalizeText(value);
        if ("1".equals(s) || "남자".equals(s)) return "남자";
        if ("2".equals(s) || "여자".equals(s)) return "여자";
        return "";
    }

    /** Python {@code _normalize_gender_code}. */
    public static String normalizeGenderCode(Object value) {
        String s = normalizeText(value);
        if ("1".equals(s)) return "1";
        if ("2".equals(s)) return "2";
        return "";
    }

    /** Python {@code _norm_age_val}. */
    public static String normAgeVal(Object value) {
        String s = normalizeText(value);
        if (s.isEmpty() || "None".equals(s) || "nan".equals(s)) return "";
        return s;
    }

    /** Python NORMALIZERS registry lookup by name. */
    public static Function<Object, String> byName(String name) {
        if (name == null) return CompareNormalizers::normalizeText;
        return switch (name) {
            case "code" -> CompareNormalizers::normalizeCode;
            case "age_code" -> CompareNormalizers::normalizeAgeCode;
            case "gender" -> CompareNormalizers::normalizeGender;
            case "gender_code" -> CompareNormalizers::normalizeGenderCode;
            case "age_val" -> CompareNormalizers::normAgeVal;
            default -> CompareNormalizers::normalizeText;
        };
    }
}
