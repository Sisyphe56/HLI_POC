package com.hanwha.setdata.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.hanwha.setdata.config.OverridesConfig;
import com.hanwha.setdata.util.Normalizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds per-dataset output rows. Python equivalents:
 * {@code build_product_classification_row}, {@code build_payment_cycle_row},
 * {@code build_annuity_age_row}, {@code build_insurance_period_row},
 * {@code build_join_age_row} along with their override helpers.
 */
public final class RowBuilder {

    private final OverridesConfig overrides;

    public RowBuilder(OverridesConfig overrides) {
        this.overrides = overrides;
    }

    public LinkedHashMap<String, Object> baseRow(Map<String, Object> record, MappingRow csvMatch) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        if (csvMatch != null) {
            row.put("isrn_kind_dtcd", csvMatch.isrnKindDtcd);
            row.put("isrn_kind_itcd", csvMatch.isrnKindItcd);
            row.put("isrn_kind_sale_nm", csvMatch.isrnKindSaleNm);
            row.put("prod_dtcd", csvMatch.prodDtcd);
            row.put("prod_itcd", csvMatch.prodItcd);
            row.put("prod_sale_nm", csvMatch.prodSaleNm);
        } else {
            row.put("isrn_kind_dtcd", "");
            row.put("isrn_kind_itcd", "");
            row.put("isrn_kind_sale_nm", "");
            row.put("prod_dtcd", "");
            row.put("prod_itcd", "");
            row.put("prod_sale_nm", "");
        }
        if (record.get("상품명칭") != null) {
            row.put("상품명칭", record.get("상품명칭"));
        }
        for (String k : MapUtils.collectDetailKeys(record)) {
            Object v = record.get(k);
            row.put(k, v == null ? "" : v);
        }
        if (record.get("상품명") != null) {
            row.put("상품명", record.get("상품명"));
        }
        return row;
    }

    public LinkedHashMap<String, Object> productClassification(Map<String, Object> record, MappingRow m) {
        return baseRow(record, m);
    }

    public LinkedHashMap<String, Object> paymentCycle(Map<String, Object> record, MappingRow m) {
        LinkedHashMap<String, Object> row = baseRow(record, m);
        if (record.containsKey("납입주기")) {
            row.put("납입주기", record.get("납입주기"));
        }
        return row;
    }

    public LinkedHashMap<String, Object> annuityAge(Map<String, Object> record, MappingRow m) {
        LinkedHashMap<String, Object> row = baseRow(record, m);
        if (record.containsKey("보기개시나이정보")) {
            row.put("보기개시나이정보", record.get("보기개시나이정보"));
        }
        return row;
    }

    @SuppressWarnings("unchecked")
    public LinkedHashMap<String, Object> insurancePeriod(Map<String, Object> record, MappingRow m) {
        LinkedHashMap<String, Object> row = baseRow(record, m);
        if (record.containsKey("가입가능보기납기")) {
            Object raw = record.get("가입가능보기납기");
            List<Map<String, Object>> periods = (raw instanceof List)
                    ? (List<Map<String, Object>>) raw
                    : new ArrayList<>();
            String saleNm = m == null ? "" : m.isrnKindSaleNm;
            if (!saleNm.isEmpty()) {
                periods = applyMappedPeriodFilter(periods, saleNm);
            }
            row.put("가입가능보기납기", periods);
        }
        return row;
    }

    @SuppressWarnings("unchecked")
    public LinkedHashMap<String, Object> joinAge(Map<String, Object> record, MappingRow m) {
        LinkedHashMap<String, Object> row = baseRow(record, m);
        if (record.containsKey("가입가능나이")) {
            Object raw = record.get("가입가능나이");
            List<Map<String, Object>> ages = (raw instanceof List)
                    ? (List<Map<String, Object>>) raw
                    : new ArrayList<>();
            ages = dedupAgeGender(ages);
            String saleNm = m == null ? "" : m.isrnKindSaleNm;
            ages = applyMinAgeFloor(ages, saleNm);
            row.put("가입가능나이", ages);
        }
        return row;
    }

    // ---------- Overrides ----------

    private List<Map<String, Object>> applyMappedPeriodFilter(List<Map<String, Object>> periods, String saleNm) {
        if (overrides == null || periods.isEmpty()) return periods;
        String nfcNm = Normalizer.nfc(saleNm);
        for (Map.Entry<String, JsonNode> e : overrides.entries("insurance_period")) {
            JsonNode cfg = e.getValue();
            if (!cfg.isObject()) continue;
            if (!"filter".equals(cfg.path("action").asText(""))) continue;
            String[] keywords = e.getKey().split("\\+");
            boolean ok = true;
            for (String kw : keywords) {
                if (!nfcNm.contains(kw)) { ok = false; break; }
            }
            if (!ok) continue;

            List<Map<String, Object>> filtered = new ArrayList<>(periods);
            JsonNode incVals = cfg.get("include_납입기간값");
            if (incVals != null && incVals.isArray()) {
                Set<String> inc = new HashSet<>();
                for (JsonNode v : incVals) inc.add(v.asText());
                List<Map<String, Object>> tmp = new ArrayList<>();
                for (Map<String, Object> p : filtered) {
                    Object v = p.get("납입기간값");
                    if (v != null && inc.contains(String.valueOf(v))) tmp.add(p);
                }
                filtered = tmp;
            }
            JsonNode excl = cfg.get("exclude_combinations");
            if (excl != null && excl.isArray()) {
                for (JsonNode combo : excl) {
                    List<Map<String, Object>> tmp = new ArrayList<>();
                    for (Map<String, Object> p : filtered) {
                        boolean allMatch = true;
                        java.util.Iterator<Map.Entry<String, JsonNode>> it = combo.fields();
                        while (it.hasNext()) {
                            Map.Entry<String, JsonNode> kv = it.next();
                            Object pv = p.get(kv.getKey());
                            String target = kv.getValue().asText();
                            if (pv == null || !String.valueOf(pv).equals(target)) {
                                allMatch = false;
                                break;
                            }
                        }
                        if (!allMatch) tmp.add(p);
                    }
                    filtered = tmp;
                }
            }
            return filtered;
        }
        return periods;
    }

    private List<Map<String, Object>> applyMinAgeFloor(List<Map<String, Object>> ages, String saleNm) {
        if (overrides == null) return ages;
        String nfcNm = Normalizer.nfc(saleNm);
        for (Map.Entry<String, JsonNode> e : overrides.entries("join_age")) {
            JsonNode cfg = e.getValue();
            if (!cfg.isObject()) continue;
            if (!"min_age_floor".equals(cfg.path("action").asText(""))) continue;
            String[] keywords = e.getKey().split("\\+");
            boolean ok = true;
            for (String kw : keywords) {
                if (!nfcNm.contains(kw)) { ok = false; break; }
            }
            if (!ok) continue;
            int floor;
            try { floor = Integer.parseInt(cfg.path("min_age").asText("0")); }
            catch (NumberFormatException ex) { floor = 0; }
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> a : ages) {
                LinkedHashMap<String, Object> copy = new LinkedHashMap<>(a);
                Object cur = copy.get("최소가입나이");
                try {
                    int v = Integer.parseInt(cur == null ? "0" : String.valueOf(cur));
                    if (v < floor) copy.put("최소가입나이", String.valueOf(floor));
                } catch (NumberFormatException ex) {
                    // leave as-is
                }
                result.add(copy);
            }
            return result;
        }
        return ages;
    }

    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }

    private static List<Object> sigWithMin(Map<String, Object> a) {
        List<Object> out = new ArrayList<>();
        out.add(s(a.get("최소가입나이")));
        out.add(s(a.get("최대가입나이")));
        out.add(s(a.get("최소보험기간"))); out.add(s(a.get("최대보험기간"))); out.add(s(a.get("보험기간구분코드")));
        out.add(s(a.get("최소납입기간"))); out.add(s(a.get("최대납입기간"))); out.add(s(a.get("납입기간구분코드")));
        out.add(s(a.get("최소제2보기개시나이"))); out.add(s(a.get("최대제2보기개시나이"))); out.add(s(a.get("제2보기개시나이구분코드")));
        return out;
    }

    private static List<Map<String, Object>> dedupAgeGender(List<Map<String, Object>> ages) {
        Set<List<Object>> neutralSigs = new HashSet<>();
        for (Map<String, Object> a : ages) {
            if (s(a.get("성별")).isEmpty()) {
                neutralSigs.add(sigWithMin(a));
            }
        }
        if (neutralSigs.isEmpty()) return ages;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> a : ages) {
            if (!s(a.get("성별")).isEmpty()) {
                if (neutralSigs.contains(sigWithMin(a))) continue;
            }
            result.add(a);
        }
        return result;
    }
}
