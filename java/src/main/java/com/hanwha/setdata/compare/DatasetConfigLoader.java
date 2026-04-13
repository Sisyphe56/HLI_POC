package com.hanwha.setdata.compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads {@code config/dataset_configs.json} and exposes per-dataset settings.
 */
public final class DatasetConfigLoader {

    /** Per-dataset metadata + behaviour. Holds raw JsonNode nodes for extensibility. */
    public static final class DatasetConfig {
        public final String name;
        public final String mappedDir;
        public final String answerExcel;
        public final String answerCsv;
        public final String reportDir;
        public final String filePrefix;
        public final String dataField;
        public final String outputLabel;
        public final List<String> answerKeyCols;
        public final List<String> answerValueCols;
        public final List<FieldSpec> tupleFields;
        public final SkipRule skipRule;
        public final List<String> specialRules;
        public final List<String> notSupportedDtcds;

        DatasetConfig(String name, String mappedDir, String answerExcel, String answerCsv,
                      String reportDir, String filePrefix, String dataField, String outputLabel,
                      List<String> answerKeyCols, List<String> answerValueCols,
                      List<FieldSpec> tupleFields, SkipRule skipRule,
                      List<String> specialRules, List<String> notSupportedDtcds) {
            this.name = name;
            this.mappedDir = mappedDir;
            this.answerExcel = answerExcel;
            this.answerCsv = answerCsv;
            this.reportDir = reportDir;
            this.filePrefix = filePrefix;
            this.dataField = dataField;
            this.outputLabel = outputLabel == null ? "values" : outputLabel;
            this.answerKeyCols = answerKeyCols;
            this.answerValueCols = answerValueCols;
            this.tupleFields = tupleFields;
            this.skipRule = skipRule;
            this.specialRules = specialRules;
            this.notSupportedDtcds = notSupportedDtcds;
        }
    }

    public static final class FieldSpec {
        public final String jsonKey;
        public final String csvKey;
        public final String norm;
        public final String defaultValue; // null if not set
        public final boolean hasDefault;

        FieldSpec(String jsonKey, String csvKey, String norm, String defaultValue, boolean hasDefault) {
            this.jsonKey = jsonKey;
            this.csvKey = csvKey;
            this.norm = norm;
            this.defaultValue = defaultValue;
            this.hasDefault = hasDefault;
        }
    }

    public static final class SkipRule {
        public final String mode; // all_empty / any_empty / all_default / null
        public final List<Integer> fields;

        SkipRule(String mode, List<Integer> fields) {
            this.mode = mode;
            this.fields = fields;
        }
    }

    public static Map<String, DatasetConfig> load(Path configPath, Path projectRoot) throws IOException {
        JsonNode root = new ObjectMapper().readTree(configPath.toFile());
        Map<String, DatasetConfig> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            if (key.startsWith("_")) continue;
            JsonNode cfg = e.getValue();

            List<FieldSpec> fields = new ArrayList<>();
            if (cfg.has("tuple_fields")) {
                for (JsonNode f : cfg.get("tuple_fields")) {
                    String jsonKey = f.path("json").asText("");
                    String csvKey = f.path("csv").asText("");
                    String norm = f.has("norm") ? f.get("norm").asText("text") : "text";
                    boolean hasDefault = f.has("default");
                    String defVal = hasDefault ? f.get("default").asText() : null;
                    fields.add(new FieldSpec(jsonKey, csvKey, norm, defVal, hasDefault));
                }
            }

            SkipRule skipRule = null;
            if (cfg.has("skip_rule")) {
                JsonNode sr = cfg.get("skip_rule");
                String mode = sr.path("mode").asText("all_empty");
                List<Integer> idx = new ArrayList<>();
                if (sr.has("fields")) {
                    for (JsonNode n : sr.get("fields")) idx.add(n.asInt());
                }
                skipRule = new SkipRule(mode, idx);
            }

            List<String> specialRules = asStringList(cfg.get("special_rules"));
            List<String> notSupported = asStringList(cfg.get("not_supported_dtcds"));
            List<String> answerKeyCols = asStringList(cfg.get("answer_key_cols"));
            List<String> answerValueCols = asStringList(cfg.get("answer_value_cols"));

            out.put(key, new DatasetConfig(
                    key,
                    cfg.path("mapped_dir").asText(""),
                    cfg.path("answer_excel").asText(""),
                    cfg.has("answer_csv") ? cfg.get("answer_csv").asText() : null,
                    cfg.path("report_dir").asText(""),
                    cfg.path("file_prefix").asText(""),
                    cfg.path("data_field").asText(""),
                    cfg.path("output_label").asText("values"),
                    answerKeyCols,
                    answerValueCols,
                    fields,
                    skipRule,
                    specialRules,
                    notSupported
            ));
        }
        return out;
    }

    private static List<String> asStringList(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n == null || !n.isArray()) return out;
        for (JsonNode v : n) out.add(v.asText());
        return out;
    }
}
