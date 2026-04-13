package com.hanwha.setdata.extract.cycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanwha.setdata.config.OverridesConfig;
import com.hanwha.setdata.extract.ProductClassificationExtractor;
import com.hanwha.setdata.model.ProductRecord;
import com.hanwha.setdata.output.PythonStyleJson;
import com.hanwha.setdata.util.Normalizer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Main entry point for the 납입주기 pipeline — Java port of
 * {@code extract_payment_cycle_v2.py}.
 *
 * <p>Composes {@link ProductClassificationExtractor} (for base records),
 * {@link PcCycleDocx}, {@link CycleRuleParser}, and the JSON overrides loader.
 */
public final class PaymentCycleExtractor {

    /** Record type used for output: preserves insertion order, may contain
     *  a {@code 납입주기} list of {@code LinkedHashMap<String,String>} items. */
    public static final class PaymentCycleRecord extends LinkedHashMap<String, Object> {
        public PaymentCycleRecord() {}
        public PaymentCycleRecord(Map<String, ?> src) { super(src); }
    }

    private final OverridesConfig config;
    private final ProductClassificationExtractor classificationExtractor;
    private final List<String> detailContextTokens;

    public PaymentCycleExtractor(OverridesConfig config, List<String> detailContextTokens) {
        this.config = config;
        this.classificationExtractor = new ProductClassificationExtractor(config);
        this.detailContextTokens = detailContextTokens == null ? List.of() : detailContextTokens;
    }

    /** Python: extract_payment_cycle_for_docx + _apply_cycle_overrides. */
    public List<PaymentCycleRecord> extract(Path docxPath) throws IOException {
        List<ProductRecord> baseRecords = classificationExtractor.extract(docxPath);
        if (baseRecords.isEmpty()) return new ArrayList<>();

        String section = config != null ? config.tableSectionFilter(docxPath.getFileName().toString()) : "";

        PcCycleDocx.Result docx = PcCycleDocx.read(docxPath);
        CycleRuleParser.ExtractResult ex = CycleRuleParser.extractCycleRules(
                docx, detailContextTokens, section);

        List<PaymentCycleRecord> enriched = enrichRecordsWithCycles(
                baseRecords, ex.rules(), ex.defaultCycles());
        return applyCycleOverrides(enriched);
    }

    // ── enrichment ───────────────────────────────────────────────

    /** Python: match_record_context. */
    private static boolean matchRecordContext(Map<String, Object> record, CycleRule rule) {
        if (rule.contexts.isEmpty()) return true;
        StringBuilder recordTokens = new StringBuilder();
        for (Map.Entry<String, Object> e : record.entrySet()) {
            if (e.getKey().startsWith("세부종목") && e.getValue() instanceof String s) {
                if (recordTokens.length() > 0) recordTokens.append(' ');
                recordTokens.append(s);
            }
        }
        String recordNorm = PcCycleText.normalizeMatchKey(recordTokens.toString());
        if (recordNorm.isEmpty()) return false;
        for (String ctx : rule.contexts) {
            if (!recordNorm.contains(PcCycleText.normalizeMatchKey(ctx))) return false;
        }
        return true;
    }

    /** Python: pick_cycles. */
    private static List<Map<String, String>> pickCycles(
            Map<String, Object> record,
            List<CycleRule> rules,
            List<String> fallbackCycles) {
        TreeMap<Integer, List<String>> matched = new TreeMap<>();
        for (CycleRule r : rules) {
            if (!matchRecordContext(record, r)) continue;
            List<String> existing = matched.computeIfAbsent(r.priority, k -> new ArrayList<>());
            for (String c : r.cycles) if (!existing.contains(c)) existing.add(c);
        }

        List<String> chosen = new ArrayList<>();
        if (!matched.isEmpty()) {
            int maxPriority = matched.lastKey();
            if (maxPriority > 0) {
                chosen = matched.get(maxPriority);
            } else {
                for (String c : fallbackCycles) if (!chosen.contains(c)) chosen.add(c);
            }
        } else {
            for (String c : fallbackCycles) if (!chosen.contains(c)) chosen.add(c);
        }

        List<String> ordered = new ArrayList<>();
        for (String code : PcCycleText.CYCLE_ORDER) {
            String target = switch (code) {
                case "0" -> "일시납";
                case "1" -> "월납";
                case "3" -> "3개월납";
                case "6" -> "6개월납";
                default -> "연납";
            };
            if (chosen.contains(target) && !ordered.contains(target)) ordered.add(target);
        }
        return PcCycleText.cycleRecordsFromNames(ordered);
    }

