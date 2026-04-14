package com.hanwha.setdata.extract.annuity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanwha.setdata.config.OverridesConfig;
import com.hanwha.setdata.docx.DocxContent;
import com.hanwha.setdata.output.PythonStyleJson;
import com.hanwha.setdata.store.DocxStore;
import com.hanwha.setdata.store.DocxStoreAdapters;
import com.hanwha.setdata.store.SqliteDocxStore;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Main entry point for 보기개시나이 (annuity age) extraction — Java port of
 * {@code extract_annuity_age_v2.py}.
 *
 * <p>Usage mirrors the Python CLI:
 * <pre>
 *   java ... AnnuityAgeExtractor --target-dir 사업방법서_워드 \
 *       --json-dir 상품분류 --output-dir 보기개시나이 \
 *       --overrides config/product_overrides.json
 * </pre>
 */
public final class AnnuityAgeExtractor {

    private final OverridesConfig config;
    private final DocxStore store;

    public AnnuityAgeExtractor(OverridesConfig config, DocxStore store) {
        this.config = config;
        this.store = store;
        AaText.loadFromOverrides(config);
    }

    /**
     * Extract a single docx without pairing. Returns merged records as
     * LinkedHashMap so structured output (nested lists/dicts) is preserved.
     */
    public List<LinkedHashMap<String, Object>> extract(Path docxPath) throws IOException {
        return extract(docxPath, null);
    }

