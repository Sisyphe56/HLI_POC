package com.hanwha.setdata.extract.cycle;

import com.hanwha.setdata.util.Normalizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule parsing ported from {@code extract_payment_cycle_v2.py}:
 * {@code parse_override_rules}, {@code parse_cycle_line},
 * {@code parse_rules_from_text}, {@code parse_rules_from_table},
 * {@code extract_cycle_rules}.
 */
public final class CycleRuleParser {

    private CycleRuleParser() {}

    public record TextParseResult(List<CycleRule> rules, List<String> defaultCycles) {}

    public record ExtractResult(List<CycleRule> rules, List<String> defaultCycles) {}

    private record LineParseResult(List<CycleRule> rules, List<String> prevContext) {}

    private static final Pattern COLON_RE = Pattern.compile("[:：]");
    private static final Pattern LEADING_PAREN_NUM_RE = Pattern.compile("^\\(\\d+\\)");
    private static final Pattern LEADING_NUM_DOT_RE = Pattern.compile("^\\d+\\.");

    /** Python: parse_override_rules. */
    static List<CycleRule> parseOverrideRules(String rhs, List<String> baseContext, List<String> detailTokens) {
        List<CycleRule> rules = new ArrayList<>();
        for (String inner : PcCycleText.extractParenthesizedSections(rhs)) {
            if (!inner.contains("의 경우")) continue;
            int idx = inner.indexOf("의 경우");
            String condText = inner.substring(0, idx);
            String cycText = inner.substring(idx + "의 경우".length());
            // Python: cond_text.replace('단,', '').strip(', ')
            condText = condText.replace("단,", "");
            condText = stripChars(condText, ", ");
            List<String> condTokens = PcCycleText.extractContextTokens(condText, detailTokens);
            if (condTokens.isEmpty()) continue;
            List<String> cyc = PcCycleText.extractCycleNames(cycText);
            if (cyc.isEmpty()) continue;
            List<String> merged = PcCycleText.normalizeContextUnion(baseContext, condTokens);
            rules.add(new CycleRule(merged, cyc));
        }
        return rules;
    }

