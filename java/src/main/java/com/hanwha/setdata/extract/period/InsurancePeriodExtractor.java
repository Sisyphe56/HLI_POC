package com.hanwha.setdata.extract.period;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanwha.setdata.config.OverridesConfig;
import com.hanwha.setdata.docx.DocxContent;
import com.hanwha.setdata.docx.DocxReader;
import com.hanwha.setdata.model.ProductRecord;
import com.hanwha.setdata.output.PythonStyleJson;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Main entry point for 가입가능보기납기 extraction — Java port of
 * {@code extract_insurance_period_v2.py}.
 */
public final class InsurancePeriodExtractor {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DocxReader DOCX_READER = new DocxReader();

    private final OverridesConfig config;

    public InsurancePeriodExtractor(OverridesConfig config) {
        this.config = config;
    }

    /**
     * Extract with paired classification JSON (list of row dicts) and return
     * the fully-processed period rows.
     */
    public List<Map<String, Object>> extract(Path docxPath, Path classificationJsonPath) throws IOException {
        DocxContent content = DOCX_READER.read(docxPath);

        List<Map<String, Object>> rows = loadRows(classificationJsonPath);

        List<List<List<String>>> tablesForExtract = content.tables();
        String section = config == null ? "" : config.tableSectionFilter(docxPath.getFileName().toString());
        if (section != null && !section.isEmpty()) {
            List<List<List<String>>> filtered = new ArrayList<>();
            for (int i = 0; i < content.tables().size(); i++) {
                if (section.equals(content.tableSections().get(i))) {
                    filtered.add(content.tables().get(i));
                }
            }
            if (!filtered.isEmpty()) tablesForExtract = filtered;
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            results.add(IpRecordBuilder.mergePeriodInfo(row, content.lines(), tablesForExtract));
        }
        return IpOverrides.applyPeriodOverrides(results, config);
    }

    /**
     * Convenience: implements the ProductRecord-list contract, stringifying the
     * top-level output for sample usage. Primary API is
     * {@link #extract(Path, Path)}.
     */
    public List<ProductRecord> extract(Path docxPath) {
        throw new UnsupportedOperationException(
                "Use extract(docxPath, classificationJsonPath) — period extraction requires paired 상품분류 JSON");
    }

    private static List<Map<String, Object>> loadRows(Path jsonPath) throws IOException {
        if (!Files.exists(jsonPath)) return new ArrayList<>();
        JsonNode root = JSON.readTree(jsonPath.toFile());
        List<Map<String, Object>> out = new ArrayList<>();
        if (root == null || !root.isArray()) return out;
        for (JsonNode node : root) {
            if (!node.isObject()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                if (v.isTextual()) m.put(e.getKey(), v.asText());
                else if (v.isNumber()) m.put(e.getKey(), v.numberValue());
                else if (v.isBoolean()) m.put(e.getKey(), v.booleanValue());
                else m.put(e.getKey(), v.asText());
            }
            out.add(m);
        }
        return out;
    }

