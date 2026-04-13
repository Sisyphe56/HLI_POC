package com.hanwha.setdata.mapping;

import com.hanwha.setdata.config.OverridesConfig;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Java port of {@code map_product_code.py}: unified product-code mapping CLI.
 *
 * <p>Entry points:
 * <ul>
 *   <li>{@link #main(String[])} — argparse-compatible CLI.</li>
 *   <li>{@link #runDataset(String, Path, Path, Path, Path, Path)} — library call.</li>
 * </ul>
 */
public final class MapProductCode {

    private final OverridesConfig overrides;
    private final LinkedHashMap<String, DataSetConfig> configs;

    public MapProductCode(Path projectRoot) throws IOException {
        Path overridesPath = projectRoot.resolve("config").resolve("product_overrides.json");
        this.overrides = Files.exists(overridesPath) ? OverridesConfig.load(overridesPath) : null;
        RowBuilder rowBuilder = new RowBuilder(overrides);
        this.configs = DataSetConfig.defaults(projectRoot, rowBuilder);
    }

    public LinkedHashMap<String, DataSetConfig> configs() {
        return configs;
    }

    public List<String> allDataFieldNames() {
        List<String> out = new ArrayList<>();
        for (DataSetConfig c : configs.values()) {
            if (!c.dataFieldName.isEmpty()) out.add(c.dataFieldName);
        }
        return out;
    }

    /**
     * Directory mode. Python equivalent: the bottom half of {@code main()}.
     */
    public void runDataset(
            String dataSetName,
            Path mappingCsv,
            Path inputDirOverride,
            Path outputDirOverride) throws IOException {
        DataSetConfig config = configs.get(dataSetName);
        if (config == null) throw new IllegalArgumentException("Unknown data-set: " + dataSetName);

        Path inputDir = inputDirOverride != null ? inputDirOverride : config.inputDir;
        Path outputDir = outputDirOverride != null ? outputDirOverride : config.outputDir;

        List<MappingRow> mappingRows = MappingCsv.load(mappingCsv);
        System.out.println("Loaded " + mappingRows.size() + " CSV mapping rows");

        if (!Files.exists(inputDir)) {
            throw new IOException("입력 폴더를 찾지 못했습니다: " + inputDir);
        }

        int aliasCount = SedataAlias.apply(inputDir, config, overrides);
        if (aliasCount > 0) {
            System.out.println("sedata_alias: " + aliasCount + " alias files created");
        }

        List<Path> inputFiles = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(inputDir, "*.json")) {
            for (Path p : ds) inputFiles.add(p);
        }
        Collections.sort(inputFiles);
        if (inputFiles.isEmpty()) {
            throw new IOException("매핑 대상 JSON이 없습니다: " + inputDir);
        }

        Set<String> allMatchedCsvIds = new LinkedHashSet<>();
        List<LinkedHashMap<String, Object>> fileStats = new ArrayList<>();

        for (Path jsonPath : inputFiles) {
            String outputName = config.outputPrefix + jsonPath.getFileName().toString();
            Path outputPath = outputDir.resolve(outputName);

            FileProcessor.Result res = FileProcessor.process(jsonPath, mappingRows, config);
            JsonIO.writeJson(outputPath, res.mappedRows);
            allMatchedCsvIds.addAll(res.matchedCsvIds);

            LinkedHashMap<String, Object> stat = new LinkedHashMap<>();
            stat.put("file", jsonPath.getFileName().toString());
            stat.put("output", outputName);
            for (var e : res.stats.entrySet()) stat.put(e.getKey(), e.getValue());
            fileStats.add(stat);

            System.out.println(jsonPath.getFileName() + ": "
                    + "Matched=" + res.stats.get("matched")
                    + ", Unmatched=" + res.stats.get("unmatched")
                    + ", Ambiguous=" + res.stats.get("ambiguous"));
        }

        Set<String> siblingIds = SiblingFallback.applyToFiles(
                mappingRows, allMatchedCsvIds, outputDir, config, allDataFieldNames());
        allMatchedCsvIds.addAll(siblingIds);

        LinkedHashMap<String, Object> report = Report.generate(
                dataSetName, mappingRows, allMatchedCsvIds, fileStats);
        Path reportPath = outputDir.resolve("mapping_report.json");
        JsonIO.writeJson(reportPath, report);

        @SuppressWarnings("unchecked")
        LinkedHashMap<String, Object> s = (LinkedHashMap<String, Object>) report.get("summary");
        String bar = "=".repeat(50);
        System.out.println();
        System.out.println(bar);
        System.out.println("[" + dataSetName + "] MAPPING SUMMARY");
        System.out.println(bar);
        System.out.println("Input rows:  " + s.get("total_input_rows"));
        System.out.println("  Matched:   " + s.get("matched"));
        System.out.println("  Unmatched: " + s.get("unmatched"));
        System.out.println("  Ambiguous: " + s.get("ambiguous"));
        System.out.println("  Rate:      " + s.get("match_rate"));
        System.out.println("CSV rows:    " + s.get("csv_total"));
        System.out.println("  Matched:   " + s.get("csv_matched"));
        System.out.println("  Unmatched: " + s.get("csv_unmatched"));
        System.out.println("  Rate:      " + s.get("csv_match_rate"));
        System.out.println("Report: " + reportPath);
    }

    /** Single-file mode. Python equivalent: the top half of {@code main()}. */
    public void runSingleFile(
            String dataSetName,
            Path mappingCsv,
            Path jsonPath,
            Path outputPathOverride,
            Path outputDirOverride) throws IOException {
        DataSetConfig config = configs.get(dataSetName);
        if (config == null) throw new IllegalArgumentException("Unknown data-set: " + dataSetName);

        Path outputDir = outputDirOverride != null ? outputDirOverride : config.outputDir;
        Path outputPath = outputPathOverride != null
                ? outputPathOverride
                : outputDir.resolve(config.outputPrefix + jsonPath.getFileName().toString());

        List<MappingRow> mappingRows = MappingCsv.load(mappingCsv);
        System.out.println("Loaded " + mappingRows.size() + " CSV mapping rows");

        FileProcessor.Result res = FileProcessor.process(jsonPath, mappingRows, config);
        int siblingCount = SiblingFallback.applyInline(
                res.mappedRows, res.matchedCsvIds, mappingRows, allDataFieldNames());
        JsonIO.writeJson(outputPath, res.mappedRows);

        System.out.println(jsonPath.getFileName() + " -> " + outputPath.getFileName());
        System.out.println("  Total: " + (res.stats.get("total") + siblingCount));
        System.out.println("  Matched: " + res.stats.get("matched"));
        System.out.println("  Sibling fallback: " + siblingCount);
        System.out.println("  Unmatched: " + res.stats.get("unmatched"));
        System.out.println("  Ambiguous: " + res.stats.get("ambiguous"));
    }

    // ---------- CLI ----------

    public static void main(String[] args) throws Exception {
        String dataSet = null;
        Path mappingCsv = null;
        Path inputDir = null;
        Path outputDir = null;
        String jsonPathArg = null;
        String outputArg = null;
        Path projectRoot = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--data-set": dataSet = args[++i]; break;
                case "--mapping-csv": mappingCsv = Paths.get(args[++i]); break;
                case "--input-dir": inputDir = Paths.get(args[++i]); break;
                case "--output-dir": outputDir = Paths.get(args[++i]); break;
                case "--json": jsonPathArg = args[++i]; break;
                case "--output": outputArg = args[++i]; break;
                case "--project-root": projectRoot = Paths.get(args[++i]); break;
                default:
                    System.err.println("Unknown argument: " + a);
                    System.exit(2);
            }
        }
        if (dataSet == null) {
            System.err.println("--data-set is required");
            System.exit(2);
        }
        if (projectRoot == null) {
            // Default: the parent of the java/ dir, matching Python PROJECT_ROOT
            String userDir = System.getProperty("user.dir");
            Path candidate = Paths.get(userDir);
            if (candidate.getFileName() != null && "java".equals(candidate.getFileName().toString())) {
                projectRoot = candidate.getParent();
            } else {
                projectRoot = candidate;
            }
        }
        if (mappingCsv == null) {
            mappingCsv = projectRoot.resolve("config").resolve("보종코드_상품코드_매핑.csv");
        }

        MapProductCode runner = new MapProductCode(projectRoot);
        if (jsonPathArg != null) {
            runner.runSingleFile(
                    dataSet, mappingCsv, Paths.get(jsonPathArg),
                    outputArg != null ? Paths.get(outputArg) : null,
                    outputDir);
        } else {
            runner.runDataset(dataSet, mappingCsv, inputDir, outputDir);
        }
    }
}