    /** Python str.strip(chars) — trim any leading/trailing chars in the set. */
    private static String stripChars(String s, String chars) {
        int start = 0, end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) start++;
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(start, end);
    }

    /** Python: parse_cycle_line. */
    static LineParseResult parseCycleLine(
            String line,
            List<String> prevContext,
            List<String> detailTokens,
            boolean inAdditionalSection) {
        String text = Normalizer.normalizeWs(line);
        if (text.isEmpty()) return new LineParseResult(List.of(), prevContext);

        if (text.contains("추가납입보험료") && text.contains("수시납")) {
            return new LineParseResult(List.of(), prevContext);
        }

        List<String> contexts = PcCycleText.extractContextTokens(text, detailTokens);
        if (!contexts.isEmpty()) prevContext = contexts;

        List<String> cycles = PcCycleText.extractCycleNames(text);
        boolean hasColon = text.contains(":") || text.contains("：");
        if (inAdditionalSection || !hasColon) {
            if (!inAdditionalSection && !cycles.isEmpty() && !prevContext.isEmpty()) {
                return new LineParseResult(
                        List.of(new CycleRule(prevContext, cycles)),
                        prevContext);
            }
            return new LineParseResult(List.of(), prevContext);
        }

        List<CycleRule> rules = new ArrayList<>();

        Matcher sep = COLON_RE.matcher(text);
        if (!sep.find()) return new LineParseResult(rules, prevContext);

        String lhs = text.substring(0, sep.start());
        String rhs = text.substring(sep.start() + 1);

        List<String> lhsCtx = PcCycleText.extractContextTokens(lhs, detailTokens);
        List<String> baseCtx = lhsCtx.isEmpty() ? prevContext : lhsCtx;

        List<String> baseCycles = new ArrayList<>(PcCycleText.extractCycleNames(rhs));
        for (String inner : PcCycleText.extractParenthesizedSections(rhs)) {
            if (!inner.contains("의 경우")) continue;
            for (String cyc : PcCycleText.extractCycleNames(inner)) {
                baseCycles.remove(cyc); // remove first occurrence
            }
        }
        if (!baseCycles.isEmpty()) {
            rules.add(new CycleRule(baseCtx, baseCycles));
        }

        rules.addAll(parseOverrideRules(rhs, baseCtx, detailTokens));

        return new LineParseResult(rules, prevContext);
    }

    /** Python: parse_rules_from_text. */
    public static TextParseResult parseRulesFromText(String text, List<String> detailTokens) {
        String[] rawLines = text.split("\n", -1);
        List<String> lines = new ArrayList<>(rawLines.length);
        for (String l : rawLines) lines.add(Normalizer.normalizeWs(l));

        List<CycleRule> rules = new ArrayList<>();
        List<String> sectionDefaultCycles = new ArrayList<>();
        List<String> contextStack = new ArrayList<>();
        boolean inPaymentSection = false;
        boolean inAdditionalSection = false;

        for (String line : lines) {
            if (line.isEmpty()) continue;

            if (line.contains("납입주기")) inPaymentSection = true;
            if (line.contains("추가납입보험료")) inAdditionalSection = true;

            if (inAdditionalSection
                    && LEADING_PAREN_NUM_RE.matcher(line).lookingAt()
                    && !line.contains("추가납입보험료")) {
                inAdditionalSection = false;
            }

            if (inPaymentSection
                    && LEADING_NUM_DOT_RE.matcher(line).lookingAt()
                    && !line.contains("납입주기")) {
                inPaymentSection = false;
            }

            if (inPaymentSection) {
                List<String> headingCtx = PcCycleText.extractContextTokens(line, detailTokens);
                if (!headingCtx.isEmpty()) contextStack = headingCtx;
            }

            if (!inPaymentSection && !line.contains("납입주기")) continue;

            List<String> lineCycles = PcCycleText.extractCycleNames(line);
            boolean anyKeyword = false;
            for (String k : new String[]{"납입주기", "일시납", "수시납", "월납", "3개월납", "6개월납", "연납", "년납"}) {
                if (line.contains(k)) { anyKeyword = true; break; }
            }
            if (!anyKeyword) continue;

            LineParseResult lp = parseCycleLine(line, contextStack, detailTokens, inAdditionalSection);
            rules.addAll(lp.rules());
            contextStack = lp.prevContext();

            boolean hasContextTokens = !PcCycleText.extractContextTokens(line, detailTokens).isEmpty();

            // NB: Python compares `line_cycles not in section_default_cycles` — lists vs
            // strings always truthy. Preserve Python behavior: always add individually.
            if (inPaymentSection && !inAdditionalSection && !hasContextTokens && !lineCycles.isEmpty()) {
                for (String cycleName : lineCycles) {
                    if (!sectionDefaultCycles.contains(cycleName)) sectionDefaultCycles.add(cycleName);
                }
            }

            if (lp.rules().isEmpty() && !PcCycleText.extractCycleNames(line).isEmpty()) {
                contextStack = new ArrayList<>();
            }
        }

        return new TextParseResult(rules, sectionDefaultCycles);
    }

    /** Python: parse_rules_from_table. */
    public static List<CycleRule> parseRulesFromTable(List<List<String>> table, List<String> detailTokens) {
        if (table.isEmpty()) return List.of();
        // Strip \n from cells before normalize_ws
        List<List<String>> rows = new ArrayList<>(table.size());
        for (List<String> row : table) {
            List<String> nr = new ArrayList<>(row.size());
            for (String c : row) {
                String s = c == null ? "" : c.replace("\n", "");
                nr.add(Normalizer.normalizeWs(s));
            }
            rows.add(nr);
        }

        List<CycleRule> rules = new ArrayList<>();
        List<String> prevCycles = null;
        for (List<String> row : rows) {
            if (row.isEmpty()) continue;

            List<List<String>> cycleCells = new ArrayList<>();
            for (String cell : row) {
                List<String> cs = PcCycleText.extractCycleNames(cell);
                if (!cs.isEmpty()) cycleCells.add(cs);
            }

            StringBuilder joined = new StringBuilder();
            for (String c : row) {
                if (joined.length() > 0) joined.append(' ');
                joined.append(c);
            }
            List<String> rowCtx = PcCycleText.extractContextTokens(joined.toString(), detailTokens);

            LinkedHashSet<String> merged = new LinkedHashSet<>();
            for (List<String> cs : cycleCells) merged.addAll(cs);
            List<String> mergedCycles = new ArrayList<>(merged);

            if (!mergedCycles.isEmpty()) {
                prevCycles = mergedCycles;
                rules.add(new CycleRule(rowCtx, mergedCycles));
            } else if (!rowCtx.isEmpty() && prevCycles != null) {
                rules.add(new CycleRule(rowCtx, new ArrayList<>(prevCycles)));
            }
        }
        return rules;
    }

    /** Python: extract_cycle_rules. */
    public static ExtractResult extractCycleRules(
            PcCycleDocx.Result docx,
            List<String> detailTokens,
            String sectionFilter) {

        List<List<List<String>>> tables = docx.tables();
        if (sectionFilter != null && !sectionFilter.isEmpty()) {
            List<List<List<String>>> filtered = new ArrayList<>();
            for (int i = 0; i < tables.size(); i++) {
                if (sectionFilter.equals(docx.tableSections().get(i))) {
                    filtered.add(tables.get(i));
                }
            }
            if (!filtered.isEmpty()) tables = filtered;
        }

        String fullText = String.join("\n", docx.lines());
        TextParseResult tp = parseRulesFromText(fullText, detailTokens);
        List<CycleRule> rules = new ArrayList<>(tp.rules());
        for (List<List<String>> table : tables) {
            rules.addAll(parseRulesFromTable(table, detailTokens));
        }

        List<String> finalDefaultCycles = new ArrayList<>();
        for (String c : tp.defaultCycles()) {
            if (!finalDefaultCycles.contains(c)) finalDefaultCycles.add(c);
        }

        // Dedupe: key = (tuple(normalize_match_key(ctx)), cycles)
        LinkedHashMap<String, CycleRule> uniq = new LinkedHashMap<>();
        for (CycleRule r : rules) {
            StringBuilder key = new StringBuilder();
            for (String x : r.contexts) key.append(PcCycleText.normalizeMatchKey(x)).append('\u0001');
            key.append('\u0002');
            for (String c : r.cycles) key.append(c).append('\u0001');
            String k = key.toString();
            uniq.putIfAbsent(k, r);
        }
        return new ExtractResult(new ArrayList<>(uniq.values()), finalDefaultCycles);
    }
}
