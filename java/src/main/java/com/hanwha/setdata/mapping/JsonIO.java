package com.hanwha.setdata.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.hanwha.setdata.output.PythonStyleJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON read/write helpers using LinkedHashMap for insertion order. */
public final class JsonIO {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonIO() {}

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> loadRows(Path path) throws IOException {
        if (!Files.exists(path)) return new ArrayList<>();
        Object parsed = MAPPER.readValue(
                path.toFile(),
                new TypeReference<Object>() {}
        );
        if (!(parsed instanceof List)) return new ArrayList<>();
        List<?> raw = (List<?>) parsed;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof Map) {
                // Re-wrap in LinkedHashMap (Jackson default for maps already preserves order).
                out.add(new LinkedHashMap<>((Map<String, Object>) o));
            }
        }
        return out;
    }

    public static void writeJson(Path path, Object value) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        PythonStyleJson.writeFile(path, value);
    }
}
