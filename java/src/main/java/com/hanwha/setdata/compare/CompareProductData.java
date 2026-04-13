package com.hanwha.setdata.compare;

import com.hanwha.setdata.compare.CompareLoaders.MappingRow;
import com.hanwha.setdata.compare.DatasetConfigLoader.DatasetConfig;
import com.hanwha.setdata.output.PythonStyleJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Java port of {@code compare_product_data.py}.
 *
 * <p>Entry points:
 * <ul>
 *   <li>{@link #main(String[])} — CLI mirroring Python's argparse.</li>
 *   <li>{@link #run(DatasetConfig, Path, Path, boolean)} — batch directory mode.</li>
 *   <li>{@link #runSingle(DatasetConfig, Path, Path, boolean)} — single-file mode.</li>
 * </ul>
 */
public final class CompareProductData {

    private static final Path PROJECT_ROOT = defaultProjectRoot();

    private static Path defaultProjectRoot() {
        // This file lives at .../web-demo-session/java/src/main/java/...; the
        // Python project root is the parent of the java dir.
        Path here = Paths.get("").toAbsolutePath();
        // If caller's cwd is already the project root, just use it. Otherwise fall
        // back to walking up from the class location is brittle; default to cwd.
        if (Files.exists(here.resolve("compare_product_data.py"))) return here;
        Path parent = here.getParent();
        if (parent != null && Files.exists(parent.resolve("compare_product_data.py"))) return parent;
        return here;
    }

    private static final Path DEFAULT_MAPPING_CSV =
            PROJECT_ROOT.resolve("config").resolve("보종코드_상품코드_매핑.csv");
    private static final Path DATASET_CONFIGS_PATH =
            PROJECT_ROOT.resolve("config").resolve("dataset_configs.json");

    private CompareProductData() {}

    // ─── Public library API ──────────────────────────────────────────────

    /** Batch directory mode (Python {@code run_comparison}). */
    public static void run(DatasetConfig cfg, Path mappedDirOverride, Path reportDirOverride, boolean verbose)
            throws IOException {
        Path mappedDir = mappedDirOverride != null ? mappedDirOverride : PROJECT_ROOT.resolve(cfg.mappedDir);
        Path reportDir = reportDirOverride != null ? reportDirOverride : PROJECT_ROOT.resolve(cfg.reportDir);

        System.out.printf("[%s] Loading CSV mapping...%n", cfg.name);
        List<MappingRow> csvRows = CompareLoaders.loadMappingCsv(DEFAULT_MAPPING_CSV);
        System.out.printf("  %d CSV rows%n", csvRows.size());

        Path answerCsv = cfg.answerCsv != null ? PROJECT_ROOT.resolve(cfg.answerCsv) : null;
        Map<List<String>, List<Map<String, String>>> answerData;
        if (answerCsv != null && Files.exists(answerCsv)) {
            System.out.printf("[%s] Loading answer CSV: %s%n", cfg.name, answerCsv.getFileName());
            answerData = CompareLoaders.loadAnswerCsv(answerCsv, cfg.answerKeyCols, cfg.answerValueCols);
        } else {
            System.out.printf("[%s] Loading answer Excel...%n", cfg.name);
            answerData = CompareLoaders.loadAnswerExcel(
                    PROJECT_ROOT.resolve(cfg.answerExcel), cfg.answerKeyCols, cfg.answerValueCols);
        }
        int answerRowCount = 0;
        for (List<Map<String, String>> v : answerData.values()) answerRowCount += v.size();
        System.out.printf("  %d answer rows, %d unique products%n", answerRowCount, answerData.size());

        System.out.printf("%n[%s] Loading mapped JSON files...%n", cfg.name);
        List<Map<String, Object>> mappedRows = CompareLoaders.loadMappedJsonFiles(mappedDir, cfg.filePrefix);
        System.out.printf("  %d mapped rows%n", mappedRows.size());

        System.out.printf("%n[%s] Comparing...%n", cfg.name);
        List<Map<String, Object>> comparisonResults = new ArrayList<>();

        for (Map<String, Object> mappedRow : mappedRows) {
            String dtcd = CompareNormalizers.normalizeCode(mappedRow.getOrDefault("isrn_kind_dtcd", ""));
            String itcd = CompareNormalizers.normalizeCode(mappedRow.getOrDefault("isrn_kind_itcd", ""));
            String saleNm = CompareNormalizers.normalizeText(mappedRow.getOrDefault("isrn_kind_sale_nm", ""));
            String prodDtcd = CompareNormalizers.normalizeCode(mappedRow.getOrDefault("prod_dtcd", ""));
            String prodItcd = CompareNormalizers.normalizeCode(mappedRow.getOrDefault("prod_itcd", ""));

            // Python: `if not (dtcd and itcd and sale_nm)` — normalize_code of empty → '0' (truthy),
            // so that branch fires only when sale_nm is empty (for empty codes the branch still fires
            // in practice because Python compares to original empties? No — it's literally checking
            // the normalized strings, and those are non-empty. The observed "No mapping found (empty
            // codes)" failures come from rows where sale_nm is empty OR dtcd/itcd were originally
            // non-empty but non-digit. We just mirror Python's boolean check on the normalized values.
            if (dtcd.isEmpty() || itcd.isEmpty() || saleNm.isEmpty()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("source_file", mappedRow.getOrDefault("source_file", ""));
                entry.put("isrn_kind_dtcd", dtcd);
                entry.put("isrn_kind_itcd", itcd);
                entry.put("isrn_kind_sale_nm", saleNm);
                entry.put("prod_dtcd", prodDtcd);
                entry.put("prod_itcd", prodItcd);
                entry.put("상품명", mappedRow.getOrDefault("상품명", ""));
                Map<String, Object> cmp = new LinkedHashMap<>();
                cmp.put("matched", false);
                cmp.put("reason", "No mapping found (empty codes)");
                cmp.put("mapped_cycles", new ArrayList<>());
                cmp.put("answer_cycles", new ArrayList<>());
                entry.put("comparison", cmp);
                comparisonResults.add(entry);
                continue;
            }

            // Build key based on answer_key_cols (same mechanism as Python).
            List<String> key = buildAnswerKey(cfg, dtcd, itcd, saleNm);
            List<Map<String, String>> answerRows = answerData.getOrDefault(key, new ArrayList<>());

            if (!prodDtcd.isEmpty() && !prodItcd.isEmpty() && !answerRows.isEmpty()) {
                List<Map<String, String>> filtered = new ArrayList<>();
                for (Map<String, String> r : answerRows) {
                    if (CompareNormalizers.normalizeCode(r.getOrDefault("PROD_DTCD", "")).equals(prodDtcd)
                            && CompareNormalizers.normalizeCode(r.getOrDefault("PROD_ITCD", "")).equals(prodItcd)) {
                        filtered.add(r);
                    }
                }
                if (!filtered.isEmpty()) answerRows = filtered;
            }

            if (answerRows.isEmpty()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("source_file", mappedRow.getOrDefault("source_file", ""));
                entry.put("isrn_kind_dtcd", dtcd);
                entry.put("isrn_kind_itcd", itcd);
                entry.put("isrn_kind_sale_nm", saleNm);
                entry.put("prod_dtcd", prodDtcd);
                entry.put("prod_itcd", prodItcd);
                entry.put("상품명", mappedRow.getOrDefault("상품명", ""));
                Map<String, Object> cmp = new LinkedHashMap<>();
                cmp.put("matched", false);
                cmp.put("reason", "Product not found in answer");
                cmp.put("mapped_cycles", new ArrayList<>());
                cmp.put("answer_cycles", new ArrayList<>());
                entry.put("comparison", cmp);
                comparisonResults.add(entry);
                continue;
            }

            Map<String, Object> comparison = CompareCore.genericCompare(mappedRow, answerRows, cfg);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("source_file", mappedRow.getOrDefault("source_file", ""));
            entry.put("isrn_kind_dtcd", dtcd);
            entry.put("isrn_kind_itcd", itcd);
            entry.put("isrn_kind_sale_nm", saleNm);
            entry.put("prod_dtcd", prodDtcd);
            entry.put("prod_itcd", prodItcd);
            entry.put("상품명", mappedRow.getOrDefault("상품명", ""));
            entry.put("comparison", comparison);
            comparisonResults.add(entry);

            if (verbose && !Boolean.TRUE.equals(comparison.get("matched"))) {
                System.out.printf("%n  Mismatch: %s%n", mappedRow.getOrDefault("source_file", ""));
                System.out.printf("    Product: %s%n", mappedRow.getOrDefault("상품명", ""));
            }
        }

        Files.createDirectories(reportDir);

        Map<String, Object> report = CompareReports.extractionReport(comparisonResults);
        Path reportPath = reportDir.resolve("comparison_report.json");
        PythonStyleJson.writeFile(reportPath, report);

        Path detailedPath = reportDir.resolve("comparison_detailed.json");
        PythonStyleJson.writeFile(detailedPath, comparisonResults);

        Map<String, Object> answerReport = CompareReports.answerBasedReport(answerData, mappedRows, csvRows, cfg);
        Path answerReportPath = reportDir.resolve("answer_based_report.json");
        PythonStyleJson.writeFile(answerReportPath, answerReport);

        // Console summary
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) report.get("summary");
        System.out.printf("%n%s%n", repeat('=', 50));
        System.out.printf("EXTRACTION-BASED REPORT (%s)%n", cfg.name);
        System.out.printf("%s%n", repeat('=', 50));
        System.out.printf("Total rows: %s%n", s.get("total_rows"));
        System.out.printf("Matched:    %s%n", s.get("matched_rows"));
        System.out.printf("Unmatched:  %s%n", s.get("unmatched_rows"));
        System.out.printf("Match rate: %s%n", s.get("match_rate"));
        @SuppressWarnings("unchecked")
        Map<String, Integer> fr = (Map<String, Integer>) report.get("failure_reasons");
        if (!fr.isEmpty()) {
            System.out.println("\nFailure reasons:");
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(fr.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            for (Map.Entry<String, Integer> e : entries) {
                System.out.printf("  %s: %d%n", e.getKey(), e.getValue());
            }
        }
        System.out.printf("%nReport: %s%n", reportPath);
        System.out.printf("Detail: %s%n", detailedPath);

        @SuppressWarnings("unchecked")
        Map<String, Object> s2 = (Map<String, Object>) answerReport.get("summary");
        System.out.printf("%n%s%n", repeat('=', 50));
        System.out.printf("CSV-ROW-BASED REPORT (%s)%n", cfg.name);
        System.out.printf("%s%n", repeat('=', 50));
        System.out.printf("Total CSV rows: %s%n", s2.get("total_csv_rows"));
        System.out.printf("  Matched:    %s%n", s2.get("matched"));
        System.out.printf("  Unmatched:  %s (매핑 데이터 없음)%n", s2.get("unmatched"));
        System.out.printf("  Mismatched: %s (값 불일치)%n", s2.get("mismatched"));
        System.out.printf("  No answer:  %s (정답 없음)%n", s2.get("no_answer"));
        System.out.printf("  Match rate (정답 있는 건): %s%n", s2.get("match_rate_with_answer"));
        System.out.printf("  Match rate (전체):        %s%n", s2.get("match_rate_total"));
        System.out.printf("Report: %s%n", answerReportPath);
    }

    /** Single-file mode (Python {@code run_single_comparison}). */
    public static void runSingle(DatasetConfig cfg, Path jsonPath, Path outputPath, boolean verbose) throws IOException {
        Path answerCsv = cfg.answerCsv != null ? PROJECT_ROOT.resolve(cfg.answerCsv) : null;
        Map<List<String>, List<Map<String, String>>> answerData;
        if (answerCsv != null && Files.exists(answerCsv)) {
            answerData = CompareLoaders.loadAnswerCsv(answerCsv, cfg.answerKeyCols, cfg.answerValueCols);
        } else {
            answerData = CompareLoaders.loadAnswerExcel(
                    PROJECT_ROOT.resolve(cfg.answerExcel), cfg.answerKeyCols, cfg.answerValueCols);
        }

        List<Map<String, Object>> mappedRows = CompareLoaders.loadSingleMappedJson(jsonPath);

        System.out.printf("[%s] Single file: %s%n", cfg.name, jsonPath.getFileName());
        int answerRowCount = 0;
        for (List<Map<String, String>> v : answerData.values()) answerRowCount += v.size();
        System.out.printf("  %d mapped rows, %d answer rows%n", mappedRows.size(), answerRowCount);

        List<Map<String, Object>> comparisonResults = new ArrayList<>();
        int matched = 0, unmatched = 0, noAnswer = 0, noMapping = 0;
        for (Map<String, Object> mappedRow : mappedRows) {
            String dtcd = CompareNormalizers.normalizeCode(mappedRow.getOrDefault("isrn_kind_dtcd", ""));
            String itcd = CompareNormalizers.normalizeCode(mappedRow.getOrDefault("isrn_kind_itcd", ""));
            String saleNm = CompareNormalizers.normalizeText(mappedRow.getOrDefault("isrn_kind_sale_nm", ""));
            String prodDtcd = CompareNormalizers.normalizeCode(mappedRow.getOrDefault("prod_dtcd", ""));
            String prodItcd = CompareNormalizers.normalizeCode(mappedRow.getOrDefault("prod_itcd", ""));

            Map<String, Object> base = new LinkedHashMap<>();
            base.put("source_file", mappedRow.getOrDefault("source_file", ""));
            base.put("isrn_kind_dtcd", dtcd);
            base.put("isrn_kind_itcd", itcd);
            base.put("isrn_kind_sale_nm", saleNm);
            base.put("prod_dtcd", prodDtcd);
            base.put("prod_itcd", prodItcd);
            base.put("상품명", mappedRow.getOrDefault("상품명", ""));

            if (dtcd.isEmpty() || itcd.isEmpty() || saleNm.isEmpty()) {
                Map<String, Object> cmp = new LinkedHashMap<>();
                cmp.put("matched", false);
                cmp.put("reason", "No mapping found (empty codes)");
                base.put("comparison", cmp);
                noMapping++;
                comparisonResults.add(base);
                continue;
            }

            List<String> key = buildAnswerKey(cfg, dtcd, itcd, saleNm);
            List<Map<String, String>> answerRows = answerData.getOrDefault(key, new ArrayList<>());
            if (!prodDtcd.isEmpty() && !prodItcd.isEmpty() && !answerRows.isEmpty()) {
                List<Map<String, String>> filtered = new ArrayList<>();
                for (Map<String, String> r : answerRows) {
                    if (CompareNormalizers.normalizeCode(r.getOrDefault("PROD_DTCD", "")).equals(prodDtcd)
                            && CompareNormalizers.normalizeCode(r.getOrDefault("PROD_ITCD", "")).equals(prodItcd)) {
                        filtered.add(r);
                    }
                }
                if (!filtered.isEmpty()) answerRows = filtered;
            }

            if (answerRows.isEmpty()) {
                Map<String, Object> cmp = new LinkedHashMap<>();
                cmp.put("matched", false);
                cmp.put("reason", "Product not found in answer");
                base.put("comparison", cmp);
                noAnswer++;
                comparisonResults.add(base);
                continue;
            }

            Map<String, Object> comparison = CompareCore.genericCompare(mappedRow, answerRows, cfg);
            base.put("comparison", comparison);
            if (Boolean.TRUE.equals(comparison.get("matched"))) matched++;
            else unmatched++;
            comparisonResults.add(base);

            if (verbose && !Boolean.TRUE.equals(comparison.get("matched"))) {
                System.out.printf("  Mismatch: %s%n", mappedRow.getOrDefault("상품명", ""));
            }
        }

        int total = comparisonResults.size();
        Path out = outputPath;
        if (out == null) {
            String name = jsonPath.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String base = dot >= 0 ? name.substring(0, dot) : name;
            out = jsonPath.resolveSibling(base + ".compare.json");
        }
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        PythonStyleJson.writeFile(out, comparisonResults);

        System.out.printf("%n  Total: %d%n", total);
        System.out.printf("  Matched: %d%n", matched);
        System.out.printf("  Mismatched: %d%n", unmatched);
        System.out.printf("  No answer: %d%n", noAnswer);
        System.out.printf("  No mapping: %d%n", noMapping);
        if (total > 0) {
            System.out.printf("  Match rate: %s%n", String.format(Locale.US, "%.1f%%", matched * 100.0 / total));
        }
        System.out.printf("  Report: %s%n", out);
    }

    /**
     * Build the answer-data lookup key from the normalized (dtcd, itcd, saleNm)
     * triple, re-normalizing per {@code answer_key_cols} just like Python does.
     */
    private static List<String> buildAnswerKey(DatasetConfig cfg, String dtcd, String itcd, String saleNm) {
        String[] vals = {dtcd, itcd, saleNm};
        List<String> key = new ArrayList<>(cfg.answerKeyCols.size());
        for (int i = 0; i < cfg.answerKeyCols.size(); i++) {
            String col = cfg.answerKeyCols.get(i);
            String v = i < vals.length ? vals[i] : "";
            if ("ISRN_KIND_DTCD".equals(col) || "ISRN_KIND_ITCD".equals(col)) {
                key.add(CompareNormalizers.normalizeCode(v));
            } else {
                key.add(CompareNormalizers.normalizeText(v));
            }
        }
        return key;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    // ─── CLI ─────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        String dataSet = null;
        String jsonArg = null;
        String outputArg = null;
        Path answerExcelArg = null;
        Path mappedDirArg = null;
        Path reportDirArg = null;
        boolean verbose = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--data-set":
                    dataSet = args[++i]; break;
                case "--json":
                    jsonArg = args[++i]; break;
                case "--output":
                    outputArg = args[++i]; break;
                case "--answer-excel":
                    answerExcelArg = Paths.get(args[++i]); break;
                case "--mapped-dir":
                    mappedDirArg = Paths.get(args[++i]); break;
                case "--report-dir":
                    reportDirArg = Paths.get(args[++i]); break;
                case "--verbose":
                    verbose = true; break;
                default:
                    throw new IllegalArgumentException("Unknown argument: " + a);
            }
        }
        if (dataSet == null) {
            System.err.println("Usage: CompareProductData --data-set {payment_cycle|annuity_age|insurance_period|join_age}"
                    + " [--json PATH] [--output PATH] [--answer-excel PATH] [--mapped-dir PATH] [--report-dir PATH] [--verbose]");
            System.exit(2);
        }

        Map<String, DatasetConfig> cfgs = DatasetConfigLoader.load(DATASET_CONFIGS_PATH, PROJECT_ROOT);
        DatasetConfig cfg = cfgs.get(dataSet);
        if (cfg == null) {
            throw new IllegalArgumentException("Unknown data-set: " + dataSet + ". Available: " + cfgs.keySet());
        }
        if (answerExcelArg != null) {
            // Mirror Python: override answer_excel (does not affect answer_csv preference).
            cfg = new DatasetConfig(
                    cfg.name, cfg.mappedDir, answerExcelArg.toString(), cfg.answerCsv,
                    cfg.reportDir, cfg.filePrefix, cfg.dataField, cfg.outputLabel,
                    cfg.answerKeyCols, cfg.answerValueCols, cfg.tupleFields, cfg.skipRule,
                    cfg.specialRules, cfg.notSupportedDtcds);
        }

        if (jsonArg != null) {
            Path jsonPath = Paths.get(jsonArg);
            Path outputPath = outputArg != null ? Paths.get(outputArg) : null;
            runSingle(cfg, jsonPath, outputPath, verbose);
            return;
        }
        run(cfg, mappedDirArg, reportDirArg, verbose);
    }
}
