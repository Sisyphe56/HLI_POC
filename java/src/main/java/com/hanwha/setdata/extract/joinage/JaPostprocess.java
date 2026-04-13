package com.hanwha.setdata.extract.joinage;

import com.fasterxml.jackson.databind.JsonNode;
import com.hanwha.setdata.config.OverridesConfig;
import com.hanwha.setdata.util.Normalizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Port of post-processing functions from the tail of extract_join_age_v2.py. */
public final class JaPostprocess {

    private JaPostprocess() {}

    /** Apply 실손의료비 override (variants-based) then postprocess. */
    public static List<Map<String, Object>> applySilsonAndPostprocess(
            List<Map<String, Object>> ageRecords, String productName, OverridesConfig cfg) {
        ageRecords = applySilsonOverride(ageRecords, productName, cfg);
        return applyPostprocess(ageRecords, productName, cfg);
    }

    public static List<Map<String, Object>> applySilsonOverride(
            List<Map<String, Object>> ageRecords, String productName, OverridesConfig cfg) {
        if (cfg == null) return ageRecords;
        JsonNode ja = cfg.section("join_age");
        JsonNode matchedCfg = null;
        Iterator<Map.Entry<String, JsonNode>> it = ja.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().startsWith("_")) continue;
            if (productName.contains(e.getKey())) {
                matchedCfg = e.getValue();
                break;
            }
        }
        if (matchedCfg == null || !matchedCfg.has("variants")) return ageRecords;

        String silsonMin = "0";
        String silsonMax = "";
        JsonNode variants = matchedCfg.get("variants");
        if (variants != null && variants.isArray()) {
            for (JsonNode variant : variants) {
                JsonNode matchArr = variant.get("match");
                if (matchArr == null || !matchArr.isArray()) continue;
                boolean all = true;
                String pnCompact = productName.replace(" ", "");
                for (JsonNode kwN : matchArr) {
                    String kw = kwN.asText();
                    if (!(productName.contains(kw) || pnCompact.contains(kw.replace(" ", "")))) {
                        all = false; break;
                    }
                }
                if (all) {
                    if (variant.has("min_age")) silsonMin = variant.get("min_age").asText();
                    if (variant.has("max_age")) silsonMax = variant.get("max_age").asText();
                    break;
                }
            }
        }

        if (silsonMax.isEmpty()) {
            var m = java.util.regex.Pattern.compile("(\\d+)세형",
                    java.util.regex.Pattern.UNICODE_CHARACTER_CLASS).matcher(productName);
            if (m.find()) silsonMax = String.valueOf(Integer.parseInt(m.group(1)) - 1);
        }

        if (!silsonMax.isEmpty()) {
            Map<String, Object> rec = newEmptyRecord();
            rec.put("최소가입나이", silsonMin);
            rec.put("최대가입나이", silsonMax);
            List<Map<String, Object>> out = new ArrayList<>();
            out.add(rec);
            return out;
        } else if (!ageRecords.isEmpty()) {
            List<Map<String, Object>> valid = new ArrayList<>();
            for (Map<String, Object> r : ageRecords) {
                String maxA = str(r.get("최대가입나이"));
                String minA = str(r.get("최소가입나이"));
                if (maxA.isEmpty() || !maxA.chars().allMatch(Character::isDigit)) continue;
                int maxI = Integer.parseInt(maxA);
                if (maxI <= 0) continue;
                if (!minA.isEmpty() && !minA.chars().allMatch(Character::isDigit)) continue;
                int minI = minA.isEmpty() ? 0 : Integer.parseInt(minA);
                if (maxI > minI) valid.add(r);
            }
            if (!valid.isEmpty()) {
                Map<String, Object> first = valid.get(0);
                String firstMin = "0".equals(silsonMin)
                        ? (str(first.get("최소가입나이")).isEmpty() ? "0" : str(first.get("최소가입나이")))
                        : silsonMin;
                Map<String, Object> rec = newEmptyRecord();
                rec.put("최소가입나이", firstMin);
                rec.put("최대가입나이", str(first.get("최대가입나이")));
                List<Map<String, Object>> out = new ArrayList<>();
                out.add(rec);
                return out;
            }
        }
        return ageRecords;
    }

    /** Port of {@code _apply_join_age_postprocess}. */
    public static List<Map<String, Object>> applyPostprocess(
            List<Map<String, Object>> ages, String productName, OverridesConfig cfg) {
        if (ages == null || ages.isEmpty()) return ages;
        String nfcName = Normalizer.nfc(productName);

        if (cfg != null) {
            JsonNode ja = cfg.section("join_age");
            Iterator<Map.Entry<String, JsonNode>> it = ja.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().startsWith("_")) continue;
                JsonNode ov = e.getValue();
                if (!ov.isObject()) continue;
                String action = ov.path("action").asText("");
                if (action.isEmpty()) continue;
                String[] kws = e.getKey().split("\\+");
                boolean all = true;
                for (String kw : kws) if (!nfcName.contains(kw)) { all = false; break; }
                if (!all) continue;
                if ("fixed".equals(action)) {
                    if (ov.has("ages")) {
                        List<Map<String, Object>> list = new ArrayList<>();
                        for (JsonNode n : ov.get("ages")) list.add(jsonToRec(n));
                        return list;
                    }
                    JsonNode tmpl = ov.get("ages_template");
                    if (tmpl != null) {
                        String insVal = "", insDvsn = "";
                        if (ov.path("ins_from_extract").asBoolean(false)) {
                            for (Map<String, Object> a : ages) {
                                String iv = str(a.get("최소보험기간"));
                                if (!iv.isEmpty()) {
                                    insVal = iv;
                                    insDvsn = str(a.get("보험기간구분코드"));
                                    break;
                                }
                            }
                        }
                        List<Map<String, Object>> result = new ArrayList<>();
                        JsonNode genders = tmpl.get("genders");
                        JsonNode payms = tmpl.get("payms");
                        String tmplMinAge = tmpl.path("min_age").asText("0");
                        String tmplMaxAge = tmpl.path("max_age").asText();
                        String paymCode = tmpl.path("paym_code").asText("N");
                        for (JsonNode gN : genders) {
                            for (JsonNode pN : payms) {
                                Map<String, Object> rec = newEmptyRecord();
                                rec.put("성별", gN.asText());
                                rec.put("최소가입나이", tmplMinAge);
                                rec.put("최대가입나이", tmplMaxAge);
                                rec.put("최소보험기간", insVal);
                                rec.put("최대보험기간", insVal);
                                rec.put("보험기간구분코드", insDvsn);
                                rec.put("최소납입기간", pN.asText());
                                rec.put("최대납입기간", pN.asText());
                                rec.put("납입기간구분코드", paymCode);
                                result.add(rec);
                            }
                        }
                        return result;
                    }
                }
            }
        }

        if (nfcName.contains("진심가득")) return postprocessJinsim(ages, nfcName);
        if (nfcName.contains("튼튼이") && nfcName.contains("갱신")) return postprocessTuntuni(ages);
        if (nfcName.contains("기업재해보장")) return mergePaymRanges(ages);

        if (cfg != null) {
            JsonNode kws = cfg.section("gender_collapse_targets").path("keywords");
            if (kws.isArray()) {
                for (JsonNode k : kws) {
                    if (nfcName.contains(k.asText())) {
                        ages = collapseIdenticalGender(ages);
                        break;
                    }
                }
            }
        }
        return ages;
    }

    // ── _merge_paym_ranges ────────────────────────────────────
    private static List<Map<String, Object>> mergePaymRanges(List<Map<String, Object>> ages) {
        LinkedHashMap<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> a : ages) {
            String key = str(a.get("최소보험기간")) + "|" + str(a.get("보험기간구분코드"))
                    + "|" + str(a.get("납입기간구분코드"));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(a);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (List<Map<String, Object>> group : groups.values()) {
            if (group.size() == 1) { result.add(group.get(0)); continue; }
            Set<String> paymCodes = new LinkedHashSet<>();
            for (Map<String, Object> r : group) paymCodes.add(str(r.get("납입기간구분코드")));
            if (paymCodes.size() > 1) { result.addAll(group); continue; }
            Map<String, Object> merged = new LinkedHashMap<>(group.get(0));
            Integer mn = null, mx = null;
            for (Map<String, Object> r : group) {
                String s = str(r.get("최소납입기간"));
                if (s.matches("\\d+")) {
                    int v = Integer.parseInt(s);
                    if (mn == null || v < mn) mn = v;
                }
                String s2 = str(r.get("최대납입기간"));
                if (s2.matches("\\d+")) {
                    int v = Integer.parseInt(s2);
                    if (mx == null || v > mx) mx = v;
                }
            }
            if (mn != null) merged.put("최소납입기간", String.valueOf(mn));
            if (mx != null) merged.put("최대납입기간", String.valueOf(mx));
            result.add(merged);
        }
        return result;
    }

    // ── _postprocess_jinsim ────────────────────────────────────
    private static List<Map<String, Object>> postprocessJinsim(List<Map<String, Object>> ages, String productName) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> deduped = new ArrayList<>();
        String[] sigFields = {
                "최소가입나이", "최대가입나이", "성별",
                "최소보험기간", "최대보험기간", "보험기간구분코드",
                "최소납입기간", "최대납입기간", "납입기간구분코드",
                "최소제2보기개시나이", "최대제2보기개시나이", "제2보기개시나이구분코드",
        };
        for (Map<String, Object> a : ages) {
            Map<String, Object> rec = new LinkedHashMap<>(a);
            rec.put("성별", "");
            StringBuilder sb = new StringBuilder();
            for (String f : sigFields) sb.append(str(rec.get(f))).append('|');
            String sig = sb.toString();
            if (!seen.contains(sig)) {
                seen.add(sig);
                deduped.add(rec);
            }
        }
        LinkedHashMap<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> rec : deduped) {
            String key = str(rec.get("최소보험기간")) + "|" + str(rec.get("보험기간구분코드"))
                    + "|" + str(rec.get("최소납입기간")) + "|" + str(rec.get("납입기간구분코드"));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(rec);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (List<Map<String, Object>> group : groups.values()) {
            if (group.size() == 1) { result.add(group.get(0)); continue; }
            Map<String, Object> best = null;
            int bestMax = Integer.MIN_VALUE;
            int minAge = Integer.MAX_VALUE;
            for (Map<String, Object> r : group) {
                int mx = parseIntSafe(str(r.get("최대가입나이")));
                if (mx > bestMax) { bestMax = mx; best = r; }
                int mn = parseIntSafe(str(r.get("최소가입나이")));
                if (mn < minAge) minAge = mn;
            }
            Map<String, Object> copy = new LinkedHashMap<>(best);
            copy.put("최소가입나이", String.valueOf(minAge));
            result.add(copy);
        }

        if (productName.contains("3종")) {
            Set<String> existing = new LinkedHashSet<>();
            for (Map<String, Object> r : result) {
                existing.add(str(r.get("최소보험기간")) + "|" + str(r.get("최소납입기간")) + "|" + str(r.get("납입기간구분코드")));
            }
            for (String ins : Arrays.asList("90", "100")) {
                for (String paym : Arrays.asList("5", "7")) {
                    if (!existing.contains(ins + "|" + paym + "|N")) {
                        Map<String, Object> rec = new LinkedHashMap<>();
                        rec.put("성별", "");
                        rec.put("최소가입나이", "0");
                        rec.put("최대가입나이", "35");
                        rec.put("최소보험기간", ins);
                        rec.put("최대보험기간", ins);
                        rec.put("보험기간구분코드", "X");
                        rec.put("최소납입기간", paym);
                        rec.put("최대납입기간", paym);
                        rec.put("납입기간구분코드", "N");
                        rec.put("최소제2보기개시나이", "");
                        rec.put("최대제2보기개시나이", "");
                        rec.put("제2보기개시나이구분코드", "");
                        result.add(rec);
                    }
                }
            }
        }
        return result;
    }

    // ── _postprocess_tuntuni ───────────────────────────────────
    private static List<Map<String, Object>> postprocessTuntuni(List<Map<String, Object>> ages) {
        Map<String, Object> best = null;
        int bestMax = Integer.MIN_VALUE;
        for (Map<String, Object> a : ages) {
            boolean hasDetail = !str(a.get("최소보험기간")).isEmpty() || !str(a.get("최소납입기간")).isEmpty();
            if (!hasDetail) continue;
            try {
                int mx = Integer.parseInt(str(a.get("최대가입나이")));
                if (best == null || mx > bestMax) { best = a; bestMax = mx; }
            } catch (NumberFormatException ignore) {}
        }
        if (best != null) {
            Map<String, Object> r = newEmptyRecord();
            r.put("최소가입나이", str(best.get("최소가입나이")));
            if (r.get("최소가입나이").toString().isEmpty()) r.put("최소가입나이", "0");
            r.put("최대가입나이", str(best.get("최대가입나이")));
            if (r.get("최대가입나이").toString().isEmpty()) r.put("최대가입나이", "0");
            List<Map<String, Object>> out = new ArrayList<>();
            out.add(r);
            return out;
        }
        return ages;
    }

    // ── _collapse_identical_gender ─────────────────────────────
    private static List<Map<String, Object>> collapseIdenticalGender(List<Map<String, Object>> ages) {
        String[] sigFields = {
                "최소가입나이", "최대가입나이",
                "최소보험기간", "최대보험기간", "보험기간구분코드",
                "최소납입기간", "최대납입기간", "납입기간구분코드",
                "최소제2보기개시나이", "최대제2보기개시나이", "제2보기개시나이구분코드",
        };
        LinkedHashMap<String, Map<String, Object>> maleBySig = new LinkedHashMap<>();
        LinkedHashMap<String, Map<String, Object>> femaleBySig = new LinkedHashMap<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> a : ages) {
            StringBuilder sb = new StringBuilder();
            for (String f : sigFields) sb.append(str(a.get(f))).append('|');
            String sig = sb.toString();
            String g = str(a.get("성별"));
            if ("1".equals(g)) maleBySig.put(sig, a);
            else if ("2".equals(g)) femaleBySig.put(sig, a);
            else result.add(a);
        }
        if (maleBySig.isEmpty() && femaleBySig.isEmpty()) return ages;
        Set<String> matched = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, Object>> e : maleBySig.entrySet()) {
            String sig = e.getKey();
            Map<String, Object> mRec = e.getValue();
            Map<String, Object> fRec = femaleBySig.get(sig);
            if (fRec != null && str(mRec.get("최소가입나이")).equals(str(fRec.get("최소가입나이")))) {
                Map<String, Object> merged = new LinkedHashMap<>(mRec);
                merged.put("성별", "");
                result.add(merged);
                matched.add(sig);
            } else {
                result.add(mRec);
            }
        }
        for (Map.Entry<String, Map<String, Object>> e : femaleBySig.entrySet()) {
            if (!matched.contains(e.getKey())) result.add(e.getValue());
        }
        return result;
    }

    // ── helpers ─────────────────────────────────────────────────
    public static Map<String, Object> newEmptyRecord() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("성별", "");
        r.put("최소가입나이", "");
        r.put("최대가입나이", "");
        r.put("최소납입기간", "");
        r.put("최대납입기간", "");
        r.put("납입기간구분코드", "");
        r.put("최소제2보기개시나이", "");
        r.put("최대제2보기개시나이", "");
        r.put("제2보기개시나이구분코드", "");
        r.put("최소보험기간", "");
        r.put("최대보험기간", "");
        r.put("보험기간구분코드", "");
        return r;
    }

    private static Map<String, Object> jsonToRec(JsonNode node) {
        Map<String, Object> rec = newEmptyRecord();
        if (!node.isObject()) return rec;
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            rec.put(e.getKey(), e.getValue().asText(""));
        }
        return rec;
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
