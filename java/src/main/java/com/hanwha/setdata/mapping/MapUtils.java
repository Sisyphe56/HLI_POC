package com.hanwha.setdata.mapping;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared text helpers ported from {@code map_product_code.py}.
 *
 * <p>Python equivalents:
 * <ul>
 *   <li>{@link #normalizeText(Object)} ↔ {@code normalize_text}</li>
 *   <li>{@link #normalizeMatchKey(String)} ↔ {@code normalize_match_key}</li>
 *   <li>{@link #splitMatchTokens(String)} ↔ {@code split_match_tokens}</li>
 *   <li>{@link #extractRefundLevelToken(List)} ↔ {@code extract_refund_level_token}</li>
 *   <li>{@link #collectDetailKeys(Map)} ↔ {@code collect_detail_keys}</li>
 *   <li>{@link #collectComponents(Map)} ↔ {@code collect_components}</li>
 *   <li>{@link #allTokensInKey(Iterable, String)} ↔ {@code _all_tokens_in_key}</li>
 * </ul>
 */
public final class MapUtils {

    private static final Pattern LEADING_BULLET =
            Pattern.compile("^[\\s·•▪∙◦]+\\s*");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern SPLIT_SEP = Pattern.compile("[\\s\\[\\]()·]+");
    private static final Pattern DETAIL_KEY =
            Pattern.compile("세부종목(\\d+)");
    private static final Pattern NUM_PREFIX = Pattern.compile("^\\d+");
    private static final Pattern ROMAN_I = Pattern.compile("I+");
    private static final Pattern PRE_REFUND = Pattern.compile("^.*일부지급형");

    private MapUtils() {}

    public static String readTextWithFallback(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        Charset[] encodings = {
                StandardCharsets.UTF_8,       // utf-8-sig via BOM stripping
                StandardCharsets.UTF_8,
                Charset.forName("CP949"),
                Charset.forName("EUC-KR"),
        };
        Exception last = null;
        for (int i = 0; i < encodings.length; i++) {
            try {
                String s = decodeStrict(bytes, encodings[i]);
                if (i == 0 && !s.isEmpty() && s.charAt(0) == '\uFEFF') {
                    s = s.substring(1);
                }
                return s;
            } catch (CharacterCodingException e) {
                last = e;
            }
        }
        throw new IOException("Cannot decode " + path, last);
    }

    private static String decodeStrict(byte[] bytes, Charset cs) throws CharacterCodingException {
        return cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    public static String normalizeText(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value).trim();
        s = Normalizer.normalize(s, Normalizer.Form.NFKC);
        s = s.replace("\u2160", "I").replace("\u2161", "II").replace("\u2162", "III");
        s = s.replace('\u00a0', ' ');
        s = LEADING_BULLET.matcher(s).replaceAll("");
        s = WHITESPACE.matcher(s).replaceAll(" ");
        return s;
    }

    public static String normalizeMatchKey(String value) {
        return WHITESPACE.matcher(normalizeText(value)).replaceAll("");
    }

    public static List<String> splitMatchTokens(String value) {
        String compact = normalizeText(value);
        if (compact.isEmpty()) return new ArrayList<>();
        String[] raw = SPLIT_SEP.split(compact);
        List<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String token : raw) {
            String tok = normalizeMatchKey(token);
            if (!tok.isEmpty() && seen.add(tok)) {
                out.add(tok);
            }
        }
        return out;
    }

    public static String extractRefundLevelToken(List<String> tokens) {
        for (String token : tokens) {
            if (!token.contains("일부지급형")) continue;
            String suffix = token.substring(token.indexOf("일부지급형") + "일부지급형".length());
            suffix = stripChars(suffix, " ()[]");
            Matcher m = ROMAN_I.matcher(suffix);
            if (m.matches()) {
                return "일부지급형" + m.group(0);
            }
            String suffix2 = PRE_REFUND.matcher(token).replaceFirst("");
            suffix2 = stripChars(suffix2, " ()[]");
            Matcher m2 = ROMAN_I.matcher(suffix2);
            if (m2.matches()) {
                return "일부지급형" + m2.group(0);
            }
        }
        return null;
    }

    /** Python {@code str.strip(chars)} — trims any char in {@code chars}. */
    public static String stripChars(String s, String chars) {
        if (s == null) return "";
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) start++;
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(start, end);
    }

    public static List<String> collectDetailKeys(Map<String, Object> record) {
        TreeMap<Integer, String> byNum = new TreeMap<>();
        for (String k : record.keySet()) {
            Matcher m = DETAIL_KEY.matcher(String.valueOf(k));
            if (m.matches()) {
                byNum.put(Integer.parseInt(m.group(1)), k);
            }
        }
        return new ArrayList<>(byNum.values());
    }

    public static List<String> collectComponents(Map<String, Object> record) {
        String productName = normalizeText(record.get("상품명칭"));
        if (productName.isEmpty() && record.get("상품명") != null) {
            productName = normalizeText(record.get("상품명"));
        }
        List<String> detailKeys = collectDetailKeys(record);
        List<String> detailParts = new ArrayList<>();
        for (String k : detailKeys) {
            detailParts.add(normalizeText(record.get(k)));
        }
        if (productName.isEmpty()) return detailParts;
        List<String> parts = new ArrayList<>();
        parts.add(productName);
        for (String p : detailParts) {
            if (!p.isEmpty()) parts.add(p);
        }
        return parts;
    }

    /**
     * Checks every token in {@code tokens} is a substring of {@code key} with
     * digit-boundary awareness for number-prefixed tokens (e.g. {@code "5년"}
     * should not match inside {@code "15년형"}).
     */
    public static boolean allTokensInKey(Iterable<String> tokens, String key) {
        for (String token : tokens) {
            if (!key.contains(token)) return false;
            if (NUM_PREFIX.matcher(token).lookingAt()) {
                int idx = 0;
                boolean found = false;
                while (true) {
                    int pos = key.indexOf(token, idx);
                    if (pos < 0) break;
                    if (pos > 0 && Character.isDigit(key.charAt(pos - 1))) {
                        idx = pos + 1;
                        continue;
                    }
                    found = true;
                    break;
                }
                if (!found) return false;
            }
        }
        return true;
    }

    public static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
