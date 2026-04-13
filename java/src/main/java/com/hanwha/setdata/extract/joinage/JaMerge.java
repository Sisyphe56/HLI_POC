package com.hanwha.setdata.extract.joinage;

import com.hanwha.setdata.config.OverridesConfig;
import com.hanwha.setdata.extract.period.IpText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of {@code merge_join_age_info} — the central orchestration. */
public final class JaMerge {

    private JaMerge() {}

    private static final int UF = Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern MIN_AGE_OVERRIDE = Pattern.compile(
            "단,?\\s*(.+?)의\\s*경우\\s*가입최저나이[는은]\\s*(\\d+)\\s*세", UF);
    private static final Pattern SANGSAENG_SEC1 = Pattern.compile("상생협력형\\s*$", UF);
    private static final Pattern SANGSAENG_SEC2 = Pattern.compile("^\\s*\\d\\)\\s*상생협력형", UF);
    private static final Pattern SANGSAENG_SEC_END1 = Pattern.compile("^\\s*\\d\\)\\s", UF);
    private static final Pattern SANGSAENG_SEC_END2 = Pattern.compile("^\\s*[\\(\\（]", UF);
    private static final Pattern SANGSAENG_MIN = Pattern.compile("최저가입나이\\s*[:：]?\\s*(\\d+)\\s*세", UF);
    private static final Pattern SANGSAENG_MAX = Pattern.compile("최고가입나이\\s*[:：]?\\s*(\\d+)\\s*세", UF);
    private static final Pattern SECTION_NUMERIC = Pattern.compile("^\\s*[\\(\\（](\\d+)\\s*[\\)\\）]\\s*(.+)", UF);
    private static final Pattern SUBSEC_NUMBER = Pattern.compile("^\\s*\\d+\\)\\s+", UF);
    private static final Pattern HEAD_NA = Pattern.compile("^\\s*나\\.\\s", UF);
    private static final Pattern HYUNG_PAT = Pattern.compile("(\\d+)형", UF);
    private static final Pattern JONG_PAT = Pattern.compile("(\\d+)종", UF);
    private static final Pattern ANNUITY_TYPE = Pattern.compile("(종신연금형|확정기간연금형\\s*\\d+년)", UF);
    private static final Pattern INLINE_RANGE = Pattern.compile(
            "만?\\s*(\\d+)\\s*세?\\s*[~～\\-]\\s*(\\d+)\\s*세", UF);
    private static final Pattern YEAR_NAP = Pattern.compile("(\\d+)\\s*년납", UF);

