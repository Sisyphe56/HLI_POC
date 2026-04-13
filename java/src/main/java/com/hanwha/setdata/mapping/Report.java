package com.hanwha.setdata.mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Python equivalent: {@code generate_csv_based_report}. */
public final class Report {

    private Report() {}

    public static LinkedHashMap<String, Object> generate(
            String dataSet,
            List<MappingRow> mappingRows,
            Set<String> matchedCsvIds,
            List<LinkedHashMap<String, Object>> fileStats) {

        int totalInput = 0, totalMatched = 0, totalUnmatched = 0, totalAmbiguous = 0;
        for (LinkedHashMap<String, Object> s : fileStats) {
            totalInput += toInt(s.get("total"));
            totalMatched += toInt(s.get("matched"));
            totalUnmatched += toInt(s.get("unmatched"));
            totalAmbiguous += toInt(s.get("ambiguous"));
        }

        List<LinkedHashMap<String, Object>> csvRowsReport = new ArrayList<>();
        int csvMatched = 0;
        for (MappingRow row : mappingRows) {
            boolean matched = matchedCsvIds.contains(row.csvRowId);
            if (matched) csvMatched++;
            LinkedHashMap<String, Object> r = new LinkedHashMap<>();
            r.put("isrn_kind_dtcd", row.isrnKindDtcd);
            r.put("isrn_kind_itcd", row.isrnKindItcd);
            r.put("isrn_kind_sale_nm", row.isrnKindSaleNm);
            r.put("status", matched ? "matched" : "unmatched");
            csvRowsReport.add(r);
        }
        int csvUnmatched = csvRowsReport.size() - csvMatched;

        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_input_rows", totalInput);
        summary.put("matched", totalMatched);
        summary.put("unmatched", totalUnmatched);
        summary.put("ambiguous", totalAmbiguous);
        summary.put("match_rate", totalInput > 0
                ? String.format(Locale.ROOT, "%.1f%%", totalMatched * 100.0 / totalInput)
                : "0%");
        summary.put("csv_total", mappingRows.size());
        summary.put("csv_matched", csvMatched);
        summary.put("csv_unmatched", csvUnmatched);
        summary.put("csv_match_rate", !mappingRows.isEmpty()
                ? String.format(Locale.ROOT, "%.1f%%", csvMatched * 100.0 / mappingRows.size())
                : "0%");

        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("data_set", dataSet);
        out.put("summary", summary);
        out.put("file_details", fileStats);
        out.put("csv_rows", csvRowsReport);
        return out;
    }

    private static int toInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o == null) return 0;
        try { return Integer.parseInt(String.valueOf(o)); } catch (NumberFormatException e) { return 0; }
    }
}
