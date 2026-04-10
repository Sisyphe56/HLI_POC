package com.hanwha.setdata.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Loads {@code config/product_overrides.json} as a {@link JsonNode} tree.
 *
 * <p>Dynamic per-action schema (fixed / sibling_filter / sibling_copy / filter /
 * alias / gender_split / min_age_floor / add_annuity_start_age) is kept as raw
 * {@link JsonNode} and dispatched by each consumer. This mirrors the Python
 * side's dict-based lookup and avoids a brittle POJO for every action variant.
 */
public final class OverridesConfig {

    private final JsonNode root;

    private OverridesConfig(JsonNode root) {
        this.root = root;
    }

    public static OverridesConfig load(Path path) throws IOException {
        return new OverridesConfig(new ObjectMapper().readTree(path.toFile()));
    }

    public JsonNode root() {
        return root;
    }

    public JsonNode section(String name) {
        JsonNode n = root.get(name);
        return n == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : n;
    }

    /**
     * Return the {@code use_section} value ("최초계약", "갱신계약", ...) if the given
     * filename matches any {@code table_section_filter.rules[].filename_contains}.
     * Returns empty string when no rule matches. Mirrors Python {@code _get_section_filter}.
     */
    public String tableSectionFilter(String fileName) {
        JsonNode rules = section("table_section_filter").get("rules");
        if (rules == null || !rules.isArray()) return "";
        for (JsonNode r : rules) {
            String kw = r.path("filename_contains").asText("");
            if (!kw.isEmpty() && fileName.contains(kw)) {
                return r.path("use_section").asText("");
            }
        }
        return "";
    }

    /**
     * Convenience accessor: list of {@code (key, spec)} entries under a section,
     * filtering out {@code _설명} meta keys.
     */
    public List<Map.Entry<String, JsonNode>> entries(String section) {
        List<Map.Entry<String, JsonNode>> result = new ArrayList<>();
        JsonNode s = section(section);
        if (!s.isObject()) return result;
        Iterator<Map.Entry<String, JsonNode>> it = s.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().startsWith("_")) continue;
            result.add(e);
        }
        return result;
    }
}
