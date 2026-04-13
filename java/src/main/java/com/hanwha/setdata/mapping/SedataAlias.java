package com.hanwha.setdata.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.hanwha.setdata.config.OverridesConfig;
import com.hanwha.setdata.util.Normalizer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python equivalent: {@code _apply_sedata_alias}. Clones a source extraction
 * JSON into an alias input file, with optional filter / replace / value overrides.
 */
public final class SedataAlias {

    private SedataAlias() {}

    @SuppressWarnings("unchecked")
    public static int apply(Path inputDir, DataSetConfig config, OverridesConfig overrides) throws IOException {
        if (overrides == null) return 0;
        JsonNode sed = overrides.section("sedata_alias");
        if (!sed.isObject()) return 0;
        JsonNode rules = sed.get("rules");
        if (rules == null || !rules.isArray() || rules.size() == 0) return 0;

        List<Path> inputFiles = new ArrayList<>();
        if (Files.isDirectory(inputDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(inputDir, "*.json")) {
                for (Path p : ds) inputFiles.add(p);
            }
        }
        Collections.sort(inputFiles);

        int created = 0;
        for (JsonNode rule : rules) {
            String sourceMatch = rule.path("source_match").asText("");
            String aliasStem = rule.path("alias_output_stem").asText("");
            if (sourceMatch.isEmpty() || aliasStem.isEmpty()) continue;

            Path sourceFile = null;
            for (Path fp : inputFiles) {
                if (Normalizer.nfc(fp.getFileName().toString()).contains(sourceMatch)) {
                    sourceFile = fp;
                    break;
                }
            }
            if (sourceFile == null) continue;

            List<Map<String, Object>> rows = JsonIO.loadRows(sourceFile);
            // Deep clone for mutation safety
            List<Map<String, Object>> aliasRows = new ArrayList<>();
            for (Map<String, Object> r : rows) aliasRows.add(deepCopyMap(r));

            JsonNode inc = rule.get("filter_include");
            if (inc != null && inc.isArray()) {
                List<String> includes = new ArrayList<>();
                for (JsonNode v : inc) includes.add(v.asText());
                List<Map<String, Object>> tmp = new ArrayList<>();
                for (Map<String, Object> r : aliasRows) {
                    String name = String.valueOf(r.getOrDefault("상품명", ""));
                    boolean hit = false;
                    for (String s : includes) {
                        if (name.contains(s)) { hit = true; break; }
                    }
                    if (hit) tmp.add(r);
                }
                aliasRows = tmp;
            }

            JsonNode exc = rule.get("filter_exclude");
            if (exc != null && exc.isArray()) {
                List<String> excludes = new ArrayList<>();
                for (JsonNode v : exc) excludes.add(v.asText());
                List<Map<String, Object>> tmp = new ArrayList<>();
                for (Map<String, Object> r : aliasRows) {
                    String name = String.valueOf(r.getOrDefault("상품명", ""));
                    boolean hit = false;
                    for (String s : excludes) {
                        if (name.contains(s)) { hit = true; break; }
                    }
                    if (!hit) tmp.add(r);
                }
                aliasRows = tmp;
            }

            JsonNode rep = rule.get("name_replace");
            if (rep != null && rep.isObject()) {
                String from = rep.path("from").asText("");
                String to = rep.path("to").asText("");
                for (Map<String, Object> r : aliasRows) {
                    for (String field : new String[]{"상품명칭", "상품명"}) {
                        Object v = r.get(field);
                        if (v != null) {
                            r.put(field, String.valueOf(v).replace(from, to));
                        }
                    }
                }
            }

            JsonNode vo = rule.get("value_overrides");
            if (vo != null && vo.isObject()) {
                java.util.Iterator<Map.Entry<String, JsonNode>> it = vo.fields();
                List<Map.Entry<String, String>> pairs = new ArrayList<>();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    pairs.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), e.getValue().asText()));
                }
                for (Map<String, Object> r : aliasRows) {
                    for (Map.Entry<String, String> pair : pairs) {
                        String[] parts = pair.getKey().split("\\.");
                        if (parts.length == 2) {
                            String arr = parts[0], sub = parts[1];
                            Object arrObj = r.get(arr);
                            if (arrObj instanceof List) {
                                for (Object item : (List<Object>) arrObj) {
                                    if (item instanceof Map) {
                                        Map<String, Object> mp = (Map<String, Object>) item;
                                        if (mp.containsKey(sub)) mp.put(sub, pair.getValue());
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!aliasRows.isEmpty()) {
                Path aliasPath = inputDir.resolve(aliasStem + ".json");
                JsonIO.writeJson(aliasPath, aliasRows);
                created++;
            }
        }
        return created;
    }

    private static Map<String, Object> deepCopyMap(Map<String, Object> src) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : src.entrySet()) {
            out.put(e.getKey(), deepCopy(e.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopy(Object o) {
        if (o instanceof Map) {
            return deepCopyMap((Map<String, Object>) o);
        }
        if (o instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object e : (List<Object>) o) out.add(deepCopy(e));
            return out;
        }
        return o;
    }
}
