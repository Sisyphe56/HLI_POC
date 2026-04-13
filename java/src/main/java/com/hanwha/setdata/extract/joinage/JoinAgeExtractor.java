package com.hanwha.setdata.extract.joinage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanwha.setdata.config.OverridesConfig;
import com.hanwha.setdata.docx.DocxContent;
import com.hanwha.setdata.docx.DocxReader;
import com.hanwha.setdata.output.PythonStyleJson;

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
 * Java port of {@code extract_join_age_v2.py}. Produces 가입가능나이 records per
 * product classification row.
 */
public final class JoinAgeExtractor {

    private final OverridesConfig config;
    private final DocxReader docxReader;
    private Path periodDir;

    public JoinAgeExtractor(OverridesConfig config) {
        this.config = config;
        this.docxReader = new DocxReader();
    }

    public void setPeriodDir(Path periodDir) { this.periodDir = periodDir; }

    public List<LinkedHashMap<String, Object>> extract(Path docxPath, Path jsonPath) throws IOException {
        DocxContent content = docxReader.read(docxPath);
        List<String> lines = content.lines();
        List<List<List<String>>> tables = content.tables();
        List<String> sections = content.tableSections();

        // table_section_filter
        String section = config == null ? "" : config.tableSectionFilter(docxPath.getFileName().toString());
        List<List<List<String>>> tablesForExtract = tables;
        if (section != null && !section.isEmpty()) {
            List<List<List<String>>> filtered = new ArrayList<>();
            for (int i = 0; i < tables.size(); i++) {
                if (i < sections.size() && section.equals(sections.get(i))) filtered.add(tables.get(i));
            }
            if (!filtered.isEmpty()) tablesForExtract = filtered;
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        if (jsonPath != null && Files.exists(jsonPath)) {
            JsonNode node = new ObjectMapper().readTree(jsonPath.toFile());
            if (node.isArray()) {
                for (JsonNode r : node) rows.add(jsonToMap(r));
            }
        }

        List<Map<String, Object>> periodData = loadPeriodData(jsonPath);

        List<LinkedHashMap<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            results.add(JaMerge.merge(row, lines, tablesForExtract, periodData, config));
        }
        return results;
    }

    private List<Map<String, Object>> loadPeriodData(Path jsonPath) throws IOException {
        if (periodDir == null || jsonPath == null) return new ArrayList<>();
        Path p = periodDir.resolve(jsonPath.getFileName().toString());
        if (!Files.exists(p)) return new ArrayList<>();
        JsonNode node = new ObjectMapper().readTree(p.toFile());
        List<Map<String, Object>> list = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode rec : node) list.add(jsonToMapDeep(rec));
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private static Object jsonToAny(JsonNode n) {
        if (n == null || n.isNull()) return "";
        if (n.isTextual()) return n.asText();
        if (n.isNumber()) return n.asText();
        if (n.isBoolean()) return n.asBoolean();
        if (n.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode c : n) list.add(jsonToAny(c));
            return list;
        }
        if (n.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                m.put(e.getKey(), jsonToAny(e.getValue()));
            }
            return m;
        }
        return n.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonToMapDeep(JsonNode node) {
        Object obj = jsonToAny(node);
        return obj instanceof Map ? (Map<String, Object>) obj : new LinkedHashMap<>();
    }

    private static Map<String, Object> jsonToMap(JsonNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!node.isObject()) return m;
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

    // ── pairing ────────────────────────────────────────────
    public static List<Path[]> gatherPairs(Path docxDir, Path jsonDir) throws IOException {
        Map<String, Path> jsonMap = new HashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jsonDir, "*.json")) {
            for (Path p : stream) {
                jsonMap.put(JaText.normalizeName(p.getFileName().toString()), p);
            }
        }
        List<Path[]> pairs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(docxDir, "*.docx")) {
            for (Path docx : stream) {
                if (docx.getFileName().toString().startsWith("~$")) continue;
                String key = JaText.normalizeName(docx.getFileName().toString());
                Path json = jsonMap.get(key);
                if (json != null) pairs.add(new Path[]{docx, json});
            }
        }
        pairs.sort(Comparator.comparing(p -> p[1].getFileName().toString()));
        return pairs;
    }

    // ── CLI ────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        Path targetDir = null;
        Path jsonDir = null;
        Path outputDir = null;
        Path overridesPath = null;
        Path periodDir = null;
        Path cur = root;
        for (int i = 0; i < 5; i++) {
            Path candidate = cur.resolve("사업방법서_워드");
            if (Files.isDirectory(candidate)) {
                targetDir = candidate;
                jsonDir = cur.resolve("상품분류");
                outputDir = cur.resolve("가입가능나이");
                overridesPath = cur.resolve("config").resolve("product_overrides.json");
                periodDir = cur.resolve("가입가능보기납기");
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
                case "--period-dir": if (i + 1 < args.length) periodDir = Paths.get(args[++i]); break;
                default: break;
            }
        }

        OverridesConfig cfg = (overridesPath != null && Files.exists(overridesPath))
                ? OverridesConfig.load(overridesPath) : null;
        JoinAgeExtractor extractor = new JoinAgeExtractor(cfg);
        extractor.setPeriodDir(periodDir);

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

        int totalRows = 0;
        int rowsWithAges = 0;
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
            totalRows += rows.size();
            int ageCount = 0;
            for (LinkedHashMap<String, Object> r : rows) {
                Object ages = r.get("가입가능나이");
                if (ages instanceof List && !((List<?>) ages).isEmpty()) { rowsWithAges++; ageCount++; }
            }
            System.out.printf("Processed: %s (%d items, %d with ages)%n",
                    json.getFileName(), rows.size(), ageCount);
        }

        // report.json
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("total_files", pairs.size());
        report.put("total_rows", totalRows);
        report.put("rows_with_age_data", rowsWithAges);
        report.put("rows_without_age_data", totalRows - rowsWithAges);
        Path reportPath = outputDir.resolve("report.json");
        PythonStyleJson.writeFile(reportPath, report);

        System.out.printf("processed_files=%d%n", pairs.size());
        System.out.printf("processed_rows=%d%n", totalRows);
    }
}
