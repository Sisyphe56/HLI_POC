package com.hanwha.setdata.extract.joinage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of {@code extract_text_gender_ages} — text-based age range extraction. */
public final class JaTextExtract {

    private JaTextExtract() {}

    private static final int UF = Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern CTX_M = Pattern.compile(
            "^\\s*[\\(\\（]([가-힣\\d]+)\\s*[\\)\\）]\\s*(.+)", UF);
    private static final Pattern SUB_LABEL = Pattern.compile(
            "^[\\(\\（][가-힣\\d]+[\\)\\）]\\s", UF);
    private static final Pattern SUB_HEAD = Pattern.compile(
            "^[가-힣\\d][\\.\\)]\\s", UF);
    private static final Pattern AGE_RANGE = Pattern.compile(
            "(\\d+)\\s*[~\\-]\\s*(\\d+)\\s*세", UF);
    private static final Pattern INS_RE = Pattern.compile("(\\d+)\\s*세만기|종신", UF);
    private static final Pattern PAYM_RE = Pattern.compile(
            "전기납|일시납|(\\d+)\\s*년납|(\\d+)\\s*세납|종신납", UF);

    public static List<Map<String, Object>> extract(List<String> lines) {
        int[] sec = JaText.findAgeSection(lines);
        List<Map<String, Object>> results = new ArrayList<>();
        String prevContext = "";
        for (int i = sec[0]; i < sec[1]; i++) {
            String line = lines.get(i);
            Matcher ctxM = CTX_M.matcher(line);
            if (ctxM.lookingAt()) prevContext = ctxM.group(2).trim();
            if (line.contains("보험기간") && line.contains("납입기간")
                    && (line.contains("남자") || line.contains("여자"))) {
                String curContext = prevContext;
                int endJ = Math.min(i + 20, sec[1]);
                for (int j = i + 1; j < endJ; j++) {
                    String dataLine = lines.get(j);
                    if (SUB_LABEL.matcher(dataLine).lookingAt()) break;
                    if (SUB_HEAD.matcher(dataLine).lookingAt()) break;

                    List<int[]> ranges = new ArrayList<>();
                    Matcher m = AGE_RANGE.matcher(dataLine);
                    List<String[]> matches = new ArrayList<>();
                    while (m.find()) matches.add(new String[]{m.group(1), m.group(2)});
                    if (matches.size() >= 2) {
                        Matcher im = INS_RE.matcher(dataLine);
                        Matcher pm = PAYM_RE.matcher(dataLine);
                        String insText = im.find() ? im.group(0) : "";
                        String paymText = pm.find() ? pm.group(0) : "";
                        Map<String, Object> m1 = new LinkedHashMap<>();
                        m1.put("context", curContext);
                        m1.put("보험기간", insText);
                        m1.put("납입기간", paymText);
                        m1.put("성별", "1");
                        m1.put("최소가입나이", matches.get(0)[0]);
                        m1.put("최대가입나이", matches.get(0)[1]);
                        results.add(m1);
                        Map<String, Object> m2 = new LinkedHashMap<>();
                        m2.put("context", curContext);
                        m2.put("보험기간", insText);
                        m2.put("납입기간", paymText);
                        m2.put("성별", "2");
                        m2.put("최소가입나이", matches.get(1)[0]);
                        m2.put("최대가입나이", matches.get(1)[1]);
                        results.add(m2);
                    } else if (matches.size() == 1) {
                        Matcher im = INS_RE.matcher(dataLine);
                        Matcher pm = PAYM_RE.matcher(dataLine);
                        String insText = im.find() ? im.group(0) : "";
                        String paymText = pm.find() ? pm.group(0) : "";
                        Map<String, Object> m1 = new LinkedHashMap<>();
                        m1.put("context", curContext);
                        m1.put("보험기간", insText);
                        m1.put("납입기간", paymText);
                        m1.put("성별", "");
                        m1.put("최소가입나이", matches.get(0)[0]);
                        m1.put("최대가입나이", matches.get(0)[1]);
                        results.add(m1);
                    }
                }
            }
        }
        return results;
    }
}
