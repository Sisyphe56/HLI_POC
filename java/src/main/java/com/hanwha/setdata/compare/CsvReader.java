package com.hanwha.setdata.compare;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal CSV parser mirroring Python's {@code csv.DictReader} with default
 * (excel) dialect: comma delimiter, double-quote quoting, doubled quotes as
 * escape, CRLF/LF/CR row terminators.
 *
 * <p>Sufficient for the comparator's input files (standard RFC 4180-ish CSVs
 * with optional quoting around fields containing commas or quotes).
 */
public final class CsvReader {

    private CsvReader() {}

    /** Parse full CSV text → list of header→value maps (first row is header). */
    public static List<Map<String, String>> parse(String text) {
        List<List<String>> rows = parseRaw(text);
        List<Map<String, String>> out = new ArrayList<>();
        if (rows.isEmpty()) return out;
        List<String> headers = rows.get(0);
        for (int r = 1; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            Map<String, String> m = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                String v = c < cells.size() ? cells.get(c) : "";
                m.put(headers.get(c), v);
            }
            out.add(m);
        }
        return out;
    }

    /** Parse text into list-of-rows of raw cell values. */
    public static List<List<String>> parseRaw(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                cell.append(ch);
                i++;
                continue;
            }
            // not in quotes
            if (ch == '"') {
                inQuotes = true;
                i++;
                continue;
            }
            if (ch == ',') {
                current.add(cell.toString());
                cell.setLength(0);
                i++;
                continue;
            }
            if (ch == '\r') {
                current.add(cell.toString());
                cell.setLength(0);
                rows.add(current);
                current = new ArrayList<>();
                i++;
                if (i < n && text.charAt(i) == '\n') i++;
                continue;
            }
            if (ch == '\n') {
                current.add(cell.toString());
                cell.setLength(0);
                rows.add(current);
                current = new ArrayList<>();
                i++;
                continue;
            }
            cell.append(ch);
            i++;
        }
        // flush last cell/row if anything remains
        if (cell.length() > 0 || !current.isEmpty()) {
            current.add(cell.toString());
            rows.add(current);
        }
        return rows;
    }
}
