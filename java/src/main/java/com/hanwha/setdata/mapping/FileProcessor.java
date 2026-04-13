package com.hanwha.setdata.mapping;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Python equivalent: {@code process_file}. */
public final class FileProcessor {

    public static final class Result {
        public final List<LinkedHashMap<String, Object>> mappedRows;
        public final LinkedHashMap<String, Integer> stats;
        public final Set<String> matchedCsvIds;

        public Result(List<LinkedHashMap<String, Object>> mappedRows,
                      LinkedHashMap<String, Integer> stats,
                      Set<String> matchedCsvIds) {
            this.mappedRows = mappedRows;
            this.stats = stats;
            this.matchedCsvIds = matchedCsvIds;
        }
    }

    private FileProcessor() {}

    public static Result process(Path jsonPath, List<MappingRow> mappingRows, DataSetConfig config) throws IOException {
        List<Map<String, Object>> records = JsonIO.loadRows(jsonPath);
        List<LinkedHashMap<String, Object>> mapped = new ArrayList<>();
        LinkedHashMap<String, Integer> stats = new LinkedHashMap<>();
        stats.put("total", 0);
        stats.put("matched", 0);
        stats.put("unmatched", 0);
        stats.put("ambiguous", 0);
        Set<String> matchedIds = new LinkedHashSet<>();

        for (Map<String, Object> record : records) {
            stats.put("total", stats.get("total") + 1);
            List<String> components = MapUtils.collectComponents(record);
            List<MappingRow> matches = TokenMatcher.matchCodes(mappingRows, components);

            if (matches.isEmpty()) {
                mapped.add(config.buildRow.apply(record, null));
                stats.put("unmatched", stats.get("unmatched") + 1);
            } else if (matches.size() == 1) {
                mapped.add(config.buildRow.apply(record, matches.get(0)));
                stats.put("matched", stats.get("matched") + 1);
                matchedIds.add(matches.get(0).csvRowId);
            } else {
                for (MappingRow m : matches) {
                    mapped.add(config.buildRow.apply(record, m));
                    matchedIds.add(m.csvRowId);
                }
                stats.put("ambiguous", stats.get("ambiguous") + 1);
            }
        }
        return new Result(mapped, stats, matchedIds);
    }
}
