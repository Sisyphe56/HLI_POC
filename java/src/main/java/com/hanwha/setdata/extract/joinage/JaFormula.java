package com.hanwha.setdata.extract.joinage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Port of {@code compute_formula_ages}. */
public final class JaFormula {

    private JaFormula() {}

    public static List<Map<String, Object>> compute(
            String minAge,
            List<Map<String, Object>> periodData,
            boolean hasGenderSplit,
            boolean spinMinusOne,
            Integer deductionOverride,
            Map<String, Integer> paymDeductions) {

        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        if (spinMinusOne) {
            int ded = deductionOverride != null ? deductionOverride : 1;
            TreeSet<Integer> spinVals = new TreeSet<>();
            for (Map<String, Object> periodRec : periodData) {
                Object raw = periodRec.get("가입가능보기납기");
                if (!(raw instanceof List)) continue;
                for (Object po : (List<?>) raw) {
                    if (!(po instanceof Map)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> p = (Map<String, Object>) po;
                    String minSpin = strOrEmpty(p.get("최소제2보기개시나이"));
                    String maxSpin = strOrEmpty(p.get("최대제2보기개시나이"));
                    if (minSpin.isEmpty()) continue;
                    try {
                        int lo = Integer.parseInt(minSpin);
                        int hi = maxSpin.isEmpty() ? lo : Integer.parseInt(maxSpin);
                        for (int v = lo; v <= hi; v++) spinVals.add(v);
                    } catch (NumberFormatException ignore) {}
                }
            }
            String[] genders = hasGenderSplit ? new String[]{"1", "2"} : new String[]{""};
            for (int spinVal : spinVals) {
                int maxAg = spinVal - ded;
                if (maxAg < 0) continue;
                String spinStr = String.valueOf(spinVal);
                for (String gender : genders) {
                    String key = minAge + "|" + maxAg + "|" + gender + "|" + spinStr;
                    if (seen.contains(key)) continue;
                    seen.add(key);
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("성별", gender);
                    rec.put("최소가입나이", minAge);
                    rec.put("최대가입나이", String.valueOf(maxAg));
                    rec.put("최소납입기간", "");
                    rec.put("최대납입기간", "");
                    rec.put("납입기간구분코드", "");
                    rec.put("최소제2보기개시나이", spinStr);
                    rec.put("최대제2보기개시나이", spinStr);
                    rec.put("제2보기개시나이구분코드", "X");
                    rec.put("최소보험기간", "");
                    rec.put("최대보험기간", "");
                    rec.put("보험기간구분코드", "");
                    results.add(rec);
                }
            }
            return results;
        }

        // SPIN x PAYM mode
        List<int[]> combos = new ArrayList<>(); // [spin, paymValInt, paymDvsnIdx]
        Set<String> comboKeys = new LinkedHashSet<>();
        // use String[] triples for stable sort
        List<Object[]> comboList = new ArrayList<>();
        for (Map<String, Object> periodRec : periodData) {
            Object raw = periodRec.get("가입가능보기납기");
            if (!(raw instanceof List)) continue;
            for (Object po : (List<?>) raw) {
                if (!(po instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) po;
                String minSpin = strOrEmpty(p.get("최소제2보기개시나이"));
                String maxSpin = strOrEmpty(p.get("최대제2보기개시나이"));
                String paymDvsn = strOrEmpty(p.get("납입기간구분코드"));
                String paymVal = strOrEmpty(p.get("납입기간값"));
                if (minSpin.isEmpty() || paymVal.isEmpty()) continue;
                int lo, hi;
                try {
                    lo = Integer.parseInt(minSpin);
                    hi = maxSpin.isEmpty() ? lo : Integer.parseInt(maxSpin);
                } catch (NumberFormatException e) { continue; }
                for (int spinVal = lo; spinVal <= hi; spinVal++) {
                    String ck = spinVal + "|" + paymVal + "|" + paymDvsn;
                    if (comboKeys.add(ck)) {
                        comboList.add(new Object[]{spinVal, paymVal, paymDvsn});
                    }
                }
            }
        }
        // sort: sorted(combos) in python sorts tuples lexicographically
        comboList.sort((a, b) -> {
            int c = Integer.compare((int) a[0], (int) b[0]);
            if (c != 0) return c;
            c = ((String) a[1]).compareTo((String) b[1]);
            if (c != 0) return c;
            return ((String) a[2]).compareTo((String) b[2]);
        });

        for (Object[] combo : comboList) {
            int spinVal = (int) combo[0];
            String paymVal = (String) combo[1];
            String paymDvsn = (String) combo[2];
            int paymInt;
            try { paymInt = Integer.parseInt(paymVal); }
            catch (NumberFormatException e) { continue; }

            int maxAg;
            if ("N".equals(paymDvsn) && paymInt > 0) {
                if (paymDeductions != null && paymDeductions.containsKey(paymVal)) {
                    maxAg = spinVal - paymDeductions.get(paymVal);
                } else {
                    maxAg = spinVal - paymInt;
                }
            } else if ("X".equals(paymDvsn)) {
                if (paymDeductions != null && paymDeductions.containsKey("전기납")) {
                    maxAg = spinVal - paymDeductions.get("전기납");
                } else {
                    maxAg = spinVal > 10 ? spinVal - 10 : 0;
                }
            } else {
                continue;
            }
            if (maxAg < 0) continue;

            String spinStr = String.valueOf(spinVal);
            String[] genders = hasGenderSplit ? new String[]{"1", "2"} : new String[]{""};
            for (String gender : genders) {
                String key = minAge + "|" + maxAg + "|" + gender
                        + "|" + paymVal + "|" + paymVal + "|" + paymDvsn
                        + "|" + spinStr + "|" + spinStr + "|X";
                if (seen.contains(key)) continue;
                seen.add(key);
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("성별", gender);
                rec.put("최소가입나이", minAge);
                rec.put("최대가입나이", String.valueOf(maxAg));
                rec.put("최소납입기간", paymVal);
                rec.put("최대납입기간", paymVal);
                rec.put("납입기간구분코드", paymDvsn);
                rec.put("최소제2보기개시나이", spinStr);
                rec.put("최대제2보기개시나이", spinStr);
                rec.put("제2보기개시나이구분코드", "X");
                rec.put("최소보험기간", "");
                rec.put("최대보험기간", "");
                rec.put("보험기간구분코드", "");
                results.add(rec);
            }
        }
        return results;
    }

    private static String strOrEmpty(Object o) {
        return o == null ? "" : o.toString();
    }
}