    private static Map<String, Path> indexJsonByNormalizedName(Path jsonDir) throws IOException {
        Map<String, Path> map = new LinkedHashMap<>();
        if (!Files.isDirectory(jsonDir)) return map;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jsonDir, "*.json")) {
            for (Path p : stream) {
                map.put(IpText.normalizeName(p.getFileName().toString()), p);
            }
        }
        return map;
    }

    // ── CLI ───────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        Path targetDir = null, outputDir = null, overridesPath = null, jsonDir = null;
        Path cur = root;
        for (int i = 0; i < 5; i++) {
            Path candidate = cur.resolve("사업방법서_워드");
            if (Files.isDirectory(candidate)) {
                targetDir = candidate;
                outputDir = cur.resolve("가입가능보기납기");
                overridesPath = cur.resolve("config").resolve("product_overrides.json");
                jsonDir = cur.resolve("상품분류");
                break;
            }
            if (cur.getParent() == null) break;
            cur = cur.getParent();
        }

        String singleDocx = null, singleOutput = null, singleJson = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--docx" -> singleDocx = args[++i];
                case "--json" -> singleJson = args[++i];
                case "--output" -> singleOutput = args[++i];
                case "--target-dir" -> targetDir = Paths.get(args[++i]);
                case "--output-dir" -> outputDir = Paths.get(args[++i]);
                case "--overrides" -> overridesPath = Paths.get(args[++i]);
                case "--json-dir" -> jsonDir = Paths.get(args[++i]);
                default -> {}
            }
        }

        OverridesConfig cfg = (overridesPath != null && Files.exists(overridesPath))
                ? OverridesConfig.load(overridesPath) : null;
        InsurancePeriodExtractor extractor = new InsurancePeriodExtractor(cfg);

        if (outputDir != null) Files.createDirectories(outputDir);

        if (singleDocx != null && singleJson != null) {
            Path docxPath = Paths.get(singleDocx);
            Path jsonPath = Paths.get(singleJson);
            List<Map<String, Object>> rows = extractor.extract(docxPath, jsonPath);
            Path outPath = singleOutput != null ? Paths.get(singleOutput)
                    : (outputDir != null ? outputDir.resolve(jsonPath.getFileName()) : Paths.get(jsonPath.getFileName().toString()));
            PythonStyleJson.writeFile(outPath, rows);
            System.out.printf("%s + %s -> %s (%d items)%n",
                    docxPath.getFileName(), jsonPath.getFileName(), outPath.getFileName(), rows.size());
            return;
        }

        if (targetDir == null || !Files.isDirectory(targetDir)) {
            System.err.println("target dir not found: " + targetDir);
            System.exit(1);
        }
        if (jsonDir == null || !Files.isDirectory(jsonDir)) {
            System.err.println("json dir not found: " + jsonDir);
            System.exit(1);
        }

        Map<String, Path> jsonIndex = indexJsonByNormalizedName(jsonDir);

        // Gather docx files, skipping lock files
        List<Path> docxFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetDir, "*.docx")) {
            for (Path p : stream) {
                if (p.getFileName().toString().startsWith("~$")) continue;
                docxFiles.add(p);
            }
        }

        // Build pairs (docx, json) keyed by jsonPath name for stable sort matching Python
        List<Map.Entry<Path, Path>> pairs = new ArrayList<>();
        for (Path docx : docxFiles) {
            String key = IpText.normalizeName(docx.getFileName().toString());
            Path jsonPath = jsonIndex.get(key);
            if (jsonPath != null) pairs.add(Map.entry(docx, jsonPath));
        }
        pairs.sort((a, b) -> a.getValue().getFileName().toString().compareTo(b.getValue().getFileName().toString()));

        List<Map<String, Object>> reportItems = new ArrayList<>();

        for (Map.Entry<Path, Path> pair : pairs) {
            Path docx = pair.getKey();
            Path jsonPath = pair.getValue();
            List<Map<String, Object>> resultRows = extractor.extract(docx, jsonPath);
            Path outPath = outputDir.resolve(jsonPath.getFileName());
            PythonStyleJson.writeFile(outPath, resultRows);
            System.out.printf("Processed: %s -> %s (%d items)%n",
                    jsonPath.getFileName(), outPath.getFileName(), resultRows.size());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("file", jsonPath.getFileName().toString());
            item.put("rows", resultRows);
            reportItems.add(item);
        }

        // Write report.json (mirrors Python make_report)
        Map<String, Object> report = makeReport(reportItems);
        Path reportPath = outputDir.resolve("report.json");
        PythonStyleJson.writeFile(reportPath, report);

        System.out.printf("processed_files=%d%n", pairs.size());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> makeReport(List<Map<String, Object>> items) {
        List<Map<String, Object>> fileStats = new ArrayList<>();
        int totalRows = 0, totalResults = 0;
        for (Map<String, Object> item : items) {
            List<Map<String, Object>> rows = (List<Map<String, Object>>) item.getOrDefault("rows", new ArrayList<>());
            int countRows = rows.size();
            int countResults = 0;
            for (Map<String, Object> r : rows) {
                Object entries = r.get("가입가능보기납기");
                if (entries instanceof List) {
                    for (Object e : (List<?>) entries) {
                        if (!(e instanceof Map<?, ?> m)) continue;
                        Object ins = m.get("보험기간");
                        Object pay = m.get("납입기간");
                        String insP = ins == null ? "" : String.valueOf(ins);
                        String payP = pay == null ? "" : String.valueOf(pay);
                        if (!insP.isEmpty() || !payP.isEmpty()) countResults++;
                    }
                }
            }
            totalRows += countRows;
            totalResults += countResults;
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("파일명", item.get("file"));
            stat.put("입력_상품수", countRows);
            stat.put("가입가능보기납기항목수", countResults);
            fileStats.add(stat);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("총_파일수", items.size());
        report.put("총_입력_상품수", totalRows);
        report.put("총_가입가능보기납기항목수", totalResults);
        report.put("파일별_요약", fileStats);
        return report;
    }
}