    public static LinkedHashMap<String, Object> merge(
            Map<String, Object> row,
            List<String> pdfLines,
            List<List<List<String>>> pdfTables,
            List<Map<String, Object>> periodData,
            OverridesConfig cfg) {

        String productName = JaText.normalizeWs(str(row.get("상품명")));
        boolean isAnnuity = JaText.isAnnuityProduct(row);

        LinkedHashMap<String, Object> output = new LinkedHashMap<>();
        output.put("상품명칭", JaText.normalizeWs(str(row.get("상품명칭"))));
        List<String> sortedKeys = new ArrayList<>(row.keySet());
        Collections.sort(sortedKeys);
        for (String key : sortedKeys) {
            if (key.startsWith("세부종목")) output.put(key, row.get(key));
        }
        output.put("상품명", productName);
        output.put("가입가능나이", new ArrayList<>());

        String minAge = JaText.extractMinAge(pdfLines);
        for (String line : pdfLines) {
            Matcher m = MIN_AGE_OVERRIDE.matcher(line);
            if (m.find()) {
                String condition = m.group(1);
                String overrideAge = m.group(2);
                if (JaText.matchContextToProduct(condition, productName)) minAge = overrideAge;
            }
        }

        String strategy = JaText.detectMaxAgeStrategy(pdfLines, pdfTables);
        boolean hasGender = JaText.detectGenderSplit(pdfLines, pdfTables);

        List<Map<String, Object>> ageRecords = new ArrayList<>();

        // 0-pre) 상생협력형
        if (productName.contains("상생")) {
            boolean inSangsaeng = false;
            String ssMin = null, ssMax = null;
            for (String line : pdfLines) {
                if (SANGSAENG_SEC1.matcher(line).find() || SANGSAENG_SEC2.matcher(line).lookingAt()) {
                    inSangsaeng = true;
                    continue;
                }
                if (inSangsaeng) {
                    if (SANGSAENG_SEC_END1.matcher(line).lookingAt() && !line.contains("상생")) {
                        inSangsaeng = false;
                        continue;
                    }
                    if (SANGSAENG_SEC_END2.matcher(line).lookingAt() && !line.contains("상생")) {
                        inSangsaeng = false;
                        continue;
                    }
                    Matcher mmin = SANGSAENG_MIN.matcher(line);
                    if (mmin.find()) ssMin = mmin.group(1);
                    Matcher mmax = SANGSAENG_MAX.matcher(line);
                    if (mmax.find()) { ssMax = mmax.group(1); break; }
                }
            }
            if (ssMax != null) {
                Map<String, Object> rec = JaPostprocess.newEmptyRecord();
                rec.put("최소가입나이", ssMin != null ? ssMin : "0");
                rec.put("최대가입나이", ssMax);
                ageRecords.add(rec);
            }
        }

        // 0) 이중 구분
        List<Map<String, Object>> dgEntries = JaOtherTables.parseDoubleGubun(pdfTables, pdfLines, productName);
        if (!dgEntries.isEmpty()) ageRecords = dgEntries;

        // 1a) 보험기간x성별
        List<Map<String, Object>> tableEntries = JaAgeTable.parse(pdfTables, pdfLines);
        if (!tableEntries.isEmpty() && ageRecords.isEmpty()) {
            Integer targetTg = selectTableGroup(tableEntries, pdfLines, productName);
            ageRecords = JaRecordBuilder.build(minAge == null ? "" : minAge,
                    tableEntries, productName, targetTg, false);
        }

        // 1b) 최고가입나이 테이블
        if (ageRecords.isEmpty() && !("formula".equals(strategy) && isAnnuity)) {
            List<Map<String, Object>> maxAgeEntries = JaOtherTables.parseMaxAge(pdfTables);
            if (!maxAgeEntries.isEmpty() && minAge != null) {
                Set<String> validPayms = collectValidPayms(periodData, productName);
                TreeSet<Integer> tableGroups = new TreeSet<>();
                for (Map<String, Object> e : maxAgeEntries) tableGroups.add(num(e.get("table_group")));
                Integer targetGroup = null;
                if (tableGroups.size() > 1) {
                    Matcher mJong = JONG_PAT.matcher(productName);
                    if (mJong.find()) {
                        int jongIdx = Integer.parseInt(mJong.group(1)) - 1;
                        List<Integer> tgs = new ArrayList<>(tableGroups);
                        if (jongIdx >= 0 && jongIdx < tgs.size()) targetGroup = tgs.get(jongIdx);
                    }
                }
                Set<String> seen = new LinkedHashSet<>();
                for (Map<String, Object> entry : maxAgeEntries) {
                    if (targetGroup != null && num(entry.get("table_group")) != targetGroup) continue;
                    if (!JaText.matchContextToProduct(str(entry.get("context")), productName)) continue;
                    addMaxAgeEntry(ageRecords, seen, entry, minAge, validPayms);
                }
                if (ageRecords.isEmpty()) {
                    for (Map<String, Object> entry : maxAgeEntries) {
                        if (targetGroup != null && num(entry.get("table_group")) != targetGroup) continue;
                        addMaxAgeEntry(ageRecords, seen, entry, minAge, validPayms);
                    }
                }
            }
        }

        // 1b-2) variant 테이블
        if (ageRecords.isEmpty()) {
            List<Map<String, Object>> variantEntries = JaOtherTables.parseVariant(pdfTables);
            if (!variantEntries.isEmpty() && minAge != null) {
                Set<String> validPayms = collectValidPayms(periodData, productName);
                Set<String> seen = new LinkedHashSet<>();
                for (Map<String, Object> entry : variantEntries) {
                    if (!JaText.matchContextToProduct(str(entry.get("context")), productName)) continue;
                    String paymText = str(entry.get("납입기간"));
                    IpText.Period p = IpText.formatPeriod(paymText);
                    String gender = str(entry.get("성별"));
                    String maxAge = str(entry.get("최대가입나이"));
                    if (!validPayms.isEmpty() && !p.value().isEmpty()
                            && !validPayms.contains(p.value() + "|" + p.kind())) continue;
                    String key = minAge + "|" + maxAge + "|" + gender + "|" + p.value() + "|" + p.kind();
                    if (seen.contains(key)) continue;
                    seen.add(key);
                    Map<String, Object> rec = JaPostprocess.newEmptyRecord();
                    rec.put("성별", gender);
                    rec.put("최소가입나이", minAge);
                    rec.put("최대가입나이", maxAge);
                    rec.put("최소납입기간", p.value());
                    rec.put("최대납입기간", p.value());
                    rec.put("납입기간구분코드", p.kind());
                    ageRecords.add(rec);
                }
            }
        }

        // 1c-0) 보험기간|납입기간|남자|여자
        if (ageRecords.isEmpty()) {
            List<Map<String, Object>> pgEntries = JaOtherTables.parsePeriodGender(pdfTables, pdfLines);
            if (!pgEntries.isEmpty()) {
                Set<String> seen = new LinkedHashSet<>();
                for (Map<String, Object> entry : pgEntries) {
                    String ctx = str(entry.get("context"));
                    if (!ctx.isEmpty() && !JaText.matchContextToProduct(ctx, productName)) continue;
                    String gender = str(entry.get("성별"));
                    String minA = str(entry.get("최소가입나이"));
                    String maxA = str(entry.get("최대가입나이"));
                    String insText = str(entry.get("보험기간"));
                    String paymText = str(entry.get("납입기간"));
                    IpText.Period insP = IpText.formatPeriod(insText);
                    IpText.Period paymP = IpText.formatPeriod(paymText);
                    String paymDvsn = paymP.kind();
                    String paymVal = paymP.value();
                    if (paymText.contains("전기납")) {
                        paymDvsn = "X";
                        paymVal = insP.value();
                    }
                    String key = minA + "|" + maxA + "|" + gender + "|" + insP.value() + "|" + insP.kind() + "|" + paymVal + "|" + paymDvsn;
                    if (seen.contains(key)) continue;
                    seen.add(key);
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("성별", gender);
                    rec.put("최소가입나이", minA);
                    rec.put("최대가입나이", maxA);
                    rec.put("최소보험기간", insP.value());
                    rec.put("최대보험기간", insP.value());
                    rec.put("보험기간구분코드", insP.kind());
                    rec.put("최소납입기간", paymVal);
                    rec.put("최대납입기간", paymVal);
                    rec.put("납입기간구분코드", paymDvsn);
                    rec.put("최소제2보기개시나이", "");
                    rec.put("최대제2보기개시나이", "");
                    rec.put("제2보기개시나이구분코드", "");
                    ageRecords.add(rec);
                }
            }
        }

        // 1c) 직접 나이
        if (ageRecords.isEmpty()) {
            List<Map<String, Object>> directEntries = JaOtherTables.parseDirect(pdfTables);
            if (!directEntries.isEmpty()) {
                ageRecords = JaRecordBuilder.build(minAge == null ? "" : minAge,
                        directEntries, productName, null, false);
            }
        }

        // 1d) 인라인
        if (ageRecords.isEmpty() && !("formula".equals(strategy) && isAnnuity)) {
            List<Map<String, Object>> inlineEntries = JaOtherTables.parseInline(pdfTables);
            if (!inlineEntries.isEmpty()) {
                ageRecords = JaRecordBuilder.build(minAge == null ? "" : minAge,
                        inlineEntries, productName, null, false);
            }
        }

        // 2) SPIN x PAYM 매트릭스
        if (ageRecords.isEmpty()) {
            List<Map<String, Object>> spinEntries = JaOtherTables.parseSpinPaym(pdfTables, pdfLines);
            if (!spinEntries.isEmpty() && minAge != null) {
                Set<String> seen = new LinkedHashSet<>();
                for (Map<String, Object> entry : spinEntries) {
                    if (!JaText.matchContextToProduct(str(entry.get("context")), productName)) continue;
                    String spinVal = str(entry.get("spin"));
                    String paymText = str(entry.get("납입기간"));
                    IpText.Period p = IpText.formatPeriod(paymText);
                    String paymDvsn = p.kind();
                    String paymVal = p.value();
                    String maxAge = str(entry.get("최대가입나이"));
                    String entryGender = str(entry.get("성별"));
                    if (paymText.contains("전기납")) {
                        paymDvsn = "X";
                        paymVal = spinVal;
                    }
                    String[] genders = (hasGender && entryGender.isEmpty()) ? new String[]{"1", "2"} : new String[]{entryGender};
                    for (String g : genders) {
                        String key = minAge + "|" + maxAge + "|" + g + "|" + paymVal + "|" + paymDvsn + "|" + spinVal;
                        if (seen.contains(key)) continue;
                        seen.add(key);
                        Map<String, Object> rec = new LinkedHashMap<>();
                        rec.put("성별", g);
                        rec.put("최소가입나이", minAge);
                        rec.put("최대가입나이", maxAge);
                        rec.put("최소납입기간", paymVal);
                        rec.put("최대납입기간", paymVal);
                        rec.put("납입기간구분코드", paymDvsn);
                        rec.put("최소제2보기개시나이", spinVal);
                        rec.put("최대제2보기개시나이", spinVal);
                        rec.put("제2보기개시나이구분코드", "X");
                        rec.put("최소보험기간", "");
                        rec.put("최대보험기간", "");
                        rec.put("보험기간구분코드", "");
                        ageRecords.add(rec);
                    }
                }
            }
        }

        // 3) 연금 공식
        if (ageRecords.isEmpty() && "formula".equals(strategy) && isAnnuity) {
            JaOtherTables.Deduction ded = JaOtherTables.parseDeduction(pdfLines, pdfTables);
            List<Map<String, Object>> matchingPeriods = new ArrayList<>();
            for (Map<String, Object> pd : periodData) {
                if (JaText.normalizeWs(str(pd.get("상품명"))).equals(productName)) matchingPeriods.add(pd);
            }
            if (matchingPeriods.isEmpty()) {
                String base = JaText.normalizeWs(str(row.get("상품명칭")));
                for (Map<String, Object> pd : periodData) {
                    if (JaText.normalizeWs(str(pd.get("상품명칭"))).equals(base)) matchingPeriods.add(pd);
                }
            }
            if (!matchingPeriods.isEmpty() && minAge != null) {
                boolean useSpinMinusOne = productName.contains("거치") && !productName.contains("즉시");
                ageRecords = JaFormula.compute(
                        minAge, matchingPeriods, hasGender,
                        useSpinMinusOne,
                        useSpinMinusOne ? ded.geochiDeduction : null,
                        useSpinMinusOne ? null : ded.paymDeductions);
                if (ageRecords.isEmpty() && useSpinMinusOne) {
                    String prodBase = JaText.normalizeWs(str(row.get("상품명칭")));
                    Matcher atm = ANNUITY_TYPE.matcher(productName);
                    String annuityType = atm.find() ? atm.group(1) : "";
                    List<Map<String, Object>> siblings = new ArrayList<>();
                    for (Map<String, Object> pd : periodData) {
                        if (!JaText.normalizeWs(str(pd.get("상품명칭"))).equals(prodBase)) continue;
                        Object rawPnl = pd.get("가입가능보기납기");
                        if (!(rawPnl instanceof List)) continue;
                        boolean anyVal = false;
                        for (Object po : (List<?>) rawPnl) {
                            if (!(po instanceof Map)) continue;
                            @SuppressWarnings("unchecked")
                            Map<String, Object> p = (Map<String, Object>) po;
                            String ms = str(p.get("최소제2보기개시나이"));
                            if (!ms.isEmpty() && !"-".equals(ms)) { anyVal = true; break; }
                        }
                        if (!anyVal) continue;
                        if (!annuityType.isEmpty()) {
                            String sibName = JaText.normalizeWs(str(pd.get("상품명")));
                            if (!sibName.contains(annuityType)) continue;
                        }
                        siblings.add(pd);
                    }
                    if (!siblings.isEmpty()) {
                        ageRecords = JaFormula.compute(minAge, siblings, hasGender, true, ded.geochiDeduction, null);
                    }
                }
            }
        }

        // 3a-post) 종별 납입기간 제한 필터
        if (!ageRecords.isEmpty() && "formula".equals(strategy) && isAnnuity) {
            Matcher jongM = JONG_PAT.matcher(productName);
            if (jongM.find()) {
                String jongLabel = jongM.group(1) + "종";
                boolean isJeok = productName.contains("적립");
                boolean isGeo = productName.contains("거치");
                String typeLabel = isJeok ? "적립형" : (isGeo ? "거치형" : "");
                Set<String> allowedPayms = null;
                // Python quirk: uses `lines.index(line)` which returns the first index whose
                // value equals `line` (full-string match), not the current loop index.
                for (int li = 0; li < pdfLines.size(); li++) {
                    String line = pdfLines.get(li);
                    if (line.contains(jongLabel)) {
                        int idx = pdfLines.indexOf(line);
                        int endI = Math.min(idx + 3, pdfLines.size());
                        for (int j = idx; j < endI; j++) {
                            String sl = pdfLines.get(j);
                            if (!typeLabel.isEmpty() && sl.contains(typeLabel) && sl.contains("년납")) {
                                Matcher mp = YEAR_NAP.matcher(sl);
                                Set<String> found = new LinkedHashSet<>();
                                while (mp.find()) found.add(mp.group(1));
                                if (!found.isEmpty()) { allowedPayms = found; break; }
                            }
                        }
                        if (allowedPayms != null) break;
                    }
                }
                if (allowedPayms != null) {
                    List<Map<String, Object>> filtered = new ArrayList<>();
                    for (Map<String, Object> r : ageRecords) {
                        String dvsn = str(r.get("납입기간구분코드"));
                        String minPaym = str(r.get("최소납입기간"));
                        if (!"N".equals(dvsn) || allowedPayms.contains(minPaym)) filtered.add(r);
                    }
                    ageRecords = filtered;
                }
            }
        }

        // 3b) 텍스트 CONTEXT MIN~MAX세
        if (ageRecords.isEmpty()) {
            int[] sec = JaText.findAgeSection(pdfLines);
            for (int i = sec[0]; i < sec[1]; i++) {
                String line = pdfLines.get(i);
                Matcher m = INLINE_RANGE.matcher(line);
                if (m.find()) {
                    String prefix = line.substring(0, m.start()).trim();
                    if (JaText.matchContextToProduct(prefix, productName)) {
                        Map<String, Object> rec = JaPostprocess.newEmptyRecord();
                        rec.put("최소가입나이", m.group(1));
                        rec.put("최대가입나이", m.group(2));
                        ageRecords.add(rec);
                        break;
                    }
                }
            }
        }

        // 4) 텍스트 기반
        if (ageRecords.isEmpty()) {
            List<Map<String, Object>> textAges = JaTextExtract.extract(pdfLines);
            if (!textAges.isEmpty()) {
                boolean hasAnyContext = false;
                for (Map<String, Object> e : textAges) if (!str(e.get("context")).isEmpty()) { hasAnyContext = true; break; }
                if (hasAnyContext) {
                    List<Map<String, Object>> filt = new ArrayList<>();
                    for (Map<String, Object> e : textAges) {
                        if (JaText.matchContextToProduct(str(e.get("context")), productName)) filt.add(e);
                    }
                    if (!filt.isEmpty()) textAges = filt;
                }
                for (Map<String, Object> entry : textAges) {
                    String insText = str(entry.get("보험기간"));
                    String paymText = str(entry.get("납입기간"));
                    IpText.Period insP = IpText.formatPeriod(insText);
                    IpText.Period paymP = IpText.formatPeriod(paymText);
                    String paymDvsn = paymP.kind();
                    String paymVal = paymP.value();
                    if (paymText.contains("전기납") && !insText.isEmpty()) {
                        paymDvsn = insP.kind();
                        paymVal = insP.value();
                    }
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("성별", str(entry.get("성별")));
                    rec.put("최소가입나이", str(entry.get("최소가입나이")));
                    rec.put("최대가입나이", str(entry.get("최대가입나이")));
                    rec.put("최소납입기간", paymVal);
                    rec.put("최대납입기간", paymVal);
                    rec.put("납입기간구분코드", paymDvsn);
                    rec.put("최소제2보기개시나이", "");
                    rec.put("최대제2보기개시나이", "");
                    rec.put("제2보기개시나이구분코드", "");
                    rec.put("최소보험기간", insP.value());
                    rec.put("최대보험기간", insP.value());
                    rec.put("보험기간구분코드", insP.kind());
                    ageRecords.add(rec);
                }
            }
        }

        // 4) 최종 fallback
        if (ageRecords.isEmpty()) {
            String maxAge = JaText.extractSimpleMaxAge(pdfLines);
            if (minAge != null && maxAge != null) {
                Map<String, Object> rec = JaPostprocess.newEmptyRecord();
                rec.put("최소가입나이", minAge);
                rec.put("최대가입나이", maxAge);
                ageRecords.add(rec);
            }
        }

        // PAYM/INS 필터
        if (ageRecords.size() > 1) {
            List<Map<String, Object>> periodMatches = new ArrayList<>();
            for (Map<String, Object> pd : periodData) {
                if (JaText.normalizeWs(str(pd.get("상품명"))).equals(productName)) periodMatches.add(pd);
            }
            if (!periodMatches.isEmpty()) {
                Set<String> validPayms = new LinkedHashSet<>();
                Set<String> validIns = new LinkedHashSet<>();
                for (Map<String, Object> pd : periodMatches) {
                    Object raw = pd.get("가입가능보기납기");
                    if (!(raw instanceof List)) continue;
                    for (Object po : (List<?>) raw) {
                        if (!(po instanceof Map)) continue;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> p = (Map<String, Object>) po;
                        String pv = str(p.get("납입기간값"));
                        String pd_ = str(p.get("납입기간구분코드"));
                        String iv = str(p.get("보험기간값"));
                        String id_ = str(p.get("보험기간구분코드"));
                        if (!pv.isEmpty()) validPayms.add(pv + "|" + pd_);
                        if (!iv.isEmpty()) validIns.add(iv + "|" + id_);
                    }
                }
                if (!validPayms.isEmpty() || !validIns.isEmpty()) {
                    List<Map<String, Object>> filtered = new ArrayList<>();
                    for (Map<String, Object> rec : ageRecords) {
                        String paymV = str(rec.get("최소납입기간"));
                        String paymD = str(rec.get("납입기간구분코드"));
                        String insV = str(rec.get("최소보험기간"));
                        String insD = str(rec.get("보험기간구분코드"));
                        if (!validPayms.isEmpty() && !paymV.isEmpty()
                                && !validPayms.contains(paymV + "|" + paymD)) continue;
                        if (!validIns.isEmpty() && !insV.isEmpty()
                                && !validIns.contains(insV + "|" + insD)) continue;
                        filtered.add(rec);
                    }
                    if (!filtered.isEmpty()) ageRecords = filtered;
                }
            }
        }

        // 실손 override + postprocess
        ageRecords = JaPostprocess.applySilsonAndPostprocess(ageRecords, productName, cfg);
        output.put("가입가능나이", ageRecords);
        return output;
    }

