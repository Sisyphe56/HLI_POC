package com.hanwha.setdata.mapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Token-based mapping matcher. Python equivalent: {@code match_codes}.
 */
public final class TokenMatcher {

    private TokenMatcher() {}

    public static List<MappingRow> matchCodes(List<MappingRow> mappingRows, List<String> components) {
        List<String> normalized = new ArrayList<>();
        for (String comp : components) {
            normalized.addAll(MapUtils.splitMatchTokens(comp));
        }
        if (normalized.isEmpty()) return new ArrayList<>();

        // 케어백간병플러스보험 exception: drop '보장형 계약' pair
        StringBuilder sb = new StringBuilder();
        for (String c : components) sb.append(c);
        String productText = sb.toString().replace(" ", "");
        if (productText.contains("케어백간병플러스보험")) {
            List<String> filtered = new ArrayList<>();
            int i = 0;
            while (i < normalized.size()) {
                String token = normalized.get(i);
                if (token.equals("보장형") && i + 1 < normalized.size() && normalized.get(i + 1).equals("계약")) {
                    i += 2;
                    continue;
                }
                if (token.equals("계약") && i > 0 && normalized.get(i - 1).equals("보장형")) {
                    i++;
                    continue;
                }
                filtered.add(token);
                i++;
            }
            normalized = filtered;
        }

        List<String> cleaned = new ArrayList<>();
        for (String t : normalized) if (!t.isEmpty()) cleaned.add(t);
        if (cleaned.isEmpty()) return new ArrayList<>();

        LinkedHashSet<String> required = new LinkedHashSet<>(cleaned);

        // 1) prod_sale_nm token-set containment
        List<MappingRow> candidates = new ArrayList<>();
        for (MappingRow row : mappingRows) {
            if (row.matchTokenSet.containsAll(required)) candidates.add(row);
        }
        // 1-b) prod_sale_nm substring fallback (boundary-aware)
        if (candidates.isEmpty()) {
            for (MappingRow row : mappingRows) {
                if (MapUtils.allTokensInKey(required, row.matchKey)) candidates.add(row);
            }
        }
        // 1-c) isrn_kind_sale_nm fallback
        if (candidates.isEmpty()) {
            for (MappingRow row : mappingRows) {
                if (row.isrnMatchTokenSet.containsAll(required)) candidates.add(row);
            }
        }
        if (candidates.isEmpty()) {
            for (MappingRow row : mappingRows) {
                if (MapUtils.allTokensInKey(required, row.isrnMatchKey)) candidates.add(row);
            }
        }
        if (candidates.isEmpty()) return new ArrayList<>();

        // 납입면제형 filter
        boolean hasWaiver = required.contains("납입면제형");
        List<MappingRow> withWaiver = new ArrayList<>();
        List<MappingRow> withoutWaiver = new ArrayList<>();
        for (MappingRow r : candidates) {
            if (r.matchTokenSet.contains("납입면제형")) withWaiver.add(r);
            else withoutWaiver.add(r);
        }
        if (hasWaiver && !withWaiver.isEmpty()) {
            candidates = withWaiver;
        } else if (!withoutWaiver.isEmpty() && !withWaiver.isEmpty()) {
            candidates = withoutWaiver;
        }

        // 간편가입 filter
        boolean hasSimple = required.contains("간편가입");
        List<MappingRow> withSimple = new ArrayList<>();
        List<MappingRow> withoutSimple = new ArrayList<>();
        for (MappingRow r : candidates) {
            if (r.matchTokenSet.contains("간편가입")) withSimple.add(r);
            else withoutSimple.add(r);
        }
        if (hasSimple && !withSimple.isEmpty()) {
            candidates = withSimple;
        } else if (!withoutSimple.isEmpty() && !withSimple.isEmpty()) {
            candidates = withoutSimple;
        }

        // 일부지급형 level filter
        String refundToken = MapUtils.extractRefundLevelToken(new ArrayList<>(required));
        if (refundToken != null) {
            List<MappingRow> exactRefund = new ArrayList<>();
            for (MappingRow r : candidates) {
                if (r.matchTokenSet.contains(refundToken)) exactRefund.add(r);
            }
            if (!exactRefund.isEmpty()) candidates = exactRefund;
        }

        if (candidates.isEmpty()) return new ArrayList<>();

        // 2) Minimize over-match
        int minExtra = Integer.MAX_VALUE;
        for (MappingRow r : candidates) {
            int extra = 0;
            for (String t : r.matchTokenSet) if (!required.contains(t)) extra++;
            if (extra < minExtra) minExtra = extra;
        }
        List<MappingRow> filtered = new ArrayList<>();
        for (MappingRow r : candidates) {
            int extra = 0;
            for (String t : r.matchTokenSet) if (!required.contains(t)) extra++;
            if (extra == minExtra) filtered.add(r);
        }
        candidates = filtered;

        // 3) Sort by (bracket count, -overlap, match_key)
        final LinkedHashSet<String> req = required;
        Collections.sort(candidates, new Comparator<MappingRow>() {
            @Override public int compare(MappingRow a, MappingRow b) {
                int ba = MapUtils.countChar(a.matchKey, '[');
                int bb = MapUtils.countChar(b.matchKey, '[');
                if (ba != bb) return Integer.compare(ba, bb);
                int oa = 0, ob = 0;
                for (String t : req) {
                    if (a.matchTokenSet.contains(t)) oa++;
                    if (b.matchTokenSet.contains(t)) ob++;
                }
                if (oa != ob) return Integer.compare(ob, oa); // -overlap
                return a.matchKey.compareTo(b.matchKey);
            }
        });
        return candidates;
    }
}
