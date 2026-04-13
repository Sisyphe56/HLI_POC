package com.hanwha.setdata.compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IO loaders — mapping CSV, answer CSV/Excel, mapped JSON dirs.
 * Mirrors {@code load_csv_rows}, {@code load_answer_csv}, {@code load_answer_excel},
 * and {@code load_mapped_json_files} in {@code compare_product_data.py}.
 */
public final class CompareLoaders {

    private static final Set<String> CODE_FIELDS =
            new HashSet<>(Arrays.asList("ISRN_KIND_DTCD", "ISRN_KIND_ITCD", "PROD_DTCD", "PROD_ITCD"));

    private static final ObjectMapper JSON = new ObjectMapper();

    private CompareLoaders() {}

    /** Represents one mapping CSV row (261 product codes). */
    public static final class MappingRow {
        public final String isrnKindDtcd;
        public final String isrnKindItcd;
        public final String isrnKindSaleNm;
        public final String prodDtcd;
        public final String prodItcd;
        public final String prodSaleNm;

        MappingRow(String a, String b, String c, String d, String e, String f) {
            this.isrnKindDtcd = a; this.isrnKindItcd = b; this.isrnKindSaleNm = c;
            this.prodDtcd = d; this.prodItcd = e; this.prodSaleNm = f;
        }
    }

    /** Python {@code load_csv_rows}. */
    public static List<MappingRow> loadMappingCsv(Path path) throws IOException {
        String text = readTextWithFallback(path);
        List<Map<String, String>> raw = CsvReader.parse(text);
        List<MappingRow> out = new ArrayList<>();
        for (Map<String, String> r : raw) {
            String dtcd = CompareNormalizers.normalizeCode(r.getOrDefault("ISRN_KIND_DTCD", ""));
            String itcd = CompareNormalizers.normalizeCode(r.getOrDefault("ISRN_KIND_ITCD", ""));
            // Python: `if dtcd and itcd` — normalize_code always returns non-empty string
            // ("0" for empty input), so this guard is effectively always true. Kept for parity.
            if (dtcd.isEmpty() || itcd.isEmpty()) continue;
            String saleNm = CompareNormalizers.normalizeText(r.getOrDefault("ISRN_KIND_SALE_NM", ""));
            String prodDtcd = CompareNormalizers.normalizeCode(r.getOrDefault("PROD_DTCD", ""));
            String prodItcd = CompareNormalizers.normalizeCode(r.getOrDefault("PROD_ITCD", ""));
            String prodSaleNm = CompareNormalizers.normalizeText(r.getOrDefault("PROD_SALE_NM", ""));
            out.add(new MappingRow(dtcd, itcd, saleNm, prodDtcd, prodItcd, prodSaleNm));
        }
        return out;
    }

    /** Python {@code read_text_with_fallback}. */
    public static String readTextWithFallback(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        for (String enc : new String[]{"UTF-8", "UTF-8", "x-windows-949", "EUC-KR"}) {
            try {
                Charset cs = Charset.forName(enc);
                String decoded = cs.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                if (decoded.startsWith("\uFEFF")) decoded = decoded.substring(1);
                return decoded;
            } catch (Exception ignored) {
                // try next
            }
        }
        throw new IOException("Cannot decode " + path);
    }

