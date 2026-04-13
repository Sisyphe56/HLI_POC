package com.hanwha.setdata.compare;

import com.hanwha.setdata.compare.DatasetConfigLoader.DatasetConfig;
import com.hanwha.setdata.compare.DatasetConfigLoader.FieldSpec;
import com.hanwha.setdata.compare.DatasetConfigLoader.SkipRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Core tuple extraction + generic comparison logic, mirroring
 * {@code _extract_generic_set}, {@code _extract_generic_answer_set},
 * {@code generic_compare}, and the special-rule handlers in
 * {@code compare_product_data.py}.
 */
public final class CompareCore {

    private CompareCore() {}

    /** Lexicographic tuple comparator, equivalent to Python's default list sort. */
    public static final java.util.Comparator<List<String>> TUPLE_CMP = (a, b) -> {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(a.size(), b.size());
    };

    /** Extract tuple set from mapped JSON items (list of dicts). */
    public static Set<List<String>> extractMappedSet(Object dataListObj, DatasetConfig cfg) {
        Set<List<String>> result = new LinkedHashSet<>();
        if (!(dataListObj instanceof List)) return result;
        List<?> data = (List<?>) dataListObj;
        List<FieldSpec> fields = cfg.tupleFields;
        for (Object itm : data) {
            if (!(itm instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) itm;
            List<String> values = new ArrayList<>(fields.size());
            for (FieldSpec f : fields) {
                Object raw = item.getOrDefault(f.jsonKey, "");
                Function<Object, String> norm = CompareNormalizers.byName(f.norm);
                values.add(norm.apply(raw));
            }
            if (shouldSkip(values, fields, cfg.skipRule)) continue;
            applyDefaults(values, fields);
            result.add(Collections.unmodifiableList(values));
        }
        return result;
    }

    /** Extract tuple set from answer rows (list of normalized maps). */
    public static Set<List<String>> extractAnswerSet(List<Map<String, String>> answerRows, DatasetConfig cfg) {
        Set<List<String>> result = new LinkedHashSet<>();
        List<FieldSpec> fields = cfg.tupleFields;
        for (Map<String, String> r : answerRows) {
            List<String> values = new ArrayList<>(fields.size());
            for (FieldSpec f : fields) {
                String raw = r.getOrDefault(f.csvKey, "");
                Function<Object, String> norm = CompareNormalizers.byName(f.norm);
                values.add(norm.apply(raw));
            }
            if (shouldSkip(values, fields, cfg.skipRule)) continue;
            applyDefaults(values, fields);
            result.add(Collections.unmodifiableList(values));
        }
        return result;
    }

    private static void applyDefaults(List<String> values, List<FieldSpec> fields) {
        for (int i = 0; i < fields.size(); i++) {
            FieldSpec f = fields.get(i);
            if (values.get(i).isEmpty() && f.hasDefault) {
                values.set(i, f.defaultValue);
            }
        }
    }

    private static boolean shouldSkip(List<String> values, List<FieldSpec> fields, SkipRule rule) {
        if (rule == null || rule.mode == null) return false;
        List<Integer> idx = rule.fields;
        if (idx == null || idx.isEmpty()) return false;
        switch (rule.mode) {
            case "any_empty":
                for (int i : idx) if (values.get(i).isEmpty()) return true;
                return false;
            case "all_empty":
                for (int i : idx) if (!values.get(i).isEmpty()) return false;
                return true;
            case "all_default":
                for (int i : idx) {
                    FieldSpec f = fields.get(i);
                    String def = f.hasDefault ? f.defaultValue : "";
                    if (!values.get(i).equals(def)) return false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Sort set of tuples into a JSON-friendly List&lt;List&lt;String&gt;&gt;. */
    public static List<List<String>> sortedList(Set<List<String>> set) {
        List<List<String>> out = new ArrayList<>(set.size());
        for (List<String> t : set) out.add(new ArrayList<>(t));
        out.sort(TUPLE_CMP);
        return out;
    }

    /** Set difference (a \ b) sorted. */
    public static List<List<String>> sortedDiff(Set<List<String>> a, Set<List<String>> b) {
        List<List<String>> out = new ArrayList<>();
        for (List<String> t : a) if (!b.contains(t)) out.add(new ArrayList<>(t));
        out.sort(TUPLE_CMP);
        return out;
    }

    /** Python {@code generic_compare}. */
    public static Map<String, Object> genericCompare(
            Map<String, Object> mappedRow, List<Map<String, String>> answerRows, DatasetConfig cfg) {
        String dataField = cfg.dataField;
        String label = cfg.outputLabel;
        Object dataListObj = mappedRow.get(dataField);
        Map<String, Object> out = new LinkedHashMap<>();

        if (!(dataListObj instanceof List) || ((List<?>) dataListObj).isEmpty()) {
            Set<List<String>> answerSet = extractAnswerSet(answerRows, cfg);
            out.put("matched", false);
            out.put("reason", "No " + dataField + " data in mapped row");
            out.put("mapped_" + label, new ArrayList<>());
            out.put("answer_" + label, sortedList(answerSet));
            return out;
        }

        Set<List<String>> mappedSet = extractMappedSet(dataListObj, cfg);
        Set<List<String>> answerSet = extractAnswerSet(answerRows, cfg);

        if (setsEqual(mappedSet, answerSet)) {
            out.put("matched", true);
            out.put("reason", "Perfect match");
            out.put("mapped_" + label, sortedList(mappedSet));
            out.put("answer_" + label, sortedList(answerSet));
            return out;
        }
        out.put("matched", false);
        out.put("reason", dataField + " mismatch");
        out.put("mapped_" + label, sortedList(mappedSet));
        out.put("answer_" + label, sortedList(answerSet));
        out.put("missing_in_mapped", sortedDiff(answerSet, mappedSet));
        out.put("extra_in_mapped", sortedDiff(mappedSet, answerSet));
        return out;
    }

    public static boolean setsEqual(Set<List<String>> a, Set<List<String>> b) {
        if (a.size() != b.size()) return false;
        for (List<String> t : a) if (!b.contains(t)) return false;
        return true;
    }

    // ─── Special rule handlers ───────────────────────────────────────────

    /** Python {@code _apply_gender_dedup}. */
    public static Set<List<String>> applyGenderDedup(Set<List<String>> mappedSet) {
        if (mappedSet == null || mappedSet.isEmpty()) return mappedSet;
        Iterator<List<String>> it = mappedSet.iterator();
        List<String> sample = it.next();
        if (sample.size() < 3) return mappedSet;
        Set<List<String>> neutralSigs = new LinkedHashSet<>();
        for (List<String> t : mappedSet) {
            if (t.get(2).isEmpty()) {
                neutralSigs.add(sigWithoutGender(t));
            }
        }
        if (neutralSigs.isEmpty()) return mappedSet;
        Set<List<String>> result = new LinkedHashSet<>();
        for (List<String> t : mappedSet) {
            if (!t.get(2).isEmpty() && neutralSigs.contains(sigWithoutGender(t))) continue;
            result.add(t);
        }
        return result;
    }

    private static List<String> sigWithoutGender(List<String> t) {
        List<String> sig = new ArrayList<>(t.size() - 1);
        sig.add(t.get(0));
        sig.add(t.get(1));
        for (int i = 3; i < t.size(); i++) sig.add(t.get(i));
        return Collections.unmodifiableList(sig);
    }

    /** Python {@code _apply_period_strip_fallback}. */
    public static Set<List<String>> applyPeriodStripFallback(Set<List<String>> mappedSet, Set<List<String>> answerSet) {
        if (mappedSet == null || mappedSet.isEmpty() || answerSet == null || answerSet.isEmpty()) return mappedSet;
        if (setsEqual(mappedSet, answerSet)) return mappedSet;
        List<String> sample = answerSet.iterator().next();
        if (sample.size() < 10) return mappedSet;
        boolean answerHasPeriods = false;
        for (List<String> a : answerSet) {
            for (int i : new int[]{3, 6, 9}) {
                if (i < a.size() && !a.get(i).isEmpty()) { answerHasPeriods = true; break; }
            }
            if (answerHasPeriods) break;
        }
        if (answerHasPeriods) return mappedSet;
        Set<List<String>> stripped = new LinkedHashSet<>();
        for (List<String> t : mappedSet) {
            List<String> nt = new ArrayList<>(t.size());
            nt.add(t.get(0));
            nt.add(t.get(1));
            nt.add(t.get(2));
            for (int i = 3; i < t.size(); i++) nt.add("");
            stripped.add(Collections.unmodifiableList(nt));
        }
        if (setsEqual(stripped, answerSet)) return stripped;
        return mappedSet;
    }
}
