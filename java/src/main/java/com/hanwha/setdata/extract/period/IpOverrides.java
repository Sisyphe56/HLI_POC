package com.hanwha.setdata.extract.period;

import com.fasterxml.jackson.databind.JsonNode;
import com.hanwha.setdata.config.OverridesConfig;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies product_overrides.json insurance_period overrides + sibling_fallback.
 * Ports {@code _apply_period_overrides} from the Python module.
 */
public final class IpOverrides {

    private IpOverrides() {}

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> applyPeriodOverrides(
            List<Map<String, Object>> results, OverridesConfig config) {
        if (config == null) return results;
        JsonNode ipOverrides = config.section("insurance_period");
        JsonNode siblingCfg = config.section("sibling_fallback");
        JsonNode suffixPatterns = siblingCfg == null ? null : siblingCfg.get("suffix_patterns");

        // Build lookup
        Map<String, List<Map<String, Object>>> nameToPeriods = new LinkedHashMap<>();
        for (Map<String, Object> r : results) {
            String name = String.valueOf(r.getOrDefault("상품명", ""));
            Object periodsObj = r.get("가입가능보기납기");
            if (periodsObj instanceof List) {
                nameToPeriods.put(name, (List<Map<String, Object>>) periodsObj);
            } else {
                nameToPeriods.put(name, new ArrayList<>());
            }
        }

        for (Map<String, Object> r : results) {
            String productName = String.valueOf(r.getOrDefault("상품명", ""));
            List<Map<String, Object>> periods = (List<Map<String, Object>>) r.getOrDefault("가입가능보기납기", new ArrayList<>());
            boolean applied = false;

            if (ipOverrides != null && ipOverrides.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = ipOverrides.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    String key = e.getKey();
                    if (key.startsWith("_")) continue;
                    String[] keywords = key.split("\\+");
                    boolean allMatch = true;
                    for (String kw : keywords) {
                        if (!productName.contains(kw)) { allMatch = false; break; }
                    }
                    if (!allMatch) continue;

                    JsonNode cfg = e.getValue();
                    String action = cfg.path("action").asText("fixed");

                    if ("fixed".equals(action)) {
                        List<Map<String, Object>> newPeriods = new ArrayList<>();
                        JsonNode arr = cfg.get("periods");
                        if (arr != null && arr.isArray()) {
                            for (JsonNode p : arr) newPeriods.add(jsonToMap(p));
                        }
                        r.put("가입가능보기납기", newPeriods);
                        applied = true;
                    } else if ("sibling_filter".equals(action)) {
                        List<String> matchKws = toList(cfg.get("sibling_match"));
                        List<String> excludeKws = toList(cfg.get("sibling_exclude"));
                        JsonNode filt = cfg.get("filter");
                        for (Map.Entry<String, List<Map<String, Object>>> entry : nameToPeriods.entrySet()) {
                            String name = entry.getKey();
                            List<Map<String, Object>> pdata = entry.getValue();
                            boolean allM = true;
                            for (String m : matchKws) if (!name.contains(m)) { allM = false; break; }
                            boolean anyEx = false;
                            for (String ex : excludeKws) if (name.contains(ex)) { anyEx = true; break; }
                            if (allM && !anyEx && pdata != null && !pdata.isEmpty()) {
                                List<Map<String, Object>> filtered = new ArrayList<>(pdata);
                                if (filt != null) {
                                    String insKind = filt.path("보험기간구분코드").asText("");
                                    if (!insKind.isEmpty()) {
                                        List<Map<String, Object>> next = new ArrayList<>();
                                        for (Map<String, Object> p : filtered) {
                                            if (insKind.equals(String.valueOf(p.getOrDefault("보험기간구분코드", "")))) next.add(p);
                                        }
                                        filtered = next;
                                    }
                                    JsonNode vals = filt.get("보험기간값");
                                    if (vals != null && vals.isArray()) {
                                        List<String> valList = new ArrayList<>();
                                        for (JsonNode v : vals) valList.add(v.asText());
                                        List<Map<String, Object>> next = new ArrayList<>();
                                        for (Map<String, Object> p : filtered) {
                                            if (valList.contains(String.valueOf(p.getOrDefault("보험기간값", "")))) next.add(p);
                                        }
                                        filtered = next;
                                    } else if (vals != null && vals.isTextual()) {
                                        List<Map<String, Object>> next = new ArrayList<>();
                                        for (Map<String, Object> p : filtered) {
                                            if (vals.asText().equals(String.valueOf(p.getOrDefault("보험기간값", "")))) next.add(p);
                                        }
                                        filtered = next;
                                    }
                                    if (filt.path("exclude_jeonginap").asBoolean(false)) {
                                        List<Map<String, Object>> next = new ArrayList<>();
                                        for (Map<String, Object> p : filtered) {
                                            if (!String.valueOf(p.getOrDefault("납입기간", ""))
                                                    .equals(String.valueOf(p.getOrDefault("보험기간", "")))) next.add(p);
                                        }
                                        filtered = next;
                                    }
                                }
                                r.put("가입가능보기납기", filtered);
                                applied = true;
                                break;
                            }
                        }
                    } else if ("sibling_copy".equals(action)) {
                        if (periods.isEmpty()) {
                            List<String> matchKws = toList(cfg.get("sibling_match"));
                            List<String> anyKws = toList(cfg.get("sibling_any"));
                            for (Map.Entry<String, List<Map<String, Object>>> entry : nameToPeriods.entrySet()) {
                                String name = entry.getKey();
                                List<Map<String, Object>> pdata = entry.getValue();
                                boolean allM = true;
                                for (String m : matchKws) if (!name.contains(m)) { allM = false; break; }
                                boolean anyM = false;
                                for (String a : anyKws) if (name.contains(a)) { anyM = true; break; }
                                if (allM && anyM && pdata != null && !pdata.isEmpty()) {
                                    r.put("가입가능보기납기", new ArrayList<>(pdata));
                                    applied = true;
                                    break;
                                }
                            }
                        }
                    } else if ("filter".equals(action)) {
                        if (!periods.isEmpty()) {
                            List<Map<String, Object>> filtered = new ArrayList<>(periods);
                            JsonNode excCombos = cfg.get("exclude_combinations");
                            if (excCombos != null && excCombos.isArray()) {
                                for (JsonNode combo : excCombos) {
                                    List<Map<String, Object>> next = new ArrayList<>();
                                    for (Map<String, Object> p : filtered) {
                                        boolean allMatchCombo = true;
                                        Iterator<Map.Entry<String, JsonNode>> ci = combo.fields();
                                        while (ci.hasNext()) {
                                            Map.Entry<String, JsonNode> cc = ci.next();
                                            if (!cc.getValue().asText().equals(String.valueOf(p.getOrDefault(cc.getKey(), "")))) {
                                                allMatchCombo = false; break;
                                            }
                                        }
                                        if (!allMatchCombo) next.add(p);
                                    }
                                    filtered = next;
                                }
                            }
                            JsonNode incVals = cfg.get("include_납입기간값");
                            if (incVals != null && incVals.isArray()) {
                                List<String> valList = new ArrayList<>();
                                for (JsonNode v : incVals) valList.add(v.asText());
                                List<Map<String, Object>> next = new ArrayList<>();
                                for (Map<String, Object> p : filtered) {
                                    if (valList.contains(String.valueOf(p.getOrDefault("납입기간값", "")))) next.add(p);
                                }
                                filtered = next;
                            }
                            r.put("가입가능보기납기", filtered);
                            applied = true;
                        }
                    } else if ("add_annuity_start_age".equals(action)) {
                        if (!periods.isEmpty()) {
                            String additionalMin = cfg.path("additional_min_age").asText("");
                            List<Map<String, Object>> newPeriods = new ArrayList<>(periods);
                            for (Map<String, Object> p : periods) {
                                String minSpin = String.valueOf(p.getOrDefault("최소제2보기개시나이", ""));
                                String payKind = String.valueOf(p.getOrDefault("납입기간구분코드", ""));
                                if (!minSpin.isEmpty() && !minSpin.equals(additionalMin) && "N".equals(payKind)) {
                                    Map<String, Object> dup = new LinkedHashMap<>(p);
                                    dup.put("최소제2보기개시나이", additionalMin);
                                    newPeriods.add(dup);
                                }
                            }
                            r.put("가입가능보기납기", newPeriods);
                            applied = true;
                        }
                    }

                    if (applied) break;
                }
            }

            // sibling fallback
            if (!applied) {
                Object pObj = r.get("가입가능보기납기");
                boolean empty = !(pObj instanceof List) || ((List<?>) pObj).isEmpty();
                if (empty && suffixPatterns != null && suffixPatterns.isArray()) {
                    for (JsonNode pat : suffixPatterns) {
                        String s = pat.asText("");
                        String[] parts = s.split("\\|");
                        if (parts.length != 2) continue;
                        String src = parts[0], dst = parts[1];
                        if (productName.contains(src)) {
                            String baseName = productName.replace(src, dst);
                            List<Map<String, Object>> baseData = nameToPeriods.get(baseName);
                            if (baseData != null && !baseData.isEmpty()) {
                                r.put("가입가능보기납기", new ArrayList<>(baseData));
                                break;
                            }
                        }
                    }
                }
            }
        }
        return results;
    }

    private static List<String> toList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) out.add(n.asText());
        }
        return out;
    }

    private static Map<String, Object> jsonToMap(JsonNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                if (v.isTextual()) m.put(e.getKey(), v.asText());
                else if (v.isNumber()) m.put(e.getKey(), v.numberValue());
                else if (v.isBoolean()) m.put(e.getKey(), v.booleanValue());
                else m.put(e.getKey(), v.asText());
            }
        }
        return m;
    }
}