    /** Python {@code load_answer_csv} — euc-kr. */
    public static Map<List<String>, List<Map<String, String>>> loadAnswerCsv(
            Path path, List<String> keyCols, List<String> valueCols) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String text = Charset.forName("EUC-KR").newDecoder()
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<Map<String, String>> raw = CsvReader.parse(text);
        return bucketize(raw, keyCols, valueCols, 2);
    }

    /** Python {@code load_answer_excel} via Apache POI. */
    public static Map<List<String>, List<Map<String, String>>> loadAnswerExcel(
            Path path, List<String> keyCols, List<String> valueCols) throws IOException {
        try (InputStream is = Files.newInputStream(path);
             Workbook wb = WorkbookFactory.create(is)) {
            Sheet ws = wb.getSheetAt(0);
            Row headerRow = ws.getRow(0);
            List<String> headers = new ArrayList<>();
            DataFormatter df = new DataFormatter();
            if (headerRow != null) {
                short last = headerRow.getLastCellNum();
                for (int c = 0; c < last; c++) {
                    Cell cell = headerRow.getCell(c);
                    headers.add(cell == null ? "" : df.formatCellValue(cell));
                }
            }
            List<Map<String, String>> rows = new ArrayList<>();
            for (int r = 1; r <= ws.getLastRowNum(); r++) {
                Row row = ws.getRow(r);
                if (row == null) continue;
                Map<String, String> map = new LinkedHashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = row.getCell(c);
                    if (cell == null) { map.put(headers.get(c), ""); continue; }
                    String v;
                    if (cell.getCellType() == CellType.NUMERIC) {
                        double d = cell.getNumericCellValue();
                        if (d == Math.floor(d) && !Double.isInfinite(d)) {
                            v = Long.toString((long) d);
                        } else {
                            v = Double.toString(d);
                        }
                    } else {
                        v = df.formatCellValue(cell);
                    }
                    map.put(headers.get(c), v);
                }
                rows.add(map);
            }
            return bucketize(rows, keyCols, valueCols, 2);
        }
    }

    private static Map<List<String>, List<Map<String, String>>> bucketize(
            List<Map<String, String>> rows, List<String> keyCols, List<String> valueCols, int startIdx) {
        Set<String> allCols = new LinkedHashSet<>();
        allCols.addAll(keyCols);
        allCols.addAll(valueCols);

        Map<List<String>, List<Map<String, String>>> out = new LinkedHashMap<>();
        int rowNum = startIdx;
        for (Map<String, String> raw : rows) {
            Map<String, String> normed = new LinkedHashMap<>();
            for (String col : allCols) {
                Object v = raw.getOrDefault(col, "");
                if (CODE_FIELDS.contains(col)) {
                    normed.put(col, CompareNormalizers.normalizeCode(v));
                } else {
                    normed.put(col, CompareNormalizers.normalizeText(v));
                }
            }
            List<String> key = new ArrayList<>(keyCols.size());
            boolean allPresent = true;
            for (String c : keyCols) {
                String v = normed.get(c);
                if (v == null || v.isEmpty()) { allPresent = false; break; }
                key.add(v);
            }
            if (!allPresent) { rowNum++; continue; }

            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("row_num", Integer.toString(rowNum));
            for (String c : allCols) entry.put(c, normed.get(c));
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
            rowNum++;
        }
        return out;
    }

    /** Python {@code load_mapped_json_files}. */
    public static List<Map<String, Object>> loadMappedJsonFiles(Path dir, String prefix) throws IOException {
        List<Path> paths = new ArrayList<>();
        if (!Files.isDirectory(dir)) return new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, prefix + "*.json")) {
            for (Path p : ds) paths.add(p);
        }
        paths.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Path p : paths) {
            JsonNode root = JSON.readTree(p.toFile());
            if (!root.isArray()) continue;
            for (JsonNode item : root) {
                if (!item.isObject()) continue;
                Map<String, Object> row = nodeToMap(item);
                row.put("source_file", p.getFileName().toString());
                out.add(row);
            }
        }
        return out;
    }

    /** Load a single mapped JSON file (for single-file CLI mode). */
    public static List<Map<String, Object>> loadSingleMappedJson(Path file) throws IOException {
        JsonNode root = JSON.readTree(file.toFile());
        List<Map<String, Object>> out = new ArrayList<>();
        if (!root.isArray()) return out;
        for (JsonNode item : root) {
            if (!item.isObject()) continue;
            Map<String, Object> row = nodeToMap(item);
            row.put("source_file", file.getFileName().toString());
            out.add(row);
        }
        return out;
    }

    static Map<String, Object> nodeToMap(JsonNode obj) {
        Map<String, Object> m = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            m.put(e.getKey(), nodeToValue(e.getValue()));
        }
        return m;
    }

    static Object nodeToValue(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode c : n) list.add(nodeToValue(c));
            return list;
        }
        if (n.isObject()) return nodeToMap(n);
        if (n.isBoolean()) return n.booleanValue();
        if (n.isInt() || n.isLong()) return n.longValue();
        if (n.isFloatingPointNumber()) return n.doubleValue();
        return n.asText();
    }
}
