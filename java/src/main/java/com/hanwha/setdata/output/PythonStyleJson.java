package com.hanwha.setdata.output;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Emits JSON in the exact format produced by Python
 * {@code json.dumps(obj, ensure_ascii=False, indent=2)}:
 *
 * <ul>
 *   <li>2-space indent for both objects and arrays</li>
 *   <li>{@code ", "} between array items on the same line (N/A for our indent=2 case)</li>
 *   <li>{@code ": "} between object key and value</li>
 *   <li>No trailing newline after final {@code ]} or {@code }}</li>
 *   <li>Unicode characters emitted raw (UTF-8), not as {@code \\uXXXX} escapes</li>
 * </ul>
 *
 * <p>Jackson's {@link DefaultPrettyPrinter} by default uses different array vs
 * object indent; we override arrays to use the same 2-space indenter.
 * Jackson UTF-8 JsonGenerator already writes raw characters, matching
 * {@code ensure_ascii=False}.
 */
public final class PythonStyleJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectWriter WRITER = MAPPER.writer(buildPrinter());

    private PythonStyleJson() {}

    private static DefaultPrettyPrinter buildPrinter() {
        DefaultPrettyPrinter p = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        p.indentArraysWith(indenter);
        p.indentObjectsWith(indenter);
        // Python json.dumps puts ": " between key and value.
        p.withObjectIndenter(indenter);
        p.withArrayIndenter(indenter);
        // Override separator: Jackson default is " : ", Python uses ": "
        p = p.withSeparators(com.fasterxml.jackson.core.util.Separators.createDefaultInstance()
                .withObjectFieldValueSpacing(com.fasterxml.jackson.core.util.Separators.Spacing.AFTER));
        return p;
    }

    public static String writeString(Object value) throws JsonMappingException {
        try {
            String json = WRITER.writeValueAsString(value);
            // Jackson default empty-array printer writes "[ ]"; Python json.dumps
            // writes "[]". Same for "{ }" vs "{}". Post-process to match.
            return json.replace("[ ]", "[]").replace("{ }", "{}");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeFile(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent() == null ? path.toAbsolutePath().getParent() : path.getParent());
        String json = writeString(value);
        Files.writeString(path, json, java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }
}
