package com.hanwha.setdata.extract.annuity;

import com.fasterxml.jackson.databind.JsonNode;
import com.hanwha.setdata.config.OverridesConfig;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Port of {@code merge_records} and {@code _apply_annuity_overrides}. */
public final class AaMerge {

    private AaMerge() {}

    public static LinkedHashMap<String, Object> emptyRecord(String gender) {
        LinkedHashMap<String, Object> r = new LinkedHashMap<>();
        r.put("제1보기개시나이", "");
        r.put("제1보기개시나이구분코드", "");
        r.put("제1보기개시나이값", "");
        r.put("제2보기개시나이", "");
        r.put("제2보기개시나이구분코드", "");
        r.put("제2보기개시나이값", "");
        r.put("제3보기개시나이", "");
        r.put("제3보기개시나이구분코드", "");
        r.put("제3보기개시나이값", "");
        r.put("성별", gender);
        return r;
    }

    public static LinkedHashMap<String, Object> mergeRecords(
            Map<String, Object> row,
            List<AaBlocks.Block> annuityBlocks,
            List<AaEscalation.Pair> escalations) {
        LinkedHashMap<String, Object> output = new LinkedHashMap<>();
        output.put("상품명칭", AaText.normalizeWs(str(row.get("상품명칭"))));

        // sorted 세부종목*
        TreeMap<String, Object> sub = new TreeMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().startsWith("세부종목")) sub.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Object> e : sub.entrySet()) {
            output.put(e.getKey(), e.getValue());
        }
        output.put("상품명", AaText.normalizeWs(str(row.get("상품명"))));
        List<LinkedHashMap<String, Object>> infos = new ArrayList<>();
        output.put("보기개시나이정보", infos);

        if (AaText.isAnnuityRow(row)) {
            AaPicker.Ages ages = AaPicker.pickForRow(row, annuityBlocks);
            if (ages.male.isEmpty() && ages.female.isEmpty() && ages.common.isEmpty()) {
                infos.add(emptyRecord(""));
                return output;
            }

            List<String> maleVals = new ArrayList<>(ages.male);
            List<String> femaleVals = new ArrayList<>(ages.female);
            List<String> commonVals = new ArrayList<>(ages.common);

            if (!maleVals.isEmpty() && !femaleVals.isEmpty()
                    && new LinkedHashSet<>(maleVals).equals(new LinkedHashSet<>(femaleVals))) {
                for (String v : maleVals) {
                    if (!commonVals.contains(v)) commonVals.add(v);
                }
                maleVals = new ArrayList<>();
                femaleVals = new ArrayList<>();
            } else if ((!maleVals.isEmpty() || !femaleVals.isEmpty()) && !commonVals.isEmpty()) {
                String productName = AaText.normalizeWs(str(row.get("상품명")));
                if (productName.contains("신부부형")) {
                    commonVals = new ArrayList<>();
                } else {
                    maleVals = new ArrayList<>();
                    femaleVals = new ArrayList<>();
                }
            }

            for (String gender : new String[]{"남자", "여자"}) {
                List<String> vals = gender.equals("남자") ? maleVals : femaleVals;
                for (String raw : vals) {
                    String value = AaText.toAgeValue(raw);
                    if (value.isEmpty()) continue;
                    LinkedHashMap<String, Object> rec = emptyRecord(gender);
                    rec.put("제2보기개시나이", "X" + value);
                    rec.put("제2보기개시나이구분코드", "X");
                    rec.put("제2보기개시나이값", value);
                    infos.add(rec);
                }
            }

            for (String raw : commonVals) {
                String value = AaText.toAgeValue(raw);
                if (value.isEmpty()) continue;
                LinkedHashMap<String, Object> rec = emptyRecord("");
                rec.put("제2보기개시나이", "X" + value);
                rec.put("제2보기개시나이구분코드", "X");
                rec.put("제2보기개시나이값", value);
                infos.add(rec);
            }

            if (infos.isEmpty()) infos.add(emptyRecord(""));
            return output;
        }

        if (AaText.isLifeOrEscalationRow(row)) {
            AaEscalation.Pair pick = AaEscalation.pickForRow(row, escalations);
            LinkedHashMap<String, Object> rec = emptyRecord("");
            if (!pick.start.isEmpty()) {
                rec.put("제2보기개시나이", "N" + pick.start);
                rec.put("제2보기개시나이구분코드", "N");
                rec.put("제2보기개시나이값", pick.start);
            }
            if (!pick.end.isEmpty()) {
                String endVal;
                if (pick.absolute) {
                    endVal = pick.end;
                } else {
                    try {
                        endVal = String.valueOf(Integer.parseInt(pick.start) + Integer.parseInt(pick.end));
                    } catch (NumberFormatException e) {
                        endVal = pick.end;
                    }
                }
                rec.put("제3보기개시나이", "N" + endVal);
                rec.put("제3보기개시나이구분코드", "N");
                rec.put("제3보기개시나이값", endVal);
            }
            infos.add(rec);
            return output;
        }

        infos.add(emptyRecord(""));
        return output;
    }

    @SuppressWarnings("unchecked")
    public static List<LinkedHashMap<String, Object>> applyOverrides(
            List<LinkedHashMap<String, Object>> results, OverridesConfig cfg) {
        if (cfg == null) return results;
        JsonNode aa = cfg.section("annuity_age_overrides");
        if (aa == null || !aa.isObject()) return results;

        for (LinkedHashMap<String, Object> r : results) {
            String productName = str(r.get("상품명"));
            List<LinkedHashMap<String, Object>> ages =
                    (List<LinkedHashMap<String, Object>>) r.get("보기개시나이정보");
            if (ages == null) ages = new ArrayList<>();

            Iterator<Map.Entry<String, JsonNode>> fields = aa.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                String key = e.getKey();
                if (key.startsWith("_")) continue;
                String[] keywords = key.split("\\+");
                boolean all = true;
                for (String kw : keywords) {
                    if (!productName.contains(kw)) { all = false; break; }
                }
                if (!all) continue;
                JsonNode spec = e.getValue();
                String action = spec.path("action").asText("fixed");

                if ("fixed".equals(action)) {
                    List<LinkedHashMap<String, Object>> newAges = new ArrayList<>();
                    JsonNode agesNode = spec.get("ages");
                    if (agesNode != null && agesNode.isArray()) {
                        for (JsonNode a : agesNode) {
                            LinkedHashMap<String, Object> rec = new LinkedHashMap<>();
                            Iterator<Map.Entry<String, JsonNode>> it = a.fields();
                            while (it.hasNext()) {
                                Map.Entry<String, JsonNode> kv = it.next();
                                rec.put(kv.getKey(), kv.getValue().asText(""));
                            }
                            newAges.add(rec);
                        }
                    }
                    r.put("보기개시나이정보", newAges);
                } else if ("gender_split".equals(action)) {
                    int maleMin = Integer.parseInt(spec.path("male_min_age").asText("0"));
                    int femaleMax = Integer.parseInt(spec.path("female_max_age").asText("9999"));
                    List<LinkedHashMap<String, Object>> newAges = new ArrayList<>();
                    for (LinkedHashMap<String, Object> a : ages) {
                        String g = str(a.get("성별"));
                        if (!g.isEmpty()) {
                            newAges.add(a);
                            continue;
                        }
                        String val = str(a.get("제2보기개시나이값"));
                        if (val.isEmpty()) continue;
                        int ageVal;
                        try { ageVal = Integer.parseInt(val); } catch (NumberFormatException ex) { continue; }
                        if (ageVal <= femaleMax) {
                            LinkedHashMap<String, Object> fRec = new LinkedHashMap<>(a);
                            fRec.put("성별", "여자");
                            newAges.add(fRec);
                        }
                        if (ageVal >= maleMin) {
                            LinkedHashMap<String, Object> mRec = new LinkedHashMap<>(a);
                            mRec.put("성별", "남자");
                            newAges.add(mRec);
                        }
                    }
                    r.put("보기개시나이정보", newAges);
                }
                break;
            }
        }
        return results;
    }

    private static String str(Object v) { return v == null ? "" : v.toString(); }
}
