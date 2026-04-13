package com.hanwha.setdata.extract.period;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merges product-row data with extracted PDF period info to produce the final
 * per-row output dict. Ports {@code merge_period_info}, {@code build_record},
 * {@code is_annuity_product} from the Python module.
 */
public final class IpRecordBuilder {

    private static final int UC = Pattern.UNICODE_CHARACTER_CLASS;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IpRecordBuilder() {}

    public static boolean isAnnuityProduct(Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        for (Object v : row.values()) {
            if (v == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(v);
        }
        return sb.toString().contains("연금");
    }

    public static Map<String, Object> buildRecord(
            String insPeriod, String payPeriod,
            String minSpin, String maxSpin, String spinCode) {
        IpText.Period ins = IpText.formatPeriod(insPeriod);
        IpText.Period pay = IpText.formatPeriod(payPeriod);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("보험기간", ins.code());
        rec.put("보험기간구분코드", ins.kind());
        rec.put("보험기간값", ins.value());
        rec.put("납입기간", pay.code());
        rec.put("납입기간구분코드", pay.kind());
        rec.put("납입기간값", pay.value());
        rec.put("최소제2보기개시나이", minSpin == null ? "" : minSpin);
        rec.put("최대제2보기개시나이", maxSpin == null ? "" : maxSpin);
        rec.put("제2보기개시나이구분코드", spinCode == null ? "" : spinCode);
        return rec;
    }

    public static Map<String, Object> buildRecord(String insPeriod, String payPeriod) {
        return buildRecord(insPeriod, payPeriod, "", "", "");
    }

    private static final Pattern MANKI_NAME = Pattern.compile("(\\d+)\\s*년\\s*만기", UC);
    private static final Pattern FIXED_YEARS = Pattern.compile("(?:확정기간|환급플랜).*?(\\d+)\\s*년", UC);
    private static final Pattern JONG_PAT = Pattern.compile("(\\d종)", UC);

    public static Map<String, Object> mergePeriodInfo(
            Map<String, Object> row,
            List<String> pdfLines,
            List<List<List<String>>> pdfTables) {

        boolean isAnnuity = isAnnuityProduct(row);
        String productName = IpText.normalizeWs(String.valueOf(row.getOrDefault("상품명", "")));

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("상품명칭", IpText.normalizeWs(String.valueOf(row.getOrDefault("상품명칭", ""))));
        // sorted 세부종목N keys
        List<String> sebuKeys = new ArrayList<>();
        for (String k : row.keySet()) if (k.startsWith("세부종목")) sebuKeys.add(k);
        java.util.Collections.sort(sebuKeys);
        for (String k : sebuKeys) output.put(k, row.get(k));
        output.put("상품명", productName);
        output.put("가입가능보기납기", new ArrayList<>());

        Set<String> insPeriodsRaw = IpPeriods.extractInsurancePeriods(pdfLines, productName);
        IpPeriods.PayResult pr = IpPeriods.extractPaymentPeriods(pdfLines, productName);
        Set<String> payPeriodsRaw = new LinkedHashSet<>(pr.periods());
        boolean hasJeonginap = pr.hasJeonginap();

        // Fallback: table-based ins/pay extraction
        if (insPeriodsRaw.isEmpty() && pdfTables != null) {
            for (List<List<String>> tbl : pdfTables) {
                if (tbl.size() < 2) continue;
                List<String> header = new ArrayList<>();
                for (String c : tbl.get(0)) header.add(IpText.normalizeWs(c));
                int colIdx = header.indexOf("보험기간");
                if (colIdx < 0) continue;
                for (int ri = 1; ri < tbl.size(); ri++) {
                    List<String> dataRow = tbl.get(ri);
                    String val = colIdx < dataRow.size() ? IpText.normalizeWs(dataRow.get(colIdx)) : "";
                    Matcher m = Pattern.compile("(\\d+)\\s*년", UC).matcher(val);
                    if (m.find()) insPeriodsRaw.add(m.group(1) + "년");
                }
            }
        }
        if (payPeriodsRaw.isEmpty() && !hasJeonginap && pdfTables != null) {
            for (List<List<String>> tbl : pdfTables) {
                if (tbl.size() < 2) continue;
                List<String> header = new ArrayList<>();
                for (String c : tbl.get(0)) header.add(IpText.normalizeWs(c));
                int colIdx = header.indexOf("납입기간");
                if (colIdx < 0) continue;
                for (int ri = 1; ri < tbl.size(); ri++) {
                    List<String> dataRow = tbl.get(ri);
                    String val = colIdx < dataRow.size() ? IpText.normalizeWs(dataRow.get(colIdx)) : "";
                    if (val.contains("전기납")) hasJeonginap = true;
                }
            }
        }

        // Context filtering
        Map<String, Set<String>> payCtxMap = new LinkedHashMap<>();
        Map<String, Set<String>> insCtxMap = new LinkedHashMap<>();
        if (pdfTables != null && !productName.isEmpty()) {
            payCtxMap = IpPeriods.extractContextPeriodMap(pdfTables, "납입기간");
            insCtxMap = IpPeriods.extractContextPeriodMap(pdfTables, "보험기간");
        }
        if (payCtxMap.isEmpty() && !productName.isEmpty()) {
            payCtxMap = IpPeriods.extractTextContextPayMap(pdfLines);
        }
        if (!productName.isEmpty()) {
            Map<String, IpPeriods.SectionCtx> sectionCtx = IpPeriods.extractSectionContextMap(pdfLines);
            if (!sectionCtx.isEmpty()) {
                boolean hasGen = false, hasSs = false;
                for (String k : sectionCtx.keySet()) {
                    if (k.contains("일반형")) hasGen = true;
                    if (k.contains("상생협력형")) hasSs = true;
                }
                boolean hasTypeSeparation = hasGen && hasSs;
                Map<String, Set<String>> payFromSec = new LinkedHashMap<>();
                Map<String, Set<String>> insFromSec = new LinkedHashMap<>();
                for (Map.Entry<String, IpPeriods.SectionCtx> e : sectionCtx.entrySet()) {
                    if (!e.getValue().pay().isEmpty()) payFromSec.put(e.getKey(), e.getValue().pay());
                    if (!e.getValue().ins().isEmpty()) insFromSec.put(e.getKey(), e.getValue().ins());
                }
                if (hasTypeSeparation) {
                    if (!payFromSec.isEmpty()) payCtxMap = payFromSec;
                    if (!insFromSec.isEmpty()) insCtxMap = insFromSec;
                } else {
                    if (payCtxMap.isEmpty() && !payFromSec.isEmpty()) payCtxMap = payFromSec;
                    if (insCtxMap.isEmpty() && !insFromSec.isEmpty()) insCtxMap = insFromSec;
                }
            }
        }
        if (!payCtxMap.isEmpty()) {
            payPeriodsRaw = new LinkedHashSet<>(
                    IpPeriods.filterPeriodsByContext(payPeriodsRaw, payCtxMap, productName));
        }
        if (!insCtxMap.isEmpty()) {
            insPeriodsRaw = new LinkedHashSet<>(
                    IpPeriods.filterPeriodsByContext(insPeriodsRaw, insCtxMap, productName));
        }

        // 전기납 context filter
        if (hasJeonginap && !payCtxMap.isEmpty()) {
            boolean hasGen = false, hasSs2 = false;
            for (String k : payCtxMap.keySet()) {
                if (k.contains("일반형")) hasGen = true;
                if (k.contains("상생협력형")) hasSs2 = true;
            }
            if (hasGen && hasSs2) {
                boolean nameHasSs = IpText.normalizeWs(productName).contains("상생");
                String nameCompact = IpText.stripAllWs(IpText.normalizeWs(productName));
                Set<String> matchedPayCtx = new LinkedHashSet<>();
                for (Map.Entry<String, Set<String>> e : payCtxMap.entrySet()) {
                    String ctxKey = e.getKey();
                    if (ctxKey.contains("상생협력형")) {
                        if (nameHasSs) matchedPayCtx.addAll(e.getValue());
                    } else if (ctxKey.contains("일반형")) {
                        if (!nameHasSs) matchedPayCtx.addAll(e.getValue());
                    } else {
                        String ctxCompact = IpText.stripAllWs(ctxKey);
                        if (nameCompact.contains(ctxCompact)) matchedPayCtx.addAll(e.getValue());
                    }
                }
                if (!matchedPayCtx.isEmpty()) {
                    boolean anyJeong = false;
                    for (String p : matchedPayCtx) if (p.contains("전기납")) { anyJeong = true; break; }
                    if (!anyJeong) hasJeonginap = false;
                }
            }
        }

        // 갱신형 N년만기 filter
        Matcher mankiM = MANKI_NAME.matcher(productName);
        if (mankiM.find()) {
            String combined = String.valueOf(output.getOrDefault("상품명칭", "")) + " " + productName;
            if (combined.contains("갱신")) {
                String mankiVal = mankiM.group(1) + "년";
                Set<String> insFiltered = new LinkedHashSet<>();
                for (String p : insPeriodsRaw) if (p.contains(mankiVal)) insFiltered.add(p);
                if (!insFiltered.isEmpty()) insPeriodsRaw = insFiltered;
                Set<String> payFiltered = new LinkedHashSet<>();
                for (String p : payPeriodsRaw) if (p.contains(mankiVal)) payFiltered.add(p);
                if (!payFiltered.isEmpty()) payPeriodsRaw = payFiltered;
            }
        }

        // 종신보험 default
        if (insPeriodsRaw.isEmpty()) {
            String nameCombined = String.valueOf(output.getOrDefault("상품명칭", "")) + " " + productName;
            if (nameCombined.contains("종신") && !nameCombined.contains("연금")) {
                insPeriodsRaw.add("종신");
            }
        }
        if (payPeriodsRaw.isEmpty() && !hasJeonginap) payPeriodsRaw.add("");
        if (insPeriodsRaw.isEmpty()) insPeriodsRaw.add("");

        List<Map<String, Object>> records = new ArrayList<>();

        if (isAnnuity) {
            boolean isImmediate = productName.contains("즉시형") || productName.contains("거치형");
            if (isImmediate) {
                payPeriodsRaw = new LinkedHashSet<>();
                payPeriodsRaw.add("일시납");
                hasJeonginap = false;
            } else {
                Set<String> rem = new LinkedHashSet<>(payPeriodsRaw);
                rem.remove("일시납"); rem.remove("");
                if (!rem.isEmpty()) payPeriodsRaw.remove("일시납");
            }

            IpAnnuity.AgeRange age;
            if (productName.contains("종신연금") && pdfTables != null) {
                age = IpAnnuity.extractAnnuityAgeRangeByType(pdfTables, "종신연금형", "");
                if (age.min() == null) age = IpAnnuity.extractAnnuityAgeRange(pdfLines);
            } else if ((productName.contains("확정기간") || productName.contains("환급플랜")) && pdfTables != null) {
                Matcher jm = JONG_PAT.matcher(productName);
                String jongCtx = jm.find() ? jm.group(1) : "";
                age = IpAnnuity.extractAnnuityAgeRangeByType(pdfTables, "확정기간연금형", jongCtx);
                if (age.min() == null) age = IpAnnuity.extractAnnuityAgeRangeByType(pdfTables, "확정기간연금형", "");
                if (age.min() == null) age = IpAnnuity.extractAnnuityAgeRangeByType(pdfTables, "종신연금형", "");
                if (age.min() == null) age = IpAnnuity.extractAnnuityAgeRange(pdfLines);
            } else {
                age = IpAnnuity.extractAnnuityAgeRange(pdfLines);
            }
            Integer minAge = age.min(), maxAge = age.max();

            Integer extraMinAge = null, extraMaxAge = null;
            if (productName.contains("신부부형")) {
                IpAnnuity.AgeRange sub = IpAnnuity.extractAnnuityAgeRangeBySubtype(pdfLines, "신부부형");
                if (sub.min() != null && !sub.min().equals(minAge)) {
                    extraMinAge = sub.min();
                    extraMaxAge = sub.max();
                }
            }

            String annuityInsPeriod = "";
            if (productName.contains("종신연금") || productName.contains("종신플랜")) {
                annuityInsPeriod = "종신";
            } else {
                Matcher fy = FIXED_YEARS.matcher(productName);
                if (fy.find()) {
                    int fixedYears = Integer.parseInt(fy.group(1));
                    if (minAge != null && maxAge != null) {
                        for (int a = minAge; a <= maxAge; a++) {
                            int insAge = a + fixedYears;
                            for (String payP : payPeriodsRaw) {
                                if (!payP.isEmpty()) {
                                    records.add(buildRecord(insAge + "세", payP,
                                            String.valueOf(a), String.valueOf(a), "X"));
                                }
                            }
                            if (hasJeonginap) {
                                records.add(buildRecord(insAge + "세", a + "세납",
                                        String.valueOf(a), String.valueOf(a), "X"));
                            }
                        }
                    }
                }
            }

            if (!annuityInsPeriod.isEmpty() || records.isEmpty()) {
                String insP = annuityInsPeriod.isEmpty() ? "종신" : annuityInsPeriod;
                for (String payP : payPeriodsRaw) {
                    if (!payP.isEmpty()) {
                        IpText.Period payFmt = IpText.formatPeriod(payP);
                        boolean isLump = "N0".equals(payFmt.code());
                        String minS = isLump ? "" : (minAge == null ? "" : String.valueOf(minAge));
                        String maxS = isLump ? "" : (maxAge == null ? "" : String.valueOf(maxAge));
                        String spinCode = isLump ? "" : (minAge != null ? "X" : "");
                        records.add(buildRecord(insP, payP, minS, maxS, spinCode));
                        if (extraMinAge != null && !isLump) {
                            records.add(buildRecord(insP, payP,
                                    String.valueOf(extraMinAge),
                                    String.valueOf(extraMaxAge), "X"));
                        }
                    }
                }
                if (hasJeonginap && minAge != null && maxAge != null) {
                    for (int a = minAge; a <= maxAge; a++) {
                        records.add(buildRecord(insP, a + "세납",
                                String.valueOf(a), String.valueOf(a), "X"));
                    }
                }
            }
        } else {
            for (String insP : insPeriodsRaw) {
                for (String payP : payPeriodsRaw) {
                    if (!payP.isEmpty()) records.add(buildRecord(insP, payP));
                }
                if (hasJeonginap && !insP.isEmpty()) {
                    records.add(buildRecord(insP, insP));
                }
            }
            boolean insEmpty = insPeriodsRaw.isEmpty()
                    || (insPeriodsRaw.size() == 1 && insPeriodsRaw.contains(""));
            if (insEmpty) {
                for (String payP : payPeriodsRaw) {
                    if (!payP.isEmpty()) records.add(buildRecord("", payP));
                }
            }
        }

        // Matrix invalid pairs
        Set<IpPeriods.Pair> matrixInvalid = IpPeriods.extractMatrixInvalidPairs(pdfLines);
        if (matrixInvalid.isEmpty() && pdfTables != null) {
            matrixInvalid = IpPeriods.extractMatrixInvalidPairsFromTables(pdfTables);
        }

        // Apply 납입≤보험 constraint + matrix invalid
        List<Map<String, Object>> filteredRecs = new ArrayList<>();
        for (Map<String, Object> rec : records) {
            String insCode = String.valueOf(rec.getOrDefault("보험기간구분코드", ""));
            String payCode = String.valueOf(rec.getOrDefault("납입기간구분코드", ""));
            String insVal = String.valueOf(rec.getOrDefault("보험기간값", ""));
            String payVal = String.valueOf(rec.getOrDefault("납입기간값", ""));
            boolean skip = false;

            if ("N".equals(insCode) && "X".equals(payCode)) skip = true;
            if ("X".equals(insCode) && "N".equals(payCode)
                    && !insVal.isEmpty() && !payVal.isEmpty()) {
                try {
                    if (Integer.parseInt(insVal) < 70 && Integer.parseInt(payVal) > 20) skip = true;
                } catch (NumberFormatException ignored) {}
            }
            if (insCode.equals(payCode) && ("N".equals(insCode) || "X".equals(insCode))
                    && !insVal.isEmpty() && !payVal.isEmpty()) {
                try {
                    if (Integer.parseInt(payVal) > Integer.parseInt(insVal) && !"999".equals(insVal)) {
                        skip = true;
                    }
                } catch (NumberFormatException ignored) {}
            }
            if (!skip && !matrixInvalid.isEmpty()) {
                String insRaw = String.valueOf(rec.getOrDefault("보험기간", ""));
                String payRaw = String.valueOf(rec.getOrDefault("납입기간", ""));
                String insText;
                if ("N".equals(insCode) && !insVal.isEmpty()) insText = insVal + "년";
                else if ("X".equals(insCode) && !insVal.isEmpty()) insText = insVal + "세";
                else insText = "";
                String payText;
                if ("N".equals(payCode) && !payVal.isEmpty() && !"0".equals(payVal)) payText = payVal + "년납";
                else if ("X".equals(payCode) && !payVal.isEmpty()) payText = payVal + "세납";
                else if ("N".equals(payCode) && "0".equals(payVal)) payText = "일시납";
                else payText = "";

                if (insRaw.equals(payRaw) && !insText.isEmpty()) {
                    if (matrixInvalid.contains(new IpPeriods.Pair(insText, "전기납"))) skip = true;
                } else if (!insText.isEmpty() && !payText.isEmpty()
                        && matrixInvalid.contains(new IpPeriods.Pair(insText, payText))) {
                    skip = true;
                }
            }
            if (!skip) filteredRecs.add(rec);
        }

        // Dedupe (by canonical JSON with sorted keys)
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> unique = new ArrayList<>();
        for (Map<String, Object> rec : filteredRecs) {
            String key = canonicalKey(rec);
            if (!seen.contains(key)) {
                seen.add(key);
                unique.add(rec);
            }
        }
        output.put("가입가능보기납기", unique);
        if (unique.isEmpty()) {
            unique.add(buildRecord("", ""));
        }
        return output;
    }

    private static String canonicalKey(Map<String, Object> rec) {
        // mimic Python json.dumps(rec, sort_keys=True)
        Map<String, Object> sorted = new java.util.TreeMap<>(rec);
        try {
            return MAPPER.writeValueAsString(sorted);
        } catch (Exception e) {
            return sorted.toString();
        }
    }
}
