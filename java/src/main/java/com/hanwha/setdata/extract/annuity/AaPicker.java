package com.hanwha.setdata.extract.annuity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Row-level matching/picking of annuity age values from blocks.
 * Ports {@code pick_best_annuity_values} and {@code pick_annuity_values_for_row}.
 */
public final class AaPicker {

    private AaPicker() {}

    public static final class Ages {
        public final List<String> male;
        public final List<String> female;
        public final List<String> common;
        public Ages(List<String> m, List<String> f, List<String> c) {
            this.male = m; this.female = f; this.common = c;
        }
    }

    public static Ages empty() {
        return new Ages(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    public static Ages pickBest(List<AaBlocks.Block> blocks) {
        Set<String> male = new LinkedHashSet<>();
        Set<String> female = new LinkedHashSet<>();
        Set<String> common = new LinkedHashSet<>();
        for (AaBlocks.Block block : blocks) {
            Map<String, List<String>> gen = block.genericValues;
            if (gen != null) {
                addAll(male, gen.get("남자"));
                addAll(female, gen.get("여자"));
                addAll(common, gen.get(""));
            }
            for (Map<String, List<String>> cv : block.categoryValues.values()) {
                addAll(male, cv.get("남자"));
                addAll(female, cv.get("여자"));
                addAll(common, cv.get(""));
            }
        }
        return new Ages(sortedAsInt(male), sortedAsInt(female), sortedAsInt(common));
    }

    private static void addAll(Set<String> dest, List<String> src) {
        if (src != null) dest.addAll(src);
    }

    private static List<String> sortedAsInt(Set<String> values) {
        TreeSet<int[]> sorter = new TreeSet<>((a, b) -> {
            if (a[0] != b[0]) return Integer.compare(a[0], b[0]);
            return Integer.compare(a[1], b[1]);
        });
        int idx = 0;
        Map<Integer, String> idxToStr = new LinkedHashMap<>();
        for (String v : values) {
            int key;
            try { key = Integer.parseInt(v); } catch (NumberFormatException e) { key = Integer.MAX_VALUE; }
            sorter.add(new int[]{key, idx});
            idxToStr.put(idx, v);
            idx++;
        }
        List<String> out = new ArrayList<>();
        for (int[] p : sorter) out.add(idxToStr.get(p[1]));
        return out;
    }

    private static int[] score(boolean exact, int overlap, int sourceBonus, int keyLen) {
        return new int[]{exact ? 2 : 0, overlap, sourceBonus, keyLen};
    }

    private static int compareScore(int[] a, int[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return 0;
    }

    public static Ages pickForRow(Map<String, Object> row, List<AaBlocks.Block> blocks) {
        if (blocks == null || blocks.isEmpty()) return empty();

        String text = AaText.rowText(row);
        if (text.isEmpty()) {
            Ages best = pickBest(blocks);
            return new Ages(new ArrayList<>(best.male), new ArrayList<>(best.female), new ArrayList<>(best.common));
        }

        Set<String> rowTokens = AaText.extractRowContextTokens(row);

        int[] bestScores = null;
        Ages bestValues = null;
        int[] bestDiffScores = null;
        Ages bestDiffValues = null;
        int[] bestAnyScores = null;
        Ages bestAnyValues = null;

        for (AaBlocks.Block block : blocks) {
            Map<String, Map<String, List<String>>> categoryMap = block.categoryValues;
            if (categoryMap == null) continue;
            for (Map.Entry<String, Map<String, List<String>>> e : categoryMap.entrySet()) {
                String key = e.getKey();
                Map<String, List<String>> values = e.getValue();
                if (values == null) continue;
                List<String> keyTokensList = AaText.splitContextKey(key);
                if (keyTokensList.isEmpty()) continue;
                Set<String> keyTokens = new LinkedHashSet<>(keyTokensList);
                Set<String> overlap = new LinkedHashSet<>(keyTokens);
                overlap.retainAll(rowTokens);
                if (overlap.isEmpty()) continue;
                boolean isExact = rowTokens.containsAll(keyTokens);
                List<String> male = values.getOrDefault("남자", new ArrayList<>());
                List<String> female = values.getOrDefault("여자", new ArrayList<>());
                List<String> common = values.getOrDefault("", new ArrayList<>());
                int sourceBonus = "table".equals(block.source) ? 1 : 0;
                int[] scoreArr = score(isExact, overlap.size(), sourceBonus, keyTokens.size());
                Ages entry = new Ages(new ArrayList<>(male), new ArrayList<>(female), new ArrayList<>(common));

                if (bestScores == null || compareScore(scoreArr, bestScores) > 0) {
                    bestValues = entry;
                    bestScores = scoreArr;
                }
                if (!male.isEmpty() || !female.isEmpty()) {
                    boolean hasDiff = !new LinkedHashSet<>(male).equals(new LinkedHashSet<>(female));
                    if (hasDiff && (bestDiffScores == null || compareScore(scoreArr, bestDiffScores) > 0)) {
                        bestDiffValues = entry;
                        bestDiffScores = scoreArr;
                    }
                    if (bestAnyScores == null || compareScore(scoreArr, bestAnyScores) > 0) {
                        bestAnyValues = entry;
                        bestAnyScores = scoreArr;
                    }
                }
            }
        }

        if (bestValues != null) {
            List<String> bm = bestValues.male;
            List<String> bf = bestValues.female;
            if (bm.isEmpty() && bf.isEmpty()) {
                Ages genderFallback = null;
                int[] fbScores = null;
                if (bestDiffValues != null) {
                    genderFallback = bestDiffValues;
                    fbScores = bestDiffScores;
                } else if (bestAnyValues != null) {
                    genderFallback = bestAnyValues;
                    fbScores = bestAnyScores;
                }
                if (genderFallback != null && fbScores != null) {
                    String productName = AaText.normalizeWs(
                            (str(row.get("상품명"))) + " " + (str(row.get("세부종목3"))));
                    boolean isSinbubu = productName.contains("신부부형");
                    if (isSinbubu || compareScore(fbScores, bestScores) >= 0) {
                        bestValues = genderFallback;
                    }
                }
            }
            return new Ages(new ArrayList<>(bestValues.male),
                    new ArrayList<>(bestValues.female),
                    new ArrayList<>(bestValues.common));
        }

        Ages first = pickBest(blocks);
        return new Ages(new ArrayList<>(first.male), new ArrayList<>(first.female), new ArrayList<>(first.common));
    }

    private static String str(Object v) { return v == null ? "" : v.toString(); }
}
