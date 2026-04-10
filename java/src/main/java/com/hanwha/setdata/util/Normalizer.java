package com.hanwha.setdata.util;

import java.text.Normalizer.Form;
import java.util.regex.Pattern;

/**
 * String normalization utilities ported from Python modules.
 *
 * <p>Python equivalents:
 * <ul>
 *   <li>{@link #normalizeWs(String)} ↔ {@code normalize_ws(s)} — collapse whitespace, NBSP→space, strip</li>
 *   <li>{@link #stripAllWs(String)} ↔ {@code re.sub(r'\s+', '', s)} — remove all whitespace</li>
 *   <li>{@link #nfc(String)} ↔ {@code unicodedata.normalize('NFC', s)}</li>
 * </ul>
 */
public final class Normalizer {

    private static final Pattern WS = Pattern.compile("\\s+");

    private Normalizer() {}

    /** Collapse any whitespace (including NBSP) to single space and trim. */
    public static String normalizeWs(String s) {
        if (s == null) return "";
        // NBSP (\u00A0) and similar -> regular space
        String replaced = s.replace('\u00A0', ' ').replace('\u3000', ' ');
        return WS.matcher(replaced).replaceAll(" ").trim();
    }

    /** Remove all whitespace characters. */
    public static String stripAllWs(String s) {
        if (s == null) return "";
        return WS.matcher(s).replaceAll("");
    }

    /** Unicode NFC normalization (macOS HFS+ file paths may arrive as NFD). */
    public static String nfc(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s, Form.NFC);
    }
}