    /**
     * Extract records for a docx paired with a product-classification JSON.
     * When {@code jsonPath} is null, returns an empty list (no rows to merge
     * with).
     */
    public List<LinkedHashMap<String, Object>> extract(Path docxPath, Path jsonPath) throws IOException {
        DocxContent content = DocxStoreAdapters.toDocxContent(store.get(docxPath));
        List<String> lines = content.lines();
        List<List<List<String>>> tables = content.tables();

        List<AaBlocks.Block> blocks = AaBlocks.load(lines, tables);
        List<AaEscalation.Pair> escalations = AaEscalation.extract(lines);

        List<Map<String, Object>> rows = new ArrayList<>();
        if (jsonPath != null && Files.exists(jsonPath)) {
            JsonNode node = new ObjectMapper().readTree(jsonPath.toFile());
            if (node.isArray()) {
                for (JsonNode r : node) {
                    rows.add(jsonToMap(r));
                }
            }
        }

        List<LinkedHashMap<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            results.add(AaMerge.mergeRecords(row, blocks, escalations));
        }
        results = AaMerge.applyOverrides(results, config);
        return results;
    }

    private static Map<String, Object> jsonToMap(JsonNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!node.isObject()) {
            m.put("상품명칭", "");
            m.put("상품명", "");
            return m;
        }
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            if (v.isTextual()) m.put(e.getKey(), v.asText());
            else if (v.isNumber()) m.put(e.getKey(), v.asText());
            else if (v.isBoolean()) m.put(e.getKey(), v.asBoolean());
            else if (v.isNull()) m.put(e.getKey(), "");
            else m.put(e.getKey(), v.toString());
        }
        return m;
    }

    // ── pairing helpers (mirrors gather_pairs) ────────────────────
    public static List<Path[]> gatherPairs(Path docxDir, Path jsonDir) throws IOException {
        Map<String, Path> jsonMap = new HashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jsonDir, "*.json")) {
            for (Path p : stream) {
                jsonMap.put(AaText.normalizeName(p.getFileName().toString()), p);
            }
        }
        List<Path[]> pairs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(docxDir, "*.docx")) {
            for (Path docx : stream) {
                if (docx.getFileName().toString().startsWith("~$")) continue;
                String key = AaText.normalizeName(docx.getFileName().toString());
                Path json = jsonMap.get(key);
                if (json != null) pairs.add(new Path[]{docx, json});
            }
        }
        pairs.sort(Comparator.comparing(p -> p[1].getFileName().toString()));
        return pairs;
    }

    // ── CLI ────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        Path targetDir = null;
        Path jsonDir = null;
        Path outputDir = null;
        Path overridesPath = null;
        Path cur = root;
        for (int i = 0; i < 5; i++) {
            Path candidate = cur.resolve("사업방법서_워드");
            if (Files.isDirectory(candidate)) {
                targetDir = candidate;
                jsonDir = cur.resolve("상품분류");
                outputDir = cur.resolve("보기개시나이");
                overridesPath = cur.resolve("config").resolve("product_overrides.json");
                break;
            }
            if (cur.getParent() == null) break;
            cur = cur.getParent();
        }

        String singleDocx = null;
        String singleJson = null;
        String singleOutput = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--docx": if (i + 1 < args.length) singleDocx = args[++i]; break;
                case "--json": if (i + 1 < args.length) singleJson = args[++i]; break;
                case "--output": if (i + 1 < args.length) singleOutput = args[++i]; break;
                case "--target-dir":
                case "--docx-dir": if (i + 1 < args.length) targetDir = Paths.get(args[++i]); break;
                case "--json-dir": if (i + 1 < args.length) jsonDir = Paths.get(args[++i]); break;
                case "--output-dir": if (i + 1 < args.length) outputDir = Paths.get(args[++i]); break;
                case "--overrides": if (i + 1 < args.length) overridesPath = Paths.get(args[++i]); break;
                default: break;
            }
        }

        OverridesConfig cfg = (overridesPath != null && Files.exists(overridesPath))
                ? OverridesConfig.load(overridesPath) : null;
        DocxStore store = new SqliteDocxStore(targetDir.getParent().resolve("cache/docx_cache.db"));
        AnnuityAgeExtractor extractor = new AnnuityAgeExtractor(cfg, store);

        if (singleDocx != null && singleJson != null) {
            Path docx = Paths.get(singleDocx);
            Path json = Paths.get(singleJson);
            Path out = singleOutput != null
                    ? Paths.get(singleOutput)
                    : (outputDir == null ? Paths.get(json.getFileName().toString())
                                         : outputDir.resolve(json.getFileName()));
            List<LinkedHashMap<String, Object>> rows = extractor.extract(docx, json);
            PythonStyleJson.writeFile(out, rows);
            System.out.printf("%s + %s -> %s (%d items)%n",
                    docx.getFileName(), json.getFileName(), out.getFileName(), rows.size());
            return;
        }

        if (targetDir == null || jsonDir == null || outputDir == null) {
            System.err.println("missing dirs: target=" + targetDir + " json=" + jsonDir + " out=" + outputDir);
            System.exit(1);
        }

        Files.createDirectories(outputDir);
        List<Path[]> pairs = gatherPairs(targetDir, jsonDir);
        List<Map<String, Object>> reportItems = new ArrayList<>();

        for (Path[] pair : pairs) {
            Path docx = pair[0];
            Path json = pair[1];
            List<LinkedHashMap<String, Object>> rows = extractor.extract(docx, json);
            Path out = outputDir.resolve(json.getFileName().toString());
            PythonStyleJson.writeFile(out, rows);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("file", json.getFileName().toString());
            item.put("rows", rows);
            reportItems.add(item);
        }

        // report.json
        Map<String, Object> report = makeReport(reportItems);
        Path reportPath = outputDir.resolve("report.json");
        PythonStyleJson.writeFile(reportPath, report);

        int totalRows = 0;
        for (Map<String, Object> it : reportItems) {
            @SuppressWarnings("unchecked")
            List<Object> rs = (List<Object>) it.get("rows");
            if (rs != null) totalRows += rs.size();
        }
        System.out.printf("processed_files=%d%n", pairs.size());
        System.out.printf("processed_rows=%d%n", totalRows);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> makeReport(List<Map<String, Object>> items) {
        List<Map<String, Object>> fileStats = new ArrayList<>();
        int totalRows = 0;
        int totalResults = 0;
        for (Map<String, Object> item : items) {
            String fileName = (String) item.get("file");
            List<LinkedHashMap<String, Object>> rows =
                    (List<LinkedHashMap<String, Object>>) item.get("rows");
            int countRows = rows == null ? 0 : rows.size();
            int countResults = 0;
            if (rows != null) {
                for (LinkedHashMap<String, Object> row : rows) {
                    List<LinkedHashMap<String, Object>> entries =
                            (List<LinkedHashMap<String, Object>>) row.get("보기개시나이정보");
                    if (entries == null) continue;
                    for (LinkedHashMap<String, Object> entry : entries) {
                        if (hasValueEntry(entry)) countResults++;
                    }
                }
            }
            totalRows += countRows;
            totalResults += countResults;
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("파일명", fileName);
            stat.put("입력_상품수", countRows);
            stat.put("보기개시나이항목수", countResults);
            fileStats.add(stat);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("총_파일수", items.size());
        report.put("총_입력_상품수", totalRows);
        report.put("총_보기개시나이항목수", totalResults);
        report.put("파일별_요약", fileStats);
        return report;
    }

    private static boolean hasValueEntry(Map<String, Object> entry) {
        if (entry == null) return false;
        for (String k : new String[]{"제1보기개시나이", "제2보기개시나이", "제3보기개시나이"}) {
            Object v = entry.get(k);
            if (v != null && !v.toString().isEmpty()) return true;
        }
        return false;
    }
}
