package com.hanwha.setdata.extract.period;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text helpers for insurance period extraction. Ports Python module-level
 * utilities from {@code extract_insurance_period_v2.py}.
 */
public final class IpText {

    private IpText() {}

    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern REPEATED = Pattern.compile("(.)\\1{3,}");
    private static final Pattern EXT = Pattern.compile("\\.(pdf|docx|json)$");
    private static final Pattern UP_TO_SAEOP = Pattern.compile("^(.+사업방법서)");

    /** Python {@code normalize_ws}: NFC → strip zero-width → collapse 4+ repeats → whitespace. */
    public static String normalizeWs(String value) {
        if (value == null) return "";
        String s = Normalizer.normalize(value, Normalizer.Form.NFC);
        s = s.replace("\u200b", "");
        s = REPEATED.matcher(s).replaceAll("$1");
        return WS.matcher(s).replaceAll(" ").trim();
    }

    /** Python {@code normalize_name}. */
    public static String normalizeName(String value) {
        String text = normalizeWs(value);
        text = EXT.matcher(text).replaceAll("");
        Matcher m = UP_TO_SAEOP.matcher(text);
        if (m.find()) text = m.group(1);
        return WS.matcher(text).replaceAll("");
    }

    /** Strip whitespace. */
    public static String stripAllWs(String s) {
        if (s == null) return "";
        return WS.matcher(s).replaceAll("");
    }

    public record Period(String code, String kind, String value) {
        public static final Period EMPTY = new Period("", "", "");
    }

    private static final Pattern SE_PAT = Pattern.compile("(\\d+)\\s*세");
    private static final Pattern YEAR_PAT = Pattern.compile("(\\d+)\\s*년");
    private static final Pattern NUM_ONLY = Pattern.compile("^(\\d+)$");

    /** Python {@code format_period}. */
    public static Period formatPeriod(String text) {
        String t = normalizeWs(text);
        if (t.isEmpty()) return Period.EMPTY;
        if (t.contains("종신")) return new Period("A999", "A", "999");
        if (t.contains("일시납")) return new Period("N0", "N", "0");
        Matcher m = SE_PAT.matcher(t);
        if (m.find()) return new Period("X" + m.group(1), "X", m.group(1));
        m = YEAR_PAT.matcher(t);
        if (m.find()) return new Period("N" + m.group(1), "N", m.group(1));
        m = NUM_ONLY.matcher(t.trim());
        if (m.matches()) return new Period("N" + m.group(1), "N", m.group(1));
        return Period.EMPTY;
    }
}
