package com.hanwha.setdata.mapping;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Loads the CSV mapping file. Python equivalent: {@code load_mapping_rows}.
 *
 * <p>Implements a minimal RFC-4180 CSV parser (double-quote escaping, embedded
 * newlines, commas) sufficient for the hand-curated mapping file.
 */
public final class MappingCsv {

    private MappingCsv() {}

    public static List<MappingRow> load(Path csvPath) throws IOException {
        String text = MapUtils.readTextWithFallback(csvPath);
        List<List<String>> rows = parseCsv(text);
        if (rows.isEmpty()) {
            throw new IllegalStateException("No valid rows loaded from " + csvPath);
        }
        List<String> header = rows.get(0);
        Map<String, Integer> col = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) col.put(header.get(i), i);

        List<MappingRow> out = new ArrayList<>();
        for (int idx = 1; idx < rows.size(); idx++) {
            List<String> raw = rows.get(idx);
            String dtcd = MapUtils.normalizeText(get(raw, col, "ISRN_KIND_DTCD"));
            String itcd = MapUtils.normalizeText(get(raw, col, "ISRN_KIND_ITCD"));
            String saleNm = MapUtils.normalizeText(get(raw, col, "ISRN_KIND_SALE_NM"));
            String prodDtcd = MapUtils.normalizeText(get(raw, col, "PROD_DTCD"));
            String prodItcd = MapUtils.normalizeText(get(raw, col, "PROD_ITCD"));
            String prodSaleNm = MapUtils.normalizeText(get(raw, col, "PROD_SALE_NM"));
            if (dtcd.isEmpty() || itcd.isEmpty() || saleNm.isEmpty()) continue;

            List<String> prodTokens = !prodSaleNm.isEmpty()
                    ? MapUtils.splitMatchTokens(prodSaleNm)
                    : MapUtils.splitMatchTokens(saleNm);
            List<String> isrnTokens = MapUtils.splitMatchTokens(saleNm);

            out.add(new MappingRow(
                    "row-" + (idx - 1),
                    dtcd, itcd, saleNm,
                    prodDtcd, prodItcd, prodSaleNm,
                    MapUtils.normalizeMatchKey(!prodSaleNm.isEmpty() ? prodSaleNm : saleNm),
                    prodTokens,
                    new LinkedHashSet<>(prodTokens),
                    MapUtils.normalizeMatchKey(saleNm),
                    new LinkedHashSet<>(isrnTokens)
            ));
        }

        if (out.isEmpty()) {
            throw new IllegalStateException("No valid rows loaded from " + csvPath);
        }
        return out;
    }

    private static String get(List<String> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null || i >= row.size()) return "";
        return row.get(i);
    }

    /** Minimal RFC-4180 parser with quote escaping and embedded newlines. */
    public static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                field.append(c);
                i++;
            } else {
                if (c == '"') {
                    inQuotes = true;
                    i++;
                } else if (c == ',') {
                    cur.add(field.toString());
                    field.setLength(0);
                    i++;
                } else if (c == '\r') {
                    // handled with \n
                    i++;
                } else if (c == '\n') {
                    cur.add(field.toString());
                    field.setLength(0);
                    rows.add(cur);
                    cur = new ArrayList<>();
                    i++;
                } else {
                    field.append(c);
                    i++;
                }
            }
        }
        if (field.length() > 0 || !cur.isEmpty()) {
            cur.add(field.toString());
            rows.add(cur);
        }
        // Drop fully empty trailing rows
        while (!rows.isEmpty()) {
            List<String> last = rows.get(rows.size() - 1);
            boolean allEmpty = true;
            for (String s : last) if (!s.isEmpty()) { allEmpty = false; break; }
            if (allEmpty) rows.remove(rows.size() - 1); else break;
        }
        return rows;
    }
}
