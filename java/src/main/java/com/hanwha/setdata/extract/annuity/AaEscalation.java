package com.hanwha.setdata.extract.annuity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of Python {@code extract_escalation_pairs} and {@code pick_escalation_for_row}. */
public final class AaEscalation {

    private AaEscalation() {}

    public static final class Pair {
        public final String start;
        public final String end;
        public final boolean absolute;
        public final String context;
        public Pair(String s, String e, boolean a, String c) {
            this.start = s; this.end = e; this.absolute = a; this.context = c;
        }
    }

    private static final Pattern START_RE = Pattern.compile("(\\d{1,3})\\s*년\\s*경과");
    private static final Pattern END_MAX1 = Pattern.compile("(\\d{1,3})\\s*년을\\s*최대로");
    private static final Pattern END_MAX2 = Pattern.compile("최대로.*?(\\d{1,3})\\s*년");
    private static final Pattern ABS_RE = Pattern.compile(
            "(\\d{1,3})\\s*년\\s*경과시점\\s*계약해당일\\s*부터\\s*(\\d{1,3})\\s*년\\s*경과시점");
    private static final Pattern TYPE_PAREN = Pattern.compile("\\([가-힣]\\)\\s*(\\d종)",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern TYPE_DIRECT = Pattern.compile("^\\s*(\\d종)\\s*\\(");

    public static List<Pair> extract(List<String> lines) {
        List<Pair> results = new ArrayList<>();

        // Pattern 1: 체증경과년수 (duration)
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.contains("체증경과년수")) continue;

            String start = "";
            String end = "";
            int ctxStart = Math.max(0, i - 3);
            int ctxEnd = Math.min(lines.size(), i + 25);
            StringBuilder ctxSb = new StringBuilder();
            for (int k = ctxStart; k < ctxEnd; k++) {
                if (ctxSb.length() > 0) ctxSb.append(' ');
                ctxSb.append(lines.get(k));
            }
            String context = ctxSb.toString();

            for (int j = Math.max(0, i - 6); j < Math.min(lines.size(), i + 6); j++) {
                Matcher m = START_RE.matcher(lines.get(j));
                if (m.find()) { start = m.group(1); break; }
            }
            if (start.isEmpty()) {
                Matcher m = START_RE.matcher(context);
                if (m.find()) start = m.group(1);
            }

            for (int j = i; j < Math.min(lines.size(), i + 20); j++) {
                String cand = lines.get(j);
                Matcher m = END_MAX1.matcher(cand);
                if (m.find()) { end = m.group(1); break; }
                m = END_MAX2.matcher(cand);
                if (m.find()) { end = m.group(1); break; }
            }
            if (end.isEmpty()) {
                Matcher m = END_MAX1.matcher(context);
                if (m.find()) {
                    end = m.group(1);
                } else {
                    m = END_MAX2.matcher(context);
                    if (m.find()) end = m.group(1);
                }
            }

            if (start.isEmpty() && end.isEmpty()) continue;
            results.add(new Pair(start, end, false, context));
        }

        // Pattern 2: 기준사망보험금 (absolute)
        Set<String> seenAbs = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.contains("기준사망보험금")) continue;
            int blockEnd = Math.min(lines.size(), i + 60);
            List<int[]> typeSections = new ArrayList<>();
            List<String> typeLabels = new ArrayList<>();
            for (int j = i; j < blockEnd; j++) {
                Matcher mt = TYPE_PAREN.matcher(lines.get(j));
                if (mt.find()) {
                    typeSections.add(new int[]{j});
                    typeLabels.add(mt.group(1));
                    continue;
                }
                Matcher mt2 = TYPE_DIRECT.matcher(lines.get(j));
                if (mt2.find()) {
                    typeSections.add(new int[]{j});
                    typeLabels.add(mt2.group(1));
                }
            }
            if (typeSections.isEmpty()) continue;
            for (int idx = 0; idx < typeSections.size(); idx++) {
                int secStart = typeSections.get(idx)[0];
                int secEnd = (idx + 1 < typeSections.size()) ? typeSections.get(idx + 1)[0] : blockEnd;
                StringBuilder secSb = new StringBuilder();
                for (int k = secStart; k < secEnd; k++) {
                    if (secSb.length() > 0) secSb.append(' ');
                    secSb.append(lines.get(k));
                }
                String secText = secSb.toString();
                Matcher m = ABS_RE.matcher(secText);
                while (m.find()) {
                    String startVal = m.group(1);
                    String endVal = m.group(2);
                    String ctxPrefix = secText.length() >= 100 ? secText.substring(0, 100) : secText;
                    String dedup = startVal + "|" + endVal + "|" + ctxPrefix;
                    if (seenAbs.contains(dedup)) continue;
                    seenAbs.add(dedup);
                    results.add(new Pair(startVal, endVal, true, secText));
                }
            }
        }

        return results;
    }

    public static Pair pickForRow(Map<String, Object> row, List<Pair> escalations) {
        if (escalations == null || escalations.isEmpty()) {
            return new Pair("", "", false, "");
        }
        List<Pair> candidates = new ArrayList<>(escalations);
        String text = AaText.rowText(row);

        if (text.contains("스마트전환형")) {
            List<Pair> smart = new ArrayList<>();
            for (Pair p : candidates) if (p.context.contains("전환일")) smart.add(p);
            if (!smart.isEmpty()) candidates = smart;
        } else {
            List<Pair> contract = new ArrayList<>();
            for (Pair p : candidates) if (p.context.contains("계약일")) contract.add(p);
            if (!contract.isEmpty()) candidates = contract;
        }

        if (candidates.size() > 1) {
            Matcher m = Pattern.compile("(\\d)종").matcher(text);
            if (m.find()) {
                String typeNum = m.group(1);
                List<Pair> typeMatched = new ArrayList<>();
                for (Pair p : candidates) if (p.context.contains(typeNum + "종")) typeMatched.add(p);
                if (!typeMatched.isEmpty()) candidates = typeMatched;
            }
        }
        return candidates.get(0);
    }
}