    // ── helpers ────────────────────────────────────────────
    private static Set<String> collectValidPayms(List<Map<String, Object>> periodData, String productName) {
        Set<String> set = new LinkedHashSet<>();
        for (Map<String, Object> pd : periodData) {
            if (!JaText.normalizeWs(str(pd.get("상품명"))).equals(productName)) continue;
            Object raw = pd.get("가입가능보기납기");
            if (!(raw instanceof List)) continue;
            for (Object po : (List<?>) raw) {
                if (!(po instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) po;
                String pv = str(p.get("납입기간값"));
                String pd_ = str(p.get("납입기간구분코드"));
                if (!pv.isEmpty()) set.add(pv + "|" + pd_);
            }
        }
        return set;
    }

    private static void addMaxAgeEntry(List<Map<String, Object>> ageRecords, Set<String> seen,
                                        Map<String, Object> entry, String minAge, Set<String> validPayms) {
        String paymText = str(entry.get("납입기간"));
        IpText.Period p = IpText.formatPeriod(paymText);
        String paymDvsn = p.kind();
        String paymVal = p.value();
        String gender = str(entry.get("성별"));
        String maxAge = str(entry.get("최대가입나이"));
        if (!validPayms.isEmpty() && !paymVal.isEmpty()
                && !validPayms.contains(paymVal + "|" + paymDvsn)) return;
        String key = minAge + "|" + maxAge + "|" + gender + "|" + paymVal + "|" + paymDvsn;
        if (seen.contains(key)) return;
        seen.add(key);
        Map<String, Object> rec = JaPostprocess.newEmptyRecord();
        rec.put("성별", gender);
        rec.put("최소가입나이", minAge);
        rec.put("최대가입나이", maxAge);
        rec.put("최소납입기간", paymVal);
        rec.put("최대납입기간", paymVal);
        rec.put("납입기간구분코드", paymDvsn);
        ageRecords.add(rec);
    }

    /** Port of the multi-table-group selection logic in step 1a. */
    private static Integer selectTableGroup(
            List<Map<String, Object>> tableEntries, List<String> pdfLines, String productName) {
        TreeSet<Integer> tblGroups = new TreeSet<>();
        for (Map<String, Object> e : tableEntries) tblGroups.add(num(e.get("table_group")));
        if (tblGroups.size() <= 1) return null;
        boolean allEmptyCtx = true;
        for (Map<String, Object> e : tableEntries) if (!str(e.get("context")).isEmpty()) { allEmptyCtx = false; break; }
        if (!allEmptyCtx) return null;

        int[] sec = JaText.findAgeSection(pdfLines);
        List<String> secHeaders = new ArrayList<>();
        List<Integer> subsecCounts = new ArrayList<>();
        int curSubsecCount = 0;
        for (int li = sec[0]; li < sec[1]; li++) {
            String line = pdfLines.get(li);
            if (HEAD_NA.matcher(line).lookingAt()) break;
            Matcher sh = SECTION_NUMERIC.matcher(line);
            if (sh.lookingAt()) {
                if (!secHeaders.isEmpty()) subsecCounts.add(Math.max(curSubsecCount, 1));
                secHeaders.add(sh.group(2).trim());
                curSubsecCount = 0;
            } else if (SUBSEC_NUMBER.matcher(line).lookingAt()) {
                curSubsecCount++;
            }
        }
        if (!secHeaders.isEmpty()) subsecCounts.add(Math.max(curSubsecCount, 1));

        List<Integer> tblGroupList = new ArrayList<>(tblGroups);
        Integer targetTg = null;

        if (secHeaders.size() == tblGroupList.size()) {
            for (int si = 0; si < secHeaders.size(); si++) {
                if (JaText.matchContextToProduct(secHeaders.get(si), productName)) {
                    targetTg = tblGroupList.get(si);
                    break;
                }
            }
        }

        if (targetTg == null && !secHeaders.isEmpty()) {
            int sum = 0;
            for (int c : subsecCounts) sum += c;
            if (sum == tblGroupList.size()) {
                int grpOffset = 0;
                for (int si = 0; si < secHeaders.size(); si++) {
                    int nSubs = subsecCounts.get(si);
                    if (JaText.matchContextToProduct(secHeaders.get(si), productName)) {
                        Matcher mh = HYUNG_PAT.matcher(productName);
                        if (mh.find() && nSubs > 1) {
                            int hIdx = Integer.parseInt(mh.group(1)) - 1;
                            if (hIdx >= 0 && hIdx < nSubs) targetTg = tblGroupList.get(grpOffset + hIdx);
                        } else {
                            targetTg = tblGroupList.get(grpOffset);
                        }
                        break;
                    }
                    grpOffset += nSubs;
                }
            }
        }

        if (targetTg == null) {
            Matcher mj = JONG_PAT.matcher(productName);
            if (mj.find()) {
                int jongIdx = Integer.parseInt(mj.group(1)) - 1;
                if (jongIdx >= 0 && jongIdx < tblGroupList.size()) targetTg = tblGroupList.get(jongIdx);
            }
        }

        // 페이지 분할 보정
        if (targetTg != null) {
            LinkedHashMap<Integer, Integer> grpCounts = new LinkedHashMap<>();
            for (int g : tblGroupList) {
                int cnt = 0;
                for (Map<String, Object> e : tableEntries) if (num(e.get("table_group")) == g) cnt++;
                grpCounts.put(g, cnt);
            }
            int selected = grpCounts.getOrDefault(targetTg, 0);
            List<Integer> values = new ArrayList<>(grpCounts.values());
            Collections.sort(values);
            int median = values.get(values.size() / 2);
            if (selected < median * 0.6) {
                Matcher mh = HYUNG_PAT.matcher(productName);
                int hyungNum = mh.find() ? Integer.parseInt(mh.group(1)) : 0;
                List<Integer> fullGroups = new ArrayList<>();
                for (int g : tblGroupList) if (grpCounts.get(g) >= median) fullGroups.add(g);
                if (!fullGroups.isEmpty() && hyungNum > 0) {
                    Matcher mj2 = JONG_PAT.matcher(productName);
                    if (mj2.find()) {
                        int jongNum = Integer.parseInt(mj2.group(1));
                        int secCount = !secHeaders.isEmpty() ? secHeaders.size() : jongNum;
                        int perSec = fullGroups.size() / Math.max(secCount, 1);
                        if (perSec > 0) {
                            int start = (jongNum - 1) * perSec;
                            int end = start + perSec;
                            if (start >= 0 && end <= fullGroups.size()) {
                                List<Integer> secGroups = fullGroups.subList(start, end);
                                if (!secGroups.isEmpty()) {
                                    int idx = Math.min(hyungNum - 1, secGroups.size() - 1);
                                    targetTg = secGroups.get(idx);
                                }
                            }
                        }
                    }
                }
            }
        }
        return targetTg;
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private static int num(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) try { return Integer.parseInt((String) o); } catch (NumberFormatException ignored) {}
        return 0;
    }
}
