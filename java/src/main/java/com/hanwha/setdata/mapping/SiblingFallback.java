package com.hanwha.setdata.mapping;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Python equivalents: {@code _find_sibling_targets}, {@code _build_sibling_row},
 * {@code _apply_sibling_fallback_inline}, {@code apply_sibling_fallback}.
 */
public final class SiblingFallback {

    private SiblingFallback() {}

    public static List<MappingRow> findSiblingTargets(List<MappingRow> mappingRows, Set<String> matchedCsvIds) {
        LinkedHashMap<String, List<Integer>> index = new LinkedHashMap<>();
        for (int i = 0; i < mappingRows.size(); i++) {
            MappingRow row = mappingRows.get(i);
            String key = row.isrnKindDtcd + "\0" + row.isrnKindItcd;
            index.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        List<MappingRow> targets = new ArrayList<>();
        for (MappingRow row : mappingRows) {
            if (matchedCsvIds.contains(row.csvRowId)) continue;
            String key = row.isrnKindDtcd + "\0" + row.isrnKindItcd;
            List<Integer> siblings = index.getOrDefault(key, Collections.emptyList());
            boolean hasMatched = false;
            for (int j : siblings) {
                if (matchedCsvIds.contains(mappingRows.get(j).csvRowId)) { hasMatched = true; break; }
            }
            if (hasMatched) targets.add(row);
        }
        return targets;
    }

    public static LinkedHashMap<String, Object> buildSiblingRow(
            MappingRow target, List<LinkedHashMap<String, Object>> templateCandidates,
            List<String> dataFieldNames) {
        Set<String> targetTokens = new LinkedHashSet<>(MapUtils.splitMatchTokens(target.prodSaleNm));
        LinkedHashMap<String, Object> bestTemplate = templateCandidates.get(0);
        int bestScore = -1;
        for (LinkedHashMap<String, Object> cand : templateCandidates) {
            Object nm = cand.get("상품명");
            String candName = nm == null || String.valueOf(nm).isEmpty()
                    ? String.valueOf(cand.getOrDefault("상품명칭", ""))
                    : String.valueOf(nm);
            Set<String> candTokens = new LinkedHashSet<>(MapUtils.splitMatchTokens(candName));
            if (targetTokens.isEmpty() || candTokens.isEmpty()) continue;
            int overlap = 0;
            for (String t : targetTokens) if (candTokens.contains(t)) overlap++;
            int extra = 0;
            for (String t : candTokens) if (!targetTokens.contains(t)) extra++;
            int score = overlap * 10 - extra;
            if (score > bestScore) {
                bestScore = score;
                bestTemplate = cand;
            }
        }

        LinkedHashMap<String, Object> newRow = new LinkedHashMap<>();
        newRow.put("isrn_kind_dtcd", target.isrnKindDtcd);
        newRow.put("isrn_kind_itcd", target.isrnKindItcd);
        newRow.put("isrn_kind_sale_nm", target.isrnKindSaleNm);
        newRow.put("prod_dtcd", target.prodDtcd);
        newRow.put("prod_itcd", target.prodItcd);
        newRow.put("prod_sale_nm", target.prodSaleNm);
        for (String k : new String[]{"상품명칭", "상품명"}) {
            if (bestTemplate.containsKey(k)) newRow.put(k, bestTemplate.get(k));
        }
        // Python: `for k in sorted(best_template.keys())` filtered to 세부종목*
        TreeMap<String, Object> sortedDetails = new TreeMap<>();
        for (Map.Entry<String, Object> e : bestTemplate.entrySet()) {
            if (e.getKey().startsWith("세부종목")) sortedDetails.put(e.getKey(), e.getValue());
        }
        newRow.putAll(sortedDetails);
        for (String k : dataFieldNames) {
            if (bestTemplate.containsKey(k)) newRow.put(k, bestTemplate.get(k));
        }
        return newRow;
    }

    public static int applyInline(
            List<LinkedHashMap<String, Object>> mappedRows,
            Set<String> matchedIds,
            List<MappingRow> mappingRows,
            List<String> dataFieldNames) {
        List<MappingRow> targets = findSiblingTargets(mappingRows, matchedIds);
        if (targets.isEmpty()) return 0;

        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> index = new LinkedHashMap<>();
        for (LinkedHashMap<String, Object> row : mappedRows) {
            String dtcd = String.valueOf(row.getOrDefault("isrn_kind_dtcd", ""));
            String itcd = String.valueOf(row.getOrDefault("isrn_kind_itcd", ""));
            if (dtcd.isEmpty()) continue;
            index.computeIfAbsent(dtcd + "\0" + itcd, k -> new ArrayList<>()).add(row);
        }
        int added = 0;
        for (MappingRow target : targets) {
            String key = target.isrnKindDtcd + "\0" + target.isrnKindItcd;
            List<LinkedHashMap<String, Object>> candidates = index.getOrDefault(key, Collections.emptyList());
            if (candidates.isEmpty()) continue;
            mappedRows.add(buildSiblingRow(target, candidates, dataFieldNames));
            added++;
        }
        if (added > 0) {
            System.out.println("Sibling fallback: " + added + " CSV rows added");
        }
        return added;
    }

    public static Set<String> applyToFiles(
            List<MappingRow> mappingRows,
            Set<String> allMatchedCsvIds,
            Path outputDir,
            DataSetConfig config,
            List<String> dataFieldNames) throws IOException {
        List<MappingRow> targets = findSiblingTargets(mappingRows, allMatchedCsvIds);
        if (targets.isEmpty()) return new LinkedHashSet<>();

        List<Path> outputFiles = new ArrayList<>();
        if (Files.isDirectory(outputDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(
                    outputDir, config.outputPrefix + "*.json")) {
                for (Path p : ds) outputFiles.add(p);
            }
        }
        Collections.sort(outputFiles);

        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> dtcdItcdOutput = new LinkedHashMap<>();
        LinkedHashMap<String, Path> outputFileMap = new LinkedHashMap<>();
        for (Path fp : outputFiles) {
            List<Map<String, Object>> rows = JsonIO.loadRows(fp);
            for (Map<String, Object> row : rows) {
                String dtcd = String.valueOf(row.getOrDefault("isrn_kind_dtcd", ""));
                String itcd = String.valueOf(row.getOrDefault("isrn_kind_itcd", ""));
                if (dtcd.isEmpty()) continue;
                String key = dtcd + "\0" + itcd;
                dtcdItcdOutput.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new LinkedHashMap<>(row));
                outputFileMap.putIfAbsent(key, fp);
            }
        }

        Set<String> addedIds = new LinkedHashSet<>();
        LinkedHashMap<Path, List<LinkedHashMap<String, Object>>> fileAppends = new LinkedHashMap<>();

        for (MappingRow target : targets) {
            String key = target.isrnKindDtcd + "\0" + target.isrnKindItcd;
            List<LinkedHashMap<String, Object>> candidates = dtcdItcdOutput.getOrDefault(key, Collections.emptyList());
            if (candidates.isEmpty()) continue;
            LinkedHashMap<String, Object> newRow = buildSiblingRow(target, candidates, dataFieldNames);
            Path fp = outputFileMap.get(key);
            if (fp != null) {
                fileAppends.computeIfAbsent(fp, k -> new ArrayList<>()).add(newRow);
            }
            addedIds.add(target.csvRowId);
        }

        for (Map.Entry<Path, List<LinkedHashMap<String, Object>>> e : fileAppends.entrySet()) {
            Path fp = e.getKey();
            List<Map<String, Object>> existing = JsonIO.loadRows(fp);
            List<Map<String, Object>> merged = new ArrayList<>(existing);
            merged.addAll(e.getValue());
            JsonIO.writeJson(fp, merged);
        }

        if (!addedIds.isEmpty()) {
            System.out.println("Sibling fallback: " + addedIds.size() + " CSV rows added");
        }
        return addedIds;
    }
}
