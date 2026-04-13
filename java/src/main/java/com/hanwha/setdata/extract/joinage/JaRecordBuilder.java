package com.hanwha.setdata.extract.joinage;

import com.hanwha.setdata.extract.period.IpText;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Port of {@code build_table_age_records}. */
public final class JaRecordBuilder {

    private JaRecordBuilder() {}

    public static List<Map<String, Object>> build(
            String minAge,
            List<Map<String, Object>> tableEntries,
            String productName,
            Integer targetTableGroup,
            boolean collapseGender) {

        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> entry : tableEntries) {
            if (targetTableGroup != null) {
                int g = numOrZero(entry.get("table_group"));
                if (g != targetTableGroup) continue;
            }
            String ctx = str(entry.get("context"));
            if (JaText.matchContextToProduct(ctx, productName)) filtered.add(entry);
        }
        if (filtered.isEmpty()) {
            for (Map<String, Object> entry : tableEntries) {
                if (targetTableGroup != null) {
                    int g = numOrZero(entry.get("table_group"));
                    if (g != targetTableGroup) continue;
                }
                filtered.add(entry);
            }
        }

        List<Map<String, Object>> records = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> entry : filtered) {
            String insText = str(entry.get("보험기간"));
            String paymText = str(entry.get("납입기간"));
            String gender = collapseGender ? "" : str(entry.get("성별"));
            String maxAge = str(entry.get("최대가입나이"));
            String entryMinAge = str(entry.get("최소가입나이"));
            if (entryMinAge.isEmpty()) entryMinAge = minAge == null ? "" : minAge;

            IpText.Period insP = IpText.formatPeriod(insText);
            IpText.Period paymP = IpText.formatPeriod(paymText);
            String paymCode = paymP.code();
            String paymDvsn = paymP.kind();
            String paymVal = paymP.value();
            if (paymText.contains("전기납") && !insText.isEmpty()) {
                paymCode = insP.code();
                paymDvsn = insP.kind();
                paymVal = insP.value();
            }
            String key = entryMinAge + "|" + maxAge + "|" + gender
                    + "|" + insP.value() + "|" + insP.value() + "|" + insP.kind()
                    + "|" + paymVal + "|" + paymVal + "|" + paymDvsn;
            if (seen.contains(key)) continue;
            seen.add(key);
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("성별", gender);
            rec.put("최소가입나이", entryMinAge);
            rec.put("최대가입나이", maxAge);
            rec.put("최소납입기간", paymVal);
            rec.put("최대납입기간", paymVal);
            rec.put("납입기간구분코드", paymDvsn);
            rec.put("최소제2보기개시나이", "");
            rec.put("최대제2보기개시나이", "");
            rec.put("제2보기개시나이구분코드", "");
            rec.put("최소보험기간", insP.value());
            rec.put("최대보험기간", insP.value());
            rec.put("보험기간구분코드", insP.kind());
            records.add(rec);
        }

        // Clear fields that have a single unique non-empty value
        String[] insFields = {"최소보험기간", "최대보험기간", "보험기간구분코드"};
        String[] paymFields = {"최소납입기간", "최대납입기간", "납입기간구분코드"};
        String[] spinFields = {"최소제2보기개시나이", "최대제2보기개시나이", "제2보기개시나이구분코드"};

        int paymUnique = countUnique(records, paymFields);
        int insUnique = countUnique(records, insFields);
        if (paymUnique <= 1) clearFields(records, paymFields);
        if (insUnique <= 1 && paymUnique <= 1) clearFields(records, insFields);
        if (countUnique(records, spinFields) <= 1) clearFields(records, spinFields);

        return records;
    }

    private static int countUnique(List<Map<String, Object>> records, String[] fields) {
        Set<String> unique = new HashSet<>();
        for (Map<String, Object> r : records) {
            StringBuilder sb = new StringBuilder();
            boolean any = false;
            for (String f : fields) {
                String v = str(r.get(f));
                if (!v.isEmpty()) any = true;
                sb.append(v).append('|');
            }
            if (any) unique.add(sb.toString());
        }
        return unique.size();
    }

    private static void clearFields(List<Map<String, Object>> records, String[] fields) {
        for (Map<String, Object> r : records) for (String f : fields) r.put(f, "");
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int numOrZero(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) try { return Integer.parseInt((String) o); } catch (NumberFormatException ignored) {}
        return 0;
    }
}