    private static List<Map<String, String>> dedupeCycles(List<Map<String, String>> items) {
        List<Map<String, String>> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Map<String, String> it : items) {
            String key = it.getOrDefault("납입주기명", "") + "\u0001" + it.getOrDefault("납입주기값", "");
            if (seen.add(key)) out.add(it);
        }
        return out;
    }

    /** Python: enrich_records_with_cycles. */
    private List<PaymentCycleRecord> enrichRecordsWithCycles(
            List<ProductRecord> records,
            List<CycleRule> rules,
            List<String> defaultCycles) {

        List<CycleRule> normalized = new ArrayList<>(rules);
        // stable sort by priority desc
        normalized.sort((a, b) -> Integer.compare(b.priority, a.priority));

        List<String> fallbackCycles = new ArrayList<>();
        for (CycleRule r : normalized) {
            if (r.priority != 0) continue;
            for (String c : r.cycles) if (!fallbackCycles.contains(c)) fallbackCycles.add(c);
        }

        List<PaymentCycleRecord> out = new ArrayList<>();
        for (ProductRecord rec : records) {
            PaymentCycleRecord item = new PaymentCycleRecord();
            for (Map.Entry<String, String> e : rec.entrySet()) item.put(e.getKey(), e.getValue());

            List<Map<String, String>> cycles = pickCycles(item, normalized, fallbackCycles);
            List<Map<String, String>> resolved;
            if (!cycles.isEmpty()) {
                resolved = cycles;
            } else if (defaultCycles != null && !defaultCycles.isEmpty()) {
                resolved = PcCycleText.cycleRecordsFromNames(defaultCycles);
            } else {
                resolved = new ArrayList<>();
            }
            item.put("납입주기", dedupeCycles(resolved));
            out.add(item);
        }

        out.sort((a, b) -> {
            String an = strOr(a.get("상품명칭"));
            String bn = strOr(b.get("상품명칭"));
            int c = an.compareTo(bn);
            if (c != 0) return c;
            return strOr(a.get("상품명")).compareTo(strOr(b.get("상품명")));
        });
        return out;
    }

    private static String strOr(Object v) {
        return v instanceof String s ? s : "";
    }

    // ── overrides ────────────────────────────────────────────────

    /** Python: _apply_cycle_overrides. */
    @SuppressWarnings("unchecked")
    private List<PaymentCycleRecord> applyCycleOverrides(List<PaymentCycleRecord> records) {
        if (config == null) return records;
        JsonNode pcOverrides = config.section("payment_cycle");
        JsonNode siblingCfg = config.section("sibling_fallback");
        JsonNode suffixPatternsNode = siblingCfg == null ? null : siblingCfg.get("suffix_patterns");
        List<String> suffixPatterns = new ArrayList<>();
        if (suffixPatternsNode != null && suffixPatternsNode.isArray()) {
            for (JsonNode n : suffixPatternsNode) suffixPatterns.add(n.asText());
        }

        // Build lookup: 상품명 → 납입주기 (list of maps)
        LinkedHashMap<String, List<Map<String, String>>> nameToCycles = new LinkedHashMap<>();
        for (PaymentCycleRecord r : records) {
            String name = strOr(r.get("상품명"));
            Object cycles = r.get("납입주기");
            nameToCycles.put(name,
                    cycles instanceof List<?> l ? (List<Map<String, String>>) l : new ArrayList<>());
        }

        List<Map.Entry<String, JsonNode>> pcEntries = new ArrayList<>();
        if (pcOverrides != null && pcOverrides.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = pcOverrides.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().startsWith("_")) continue;
                pcEntries.add(e);
            }
        }

        for (PaymentCycleRecord r : records) {
            String productName = strOr(r.get("상품명"));
            List<Map<String, String>> cycles =
                    r.get("납입주기") instanceof List<?> l ? (List<Map<String, String>>) l : new ArrayList<>();
            boolean applied = false;

            for (Map.Entry<String, JsonNode> entry : pcEntries) {
                String key = entry.getKey();
                JsonNode cfg = entry.getValue();
                String[] keywords = key.split("\\+");
                boolean allMatch = true;
                for (String kw : keywords) {
                    if (!productName.contains(kw)) { allMatch = false; break; }
                }
                if (!allMatch) continue;

                String action = cfg.path("action").asText("fixed");
                boolean isForce = cfg.path("force").asBoolean(false);

                if (!isForce && !cycles.isEmpty()) continue;

                if ("fixed".equals(action)) {
                    JsonNode cyclesNode = cfg.path("cycles");
                    List<Map<String, String>> fixed = jsonNodeToCycleList(cyclesNode);
                    r.put("납입주기", fixed);
                    applied = true;
                } else if ("sibling_filter".equals(action)) {
                    List<String> matchKws = jsonArrayToStringList(cfg.path("sibling_match"));
                    List<String> excludeKws = jsonArrayToStringList(cfg.path("sibling_exclude"));
                    String filterVal = cfg.has("filter_cycle_value")
                            ? cfg.path("filter_cycle_value").asText("") : null;
                    for (Map.Entry<String, List<Map<String, String>>> e : nameToCycles.entrySet()) {
                        String name = e.getKey();
                        List<Map<String, String>> cdata = e.getValue();
                        boolean matchAll = true;
                        for (String m : matchKws) if (!name.contains(m)) { matchAll = false; break; }
                        if (!matchAll) continue;
                        boolean anyExcl = false;
                        for (String ex : excludeKws) if (name.contains(ex)) { anyExcl = true; break; }
                        if (anyExcl) continue;
                        if (cdata == null || cdata.isEmpty()) continue;
                        if (filterVal != null && !filterVal.isEmpty()) {
                            List<Map<String, String>> filtered = new ArrayList<>();
                            for (Map<String, String> c : cdata) {
                                if (filterVal.equals(c.get("납입주기값"))) filtered.add(c);
                            }
                            r.put("납입주기", filtered.isEmpty() ? new ArrayList<>(cdata) : filtered);
                        } else {
                            r.put("납입주기", new ArrayList<>(cdata));
                        }
                        applied = true;
                        break;
                    }
                } else if ("sibling_copy".equals(action)) {
                    List<String> matchKws = jsonArrayToStringList(cfg.path("sibling_match"));
                    List<String> anyKws = jsonArrayToStringList(cfg.path("sibling_any"));
                    for (Map.Entry<String, List<Map<String, String>>> e : nameToCycles.entrySet()) {
                        String name = e.getKey();
                        List<Map<String, String>> cdata = e.getValue();
                        boolean matchAll = true;
                        for (String m : matchKws) if (!name.contains(m)) { matchAll = false; break; }
                        if (!matchAll) continue;
                        boolean anyMatch = false;
                        for (String a : anyKws) if (name.contains(a)) { anyMatch = true; break; }
                        if (!anyMatch) continue;
                        if (cdata == null || cdata.isEmpty()) continue;
                        r.put("납입주기", new ArrayList<>(cdata));
                        applied = true;
                        break;
                    }
                }

                if (applied) break;
            }

            if (!applied) {
                Object currentCycles = r.get("납입주기");
                boolean isEmpty = !(currentCycles instanceof List<?> l) || l.isEmpty();
                if (isEmpty) {
                    for (String pattern : suffixPatterns) {
                        String[] parts = pattern.split("\\|", -1);
                        if (parts.length != 2) continue;
                        String src = parts[0], dst = parts[1];
                        if (productName.contains(src)) {
                            String baseName = productName.replace(src, dst);
                            List<Map<String, String>> baseData = nameToCycles.get(baseName);
                            if (baseData != null && !baseData.isEmpty()) {
                                r.put("납입주기", new ArrayList<>(baseData));
                                break;
                            }
                        }
                    }
                }
            }
        }
        return records;
    }

    private static List<Map<String, String>> jsonNodeToCycleList(JsonNode arr) {
        List<Map<String, String>> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode item : arr) {
            LinkedHashMap<String, String> m = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = item.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                m.put(e.getKey(), e.getValue().asText());
            }
            out.add(m);
        }
        return out;
    }

    private static List<String> jsonArrayToStringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) out.add(n.asText());
        return out;
    }

    // ── detail context tokens loader ────────────────────────────

    private static final Pattern DETAIL_KEY_RE = Pattern.compile("세부종목\\d+");

    /** Python: load_detail_context_tokens. */
    public static List<String> loadDetailContextTokens(Path sourceDir) throws IOException {
        if (sourceDir == null || !Files.isDirectory(sourceDir)) return List.of();
        ObjectMapper mapper = new ObjectMapper();
        List<String> tokens = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();

        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sourceDir, "*.json")) {
            for (Path p : stream) paths.add(p);
        }
        Collections.sort(paths);

        for (Path path : paths) {
            JsonNode rows;
            try {
                rows = mapper.readTree(path.toFile());
            } catch (Exception ex) {
                continue;
            }
            if (rows == null || !rows.isArray()) continue;
            for (JsonNode row : rows) {
                if (!row.isObject()) continue;
                Iterator<Map.Entry<String, JsonNode>> it = row.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (!DETAIL_KEY_RE.matcher(e.getKey()).matches()) continue;
                    JsonNode v = e.getValue();
                    if (!v.isTextual()) continue;
                    String text = Normalizer.normalizeWs(v.asText());
                    if (text.isEmpty()) continue;
                    String compact = PcCycleText.normalizeMatchKey(text);
                    if (!compact.isEmpty() && seen.add(compact)) tokens.add(text);
                }
            }
        }
        return tokens;
    }

    // ── CLI ──────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        Path targetDir = null, outputDir = null, overridesPath = null, productMetaDir = null;
        Path cur = root;
        for (int i = 0; i < 5; i++) {
            Path candidate = cur.resolve("사업방법서_워드");
            if (Files.isDirectory(candidate)) {
                targetDir = candidate;
                outputDir = cur.resolve("납입주기");
                overridesPath = cur.resolve("config").resolve("product_overrides.json");
                Path sgk = cur.resolve("상품구분");
                productMetaDir = Files.isDirectory(sgk) ? sgk : cur.resolve("상품분류");
                break;
            }
            if (cur.getParent() == null) break;
            cur = cur.getParent();
        }

        String singleDocx = null;
        String singleOutput = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--docx" -> singleDocx = args[++i];
                case "--output" -> singleOutput = args[++i];
                case "--target-dir" -> targetDir = Paths.get(args[++i]);
                case "--output-dir" -> outputDir = Paths.get(args[++i]);
                case "--overrides" -> overridesPath = Paths.get(args[++i]);
                case "--product-meta-dir" -> productMetaDir = Paths.get(args[++i]);
                default -> {}
            }
        }

        OverridesConfig cfg = (overridesPath != null && Files.exists(overridesPath))
                ? OverridesConfig.load(overridesPath) : null;
        // Default product meta dir relative to overrides
        if (productMetaDir == null && overridesPath != null) {
            Path base = overridesPath.getParent() == null ? null : overridesPath.getParent().getParent();
            if (base != null) {
                Path sgk = base.resolve("상품구분");
                productMetaDir = Files.isDirectory(sgk) ? sgk : base.resolve("상품분류");
            }
        }
        List<String> detailTokens = loadDetailContextTokens(productMetaDir);

        PaymentCycleExtractor extractor = new PaymentCycleExtractor(cfg, detailTokens);

        List<Path> docs = new ArrayList<>();
        if (singleDocx != null) {
            docs.add(Paths.get(singleDocx));
        } else {
            if (targetDir == null || !Files.isDirectory(targetDir)) {
                System.err.println("target dir not found: " + targetDir);
                System.exit(1);
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetDir, "*.docx")) {
                for (Path p : stream) {
                    if (p.getFileName().toString().startsWith("~$")) continue;
                    docs.add(p);
                }
            }
            Collections.sort(docs);
        }

        if (outputDir != null) Files.createDirectories(outputDir);

        List<Map.Entry<String, PaymentCycleRecord>> allRecords = new ArrayList<>();
        for (Path docx : docs) {
            List<PaymentCycleRecord> records = extractor.extract(docx);
            String outStem = ProductClassificationExtractor.makeOutputStem(docx.getFileName().toString());
            Path outPath;
            if (singleOutput != null && singleDocx != null) {
                outPath = Paths.get(singleOutput);
            } else {
                outPath = outputDir.resolve(outStem + ".json");
            }
            PythonStyleJson.writeFile(outPath, records);
            System.out.printf("%s -> %s (%d items)%n",
                    docx.getFileName(), outPath.getFileName(), records.size());
            for (PaymentCycleRecord rec : records) {
                allRecords.add(Map.entry(docx.getFileName().toString(), rec));
            }
        }

        // Mapping report
        Path reportPath = (outputDir != null ? outputDir : Paths.get("."))
                .resolve("mapping_report_paym_cycl.json");
        PythonStyleJson.writeFile(reportPath, buildPaymentCycleReport(allRecords));
        System.out.printf("[완료] 납입주기 매핑 리포트: %s%n", reportPath);
    }

    // ── report ───────────────────────────────────────────────────

    private static String classifyCycleStatus(PaymentCycleRecord item) {
        Object cycles = item.get("납입주기");
        if (!(cycles instanceof List<?> l)) return "매핑안됨";
        if (l.isEmpty()) return "매핑안됨";
        if (l.size() == 1) return "매핑완료(단일)";
        return "매핑완료(다중)";
    }

    private static String formatRecordName(PaymentCycleRecord rec) {
        String name = strOr(rec.get("상품명"));
        if (!name.isEmpty()) return name;
        return strOr(rec.get("상품명칭"));
    }

    private static LinkedHashMap<String, Object> buildPaymentCycleReport(
            List<Map.Entry<String, PaymentCycleRecord>> allRecords) {
        LinkedHashMap<String, List<String>> categories = new LinkedHashMap<>();
        categories.put("매핑완료(단일)", new ArrayList<>());
        categories.put("매핑완료(다중)", new ArrayList<>());
        categories.put("매핑안됨", new ArrayList<>());

        LinkedHashMap<String, LinkedHashMap<String, Integer>> fileSummary = new LinkedHashMap<>();

        for (Map.Entry<String, PaymentCycleRecord> entry : allRecords) {
            String source = entry.getKey();
            PaymentCycleRecord record = entry.getValue();
            String name = formatRecordName(record);
            if (name.isEmpty()) name = strOr(record.get("상품명칭"));
            String status = classifyCycleStatus(record);
            if (categories.containsKey(status)) {
                List<String> bucket = categories.get(status);
                if (!bucket.contains(name)) bucket.add(name);
            }
            LinkedHashMap<String, Integer> counts = fileSummary.computeIfAbsent(source, k -> {
                LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
                m.put("매핑완료(단일)", 0);
                m.put("매핑완료(다중)", 0);
                m.put("매핑안됨", 0);
                m.put("합계", 0);
                return m;
            });
            counts.merge(status, 1, Integer::sum);
            counts.merge("합계", 1, Integer::sum);
        }

        LinkedHashMap<String, Object> report = new LinkedHashMap<>();
        report.put("총건수", allRecords.size());
        report.put("매핑완료(단일)_count", categories.get("매핑완료(단일)").size());
        report.put("매핑완료(다중)_count", categories.get("매핑완료(다중)").size());
        report.put("매핑안됨_count", categories.get("매핑안됨").size());
        report.put("매핑완료(단일)", categories.get("매핑완료(단일)"));
        report.put("매핑완료(다중)", categories.get("매핑완료(다중)"));
        report.put("매핑안됨", categories.get("매핑안됨"));

        List<LinkedHashMap<String, Object>> summary = new ArrayList<>();
        List<String> sortedSources = new ArrayList<>(fileSummary.keySet());
        Collections.sort(sortedSources);
        for (String src : sortedSources) {
            LinkedHashMap<String, Integer> counts = fileSummary.get(src);
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("source", src);
            row.put("매핑완료(단일)", counts.get("매핑완료(단일)"));
            row.put("매핑완료(다중)", counts.get("매핑완료(다중)"));
            row.put("매핑안됨", counts.get("매핑안됨"));
            row.put("합계", counts.get("합계"));
            summary.add(row);
        }
        report.put("파일별요약", summary);
        return report;
    }
}
