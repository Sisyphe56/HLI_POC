package com.hanwha.setdata.compare;

import com.hanwha.setdata.compare.CompareLoaders.MappingRow;
import com.hanwha.setdata.compare.DatasetConfigLoader.DatasetConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Report builders — extraction-based and CSV-row-based — mirroring
 * {@code generate_extraction_report} and {@code generic_answer_report}.
 */
public final class CompareReports {

    private CompareReports() {}

    /** Python {@code generate_extraction_report}. */
    public static Map<String, Object> extractionReport(List<Map<String, Object>> comparisonResults) {
        int total = comparisonResults.size();
        int matched = 0;
        for (Map<String, Object> r : comparisonResults) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cmp = (Map<String, Object>) r.get("comparison");
            if (Boolean.TRUE.equals(cmp.get("matched"))) matched++;
        }
        int unmatched = total - matched;

        // Insertion-order map of failure reasons.
        Map<String, Integer> failureReasons = new LinkedHashMap<>();
        for (Map<String, Object> r : comparisonResults) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cmp = (Map<String, Object>) r.get("comparison");
            if (!Boolean.TRUE.equals(cmp.get("matched"))) {
                String reason = (String) cmp.get("reason");
                failureReasons.merge(reason, 1, Integer::sum);
            }
        }

        // file_stats keyed on source_file; Python uses dict then sorts by key for output.
        Map<String, int[]> fileStats = new LinkedHashMap<>();
        for (Map<String, Object> r : comparisonResults) {
            String fn = (String) r.getOrDefault("source_file", "unknown");
            if (fn == null || fn.isEmpty()) fn = "unknown";
            int[] st = fileStats.computeIfAbsent(fn, k -> new int[3]); // total, matched, unmatched
            st[0]++;
            @SuppressWarnings("unchecked")
            Map<String, Object> cmp = (Map<String, Object>) r.get("comparison");
            if (Boolean.TRUE.equals(cmp.get("matched"))) st[1]++;
            else st[2]++;
        }
        List<String> sortedFiles = new ArrayList<>(fileStats.keySet());
        sortedFiles.sort(String::compareTo);
        List<Map<String, Object>> fileStatsOut = new ArrayList<>();
        for (String fn : sortedFiles) {
            int[] s = fileStats.get(fn);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("file", fn);
            e.put("total", s[0]);
            e.put("matched", s[1]);
            e.put("unmatched", s[2]);
            e.put("match_rate", s[0] > 0 ? String.format(Locale.US, "%.1f%%", (s[1] * 100.0) / s[0]) : "0%");
            fileStatsOut.add(e);
        }

        // sample_failures: first 20 unmatched, preserving Python's quirky use of
        // list(r["comparison"].keys())[0] which is always "matched" — so
        // mapped_matched / answer_matched never exist and those fields are [].
        // Python: `[ ... for r in comparison_results[:20] if not r['comparison']['matched']]`
        // — iterates only the first 20 rows, then filters unmatched. Can yield < 20 items.
        List<Map<String, Object>> sampleFailures = new ArrayList<>();
        int limit = Math.min(20, comparisonResults.size());
        for (int ri = 0; ri < limit; ri++) {
            Map<String, Object> r = comparisonResults.get(ri);
            @SuppressWarnings("unchecked")
            Map<String, Object> cmp = (Map<String, Object>) r.get("comparison");
            if (Boolean.TRUE.equals(cmp.get("matched"))) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("source_file", r.getOrDefault("source_file", ""));
            entry.put("product", r.getOrDefault("상품명", ""));
            entry.put("isrn_kind_dtcd", r.getOrDefault("isrn_kind_dtcd", ""));
            entry.put("isrn_kind_itcd", r.getOrDefault("isrn_kind_itcd", ""));
            entry.put("reason", cmp.get("reason"));
            entry.put("mapped", cmp.getOrDefault("mapped_matched", new ArrayList<>()));
            entry.put("answer", cmp.getOrDefault("answer_matched", new ArrayList<>()));
            entry.put("missing", cmp.getOrDefault("missing_in_mapped", new ArrayList<>()));
            entry.put("extra", cmp.getOrDefault("extra_in_mapped", new ArrayList<>()));
            sampleFailures.add(entry);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_rows", total);
        summary.put("matched_rows", matched);
        summary.put("unmatched_rows", unmatched);
        summary.put("match_rate", total > 0 ? String.format(Locale.US, "%.1f%%", (matched * 100.0) / total) : "0%");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("failure_reasons", failureReasons);
        out.put("file_statistics", fileStatsOut);
        out.put("sample_failures", sampleFailures);
        return out;
    }

    /** Python {@code generic_answer_report}. */
    public static Map<String, Object> answerBasedReport(
            Map<List<String>, List<Map<String, String>>> answerData,
            List<Map<String, Object>> mappedRows,
            List<MappingRow> csvRows,
            DatasetConfig cfg) {

        String label = cfg.outputLabel;
        List<String> specialRules = cfg.specialRules == null ? new ArrayList<>() : cfg.specialRules;
        Set<String> notSupported = new HashSet<>(cfg.notSupportedDtcds == null ? new ArrayList<>() : cfg.notSupportedDtcds);

        // Build mapped indexes.
        Map<List<String>, Set<List<String>>> mappedIndex4 = new LinkedHashMap<>();
        Map<List<String>, Set<List<String>>> mappedIndex2 = new LinkedHashMap<>();
        for (Map<String, Object> row : mappedRows) {
            String dtcd = CompareNormalizers.normalizeCode(row.getOrDefault("isrn_kind_dtcd", ""));
            String itcd = CompareNormalizers.normalizeCode(row.getOrDefault("isrn_kind_itcd", ""));
            if (dtcd.isEmpty() || itcd.isEmpty()) continue;
            String prodDtcd = CompareNormalizers.normalizeCode(row.getOrDefault("prod_dtcd", ""));
            String prodItcd = CompareNormalizers.normalizeCode(row.getOrDefault("prod_itcd", ""));
            Set<List<String>> tuples = CompareCore.extractMappedSet(row.get(cfg.dataField), cfg);
            if (!prodDtcd.isEmpty() && !prodItcd.isEmpty()) {
                List<String> key4 = List.of(dtcd, itcd, prodDtcd, prodItcd);
                mappedIndex4.computeIfAbsent(key4, k -> new LinkedHashSet<>()).addAll(tuples);
            }
            List<String> key2 = List.of(dtcd, itcd);
            mappedIndex2.computeIfAbsent(key2, k -> new LinkedHashSet<>()).addAll(tuples);
        }

        // Build answer index (dtcd, itcd, prod_dtcd, prod_itcd) → tuple set.
        Map<List<String>, Set<List<String>>> answerIndex = new LinkedHashMap<>();
        for (Map.Entry<List<String>, List<Map<String, String>>> e : answerData.entrySet()) {
            List<String> keyTuple = e.getKey();
            for (Map<String, String> r : e.getValue()) {
                String prodDtcd = CompareNormalizers.normalizeCode(r.getOrDefault("PROD_DTCD", ""));
                String prodItcd = CompareNormalizers.normalizeCode(r.getOrDefault("PROD_ITCD", ""));
                List<String> key4 = List.of(keyTuple.get(0), keyTuple.get(1), prodDtcd, prodItcd);
                Set<List<String>> tuples = CompareCore.extractAnswerSet(List.of(r), cfg);
                answerIndex.computeIfAbsent(key4, k -> new LinkedHashSet<>()).addAll(tuples);
            }
        }

        List<Map<String, Object>> rowsOut = new ArrayList<>();
        int total = 0, matched = 0, unmatched = 0, mismatched = 0, noAnswer = 0, notSupportedCount = 0;

        for (MappingRow cr : csvRows) {
            String dtcd = cr.isrnKindDtcd;
            String itcd = cr.isrnKindItcd;
            String prodDtcd = cr.prodDtcd;
            String prodItcd = cr.prodItcd;
            String saleNm = cr.isrnKindSaleNm;

            if (!notSupported.isEmpty() && notSupported.contains(dtcd)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("isrn_kind_dtcd", dtcd);
                entry.put("isrn_kind_itcd", itcd);
                entry.put("prod_dtcd", prodDtcd);
                entry.put("prod_itcd", prodItcd);
                entry.put("isrn_kind_sale_nm", saleNm);
                entry.put("status", "not_supported");
                entry.put("note", "사업방법서 미기재 - 추출 불가 상품");
                rowsOut.add(entry);
                notSupportedCount++;
                total++;
                continue;
            }

            List<String> key4 = List.of(dtcd, itcd, prodDtcd, prodItcd);
            Set<List<String>> answerVals = answerIndex.get(key4);
            Set<List<String>> mappedVals = mappedIndex4.get(key4);
            if (mappedVals == null) mappedVals = mappedIndex2.get(List.of(dtcd, itcd));

            if (specialRules.contains("gender_dedup") && mappedVals != null) {
                mappedVals = CompareCore.applyGenderDedup(mappedVals);
            }
            if (specialRules.contains("period_strip_fallback") && mappedVals != null && answerVals != null) {
                mappedVals = CompareCore.applyPeriodStripFallback(mappedVals, answerVals);
            }

            String status;
            if (answerVals == null) { status = "no_answer"; noAnswer++; }
            else if (mappedVals == null) { status = "unmatched"; unmatched++; }
            else if (CompareCore.setsEqual(mappedVals, answerVals)) { status = "matched"; matched++; }
            else { status = "mismatched"; mismatched++; }
            total++;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("isrn_kind_dtcd", dtcd);
            entry.put("isrn_kind_itcd", itcd);
            entry.put("prod_dtcd", prodDtcd);
            entry.put("prod_itcd", prodItcd);
            entry.put("isrn_kind_sale_nm", saleNm);
            entry.put("status", status);
            if (answerVals != null) entry.put("answer_" + label, CompareCore.sortedList(answerVals));
            if (mappedVals != null) entry.put("mapped_" + label, CompareCore.sortedList(mappedVals));
            if ("mismatched".equals(status) && mappedVals != null && answerVals != null) {
                List<List<String>> missing = CompareCore.sortedDiff(answerVals, mappedVals);
                List<List<String>> extra = CompareCore.sortedDiff(mappedVals, answerVals);
                if (!missing.isEmpty()) entry.put("missing_in_mapped", missing);
                if (!extra.isEmpty()) entry.put("extra_in_mapped", extra);
            }
            rowsOut.add(entry);
        }

        // Sort rows by (status_order, dtcd, itcd) — use stable sort preserving input order on ties.
        Map<String, Integer> order = new LinkedHashMap<>();
        order.put("unmatched", 0);
        order.put("mismatched", 1);
        order.put("no_answer", 2);
        order.put("matched", 3);
        order.put("not_supported", 4);
        rowsOut.sort(Comparator
                .<Map<String, Object>, Integer>comparing(r -> order.getOrDefault((String) r.get("status"), 9))
                .thenComparing(r -> (String) r.get("isrn_kind_dtcd"))
                .thenComparing(r -> (String) r.get("isrn_kind_itcd")));

        int hasAnswer = matched + unmatched + mismatched;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_csv_rows", total);
        summary.put("matched", matched);
        summary.put("unmatched", unmatched);
        summary.put("mismatched", mismatched);
        summary.put("no_answer", noAnswer);
        summary.put("match_rate_with_answer",
                hasAnswer > 0 ? String.format(Locale.US, "%.1f%%", matched * 100.0 / hasAnswer) : "0%");
        summary.put("match_rate_total",
                total > 0 ? String.format(Locale.US, "%.1f%%", matched * 100.0 / total) : "0%");
        if (notSupportedCount > 0) summary.put("not_supported", notSupportedCount);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("rows", rowsOut);
        return out;
    }

    @SuppressWarnings("unused")
    private static final TreeMap<String, String> UNUSED = new TreeMap<>();
}
