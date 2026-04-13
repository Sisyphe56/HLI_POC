package com.hanwha.setdata.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.hanwha.setdata.config.OverridesConfig;
import com.hanwha.setdata.model.ProductRecord;
import com.hanwha.setdata.util.Normalizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Override/build helpers ported from
 * {@code extract_product_classification_v2.py} lines 77–315 and 1027–1385.
 */
public final class PcOverrides {

    private PcOverrides() {}

    // ── _rebuild_product_name ─────────────────────────────────────
    public static String rebuildProductName(ProductRecord obj) {
        List<String> parts = new ArrayList<>();
        parts.add(obj.getOrEmpty("상품명칭"));
        for (int i = 1; i <= 10; i++) {
            String v = obj.getOrEmpty("세부종목" + i);
            if (!v.isEmpty()) parts.add(v);
        }
        return String.join(" ", parts);
    }

    // ── apply_smart_accident_product_overrides ────────────────────
    private static final Pattern WORD_1JONG =
            Pattern.compile("\\b1종\\b", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern WORD_2JONG =
            Pattern.compile("\\b2종\\b", Pattern.UNICODE_CHARACTER_CLASS);

    public static List<ProductRecord> applySmartAccidentProductOverrides(
            String filename, List<ProductRecord> objects) {
        boolean isSmartH = filename.contains("스마트H상해보험");
        boolean isSmartV = filename.contains("스마트V상해보험");
        if (!(isSmartH || isSmartV)) return objects;

        List<ProductRecord> out = new ArrayList<>();
        for (ProductRecord obj : objects) {
            ProductRecord newObj = new ProductRecord(obj);
            String productName = newObj.getOrEmpty("상품명칭");
            String detail1 = newObj.getOrEmpty("세부종목1");
            String fullName = newObj.getOrEmpty("상품명");

            boolean nameHasH = productName.contains("스마트H상해보험");
            boolean nameHasV = productName.contains("스마트V상해보험");
            boolean targetIsH = nameHasH || (isSmartH && !isSmartV);
            boolean targetIsV = nameHasV || (isSmartV && !isSmartH);

            if (!(targetIsH || targetIsV)) { out.add(newObj); continue; }

            if (targetIsH) {
                productName = PcText.cleanItem(productName.replace("스마트V", "스마트H"));
                newObj.put("상품명칭", productName);
                if (!fullName.isEmpty()) {
                    newObj.put("상품명", PcText.cleanItem(fullName.replace("스마트V", "스마트H")));
                }
                if (detail1.equals("1종")) {
                    newObj.put("세부종목1", "2종");
                    if (!fullName.isEmpty()) {
                        String base = PcText.cleanItem(newObj.getOrDefault("상품명", fullName));
                        Matcher m = WORD_1JONG.matcher(base);
                        if (m.find()) {
                            newObj.put("상품명", base.substring(0, m.start()) + "2종" + base.substring(m.end()));
                        } else {
                            newObj.put("상품명", base);
                        }
                    }
                }
            } else {
                productName = PcText.cleanItem(productName.replace("스마트H", "스마트V"));
                newObj.put("상품명칭", productName);
                if (!fullName.isEmpty()) {
                    newObj.put("상품명", PcText.cleanItem(fullName.replace("스마트H", "스마트V")));
                }
                if (detail1.equals("2종")) {
                    newObj.put("세부종목1", "1종");
                    if (!fullName.isEmpty()) {
                        String base = PcText.cleanItem(newObj.getOrDefault("상품명", fullName));
                        Matcher m = WORD_2JONG.matcher(base);
                        if (m.find()) {
                            newObj.put("상품명", base.substring(0, m.start()) + "1종" + base.substring(m.end()));
                        } else {
                            newObj.put("상품명", base);
                        }
                    }
                }
            }
            out.add(newObj);
        }
        return out;
    }

    // ── _apply_classification_overrides ───────────────────────────
    public static List<ProductRecord> applyClassificationOverrides(
            String filename, List<ProductRecord> objects, OverridesConfig config) {
        if (config == null) return objects;
        String nfcName = Normalizer.nfc(filename);
        for (Map.Entry<String, JsonNode> e : config.entries("product_classification")) {
            String key = e.getKey();
            if (!nfcName.contains(key)) continue;
            JsonNode cfg = e.getValue();
            if ("fixed".equals(cfg.path("action").asText(""))) {
                List<ProductRecord> out = new ArrayList<>();
                JsonNode items = cfg.path("items");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        ProductRecord r = new ProductRecord();
                        item.fields().forEachRemaining(f -> r.put(f.getKey(), f.getValue().asText("")));
                        out.add(r);
                    }
                }
                return out;
            }
        }
        return objects;
    }

    // ── _get_alias_outputs ────────────────────────────────────────
    public record AliasOutput(String outputStem, List<ProductRecord> objects) {}

    public static List<AliasOutput> getAliasOutputs(
            String filename, List<ProductRecord> objects, OverridesConfig config) {
        List<AliasOutput> results = new ArrayList<>();
        if (config == null) return results;
        String nfcName = Normalizer.nfc(filename);
        for (Map.Entry<String, JsonNode> e : config.entries("product_classification")) {
            JsonNode cfg = e.getValue();
            if (!"alias".equals(cfg.path("action").asText(""))) continue;
            if (!nfcName.contains(Normalizer.nfc(e.getKey()))) continue;

            List<ProductRecord> aliasObjs = new ArrayList<>();
            for (ProductRecord o : objects) aliasObjs.add(new ProductRecord(o));

            if (cfg.has("filter_include")) {
                List<String> includes = new ArrayList<>();
                cfg.get("filter_include").forEach(n -> includes.add(n.asText()));
                aliasObjs.removeIf(o -> {
                    String name = o.getOrEmpty("상품명");
                    for (String inc : includes) if (name.contains(inc)) return false;
                    return true;
                });
            }
            if (cfg.has("filter_exclude")) {
                List<String> excludes = new ArrayList<>();
                cfg.get("filter_exclude").forEach(n -> excludes.add(n.asText()));
                aliasObjs.removeIf(o -> {
                    String name = o.getOrEmpty("상품명");
                    for (String exc : excludes) if (name.contains(exc)) return true;
                    return false;
                });
            }

            JsonNode replaceCfg = cfg.path("name_replace");
            if (replaceCfg.isObject() && replaceCfg.has("from") && replaceCfg.has("to")) {
                String from = replaceCfg.get("from").asText();
                String to = replaceCfg.get("to").asText();
                for (ProductRecord o : aliasObjs) {
                    for (String field : new String[]{"상품명칭", "상품명"}) {
                        if (o.containsKey(field)) {
                            o.put(field, o.get(field).replace(from, to));
                        }
                    }
                }
            }

            String outputStem = cfg.path("alias_output_stem").asText("");
            if (!outputStem.isEmpty() && !aliasObjs.isEmpty()) {
                results.add(new AliasOutput(outputStem, aliasObjs));
            }
        }
        return results;
    }

    // ── apply_unmatched_product_overrides ─────────────────────────
    public static List<ProductRecord> applyUnmatchedProductOverrides(
            List<ProductRecord> objects, List<String> productNames) {
        List<ProductRecord> out = new ArrayList<>(objects);

        // Rule 1: 노후실손의료비보장보험 재가입
        if (anyContains(productNames, "노후실손의료비보장보험")) {
            List<ProductRecord> add = new ArrayList<>();
            for (ProductRecord obj : out) {
                String d1 = obj.getOrEmpty("세부종목1");
                if (d1.equals("상해형") || d1.equals("질병형")) {
                    ProductRecord n = new ProductRecord(obj);
                    n.put("세부종목1", d1 + "(재가입)");
                    n.put("상품명", rebuildProductName(n));
                    add.add(n);
                }
            }
            out.addAll(add);
        }

        // Rule 2: 기본형 급여 실손의료비보장보험 태아가입용
        if (anyContains(productNames, "기본형 급여 실손의료비보장보험")) {
            List<ProductRecord> add = new ArrayList<>();
            for (ProductRecord obj : out) {
                String d1 = obj.getOrEmpty("세부종목1");
                if (d1.equals("상해급여형") || d1.equals("질병급여형")) {
                    ProductRecord n = new ProductRecord(obj);
                    n.put("세부종목1", d1 + "(태아가입용)");
                    n.put("상품명", rebuildProductName(n));
                    add.add(n);
                }
            }
            out.addAll(add);
        }

        // Rule 3: 진심가득H보장보험 일반형 + 태아가입형
        if (anyContains(productNames, "진심가득H보장보험")) {
            List<ProductRecord> filtered = new ArrayList<>();
            for (ProductRecord o : out) {
                if (o.getOrEmpty("상품명칭").contains("진심가득H보장보험")
                        && "New Start 계약".equals(o.getOrEmpty("세부종목1"))) continue;
                filtered.add(o);
            }
            out = filtered;

            List<ProductRecord> taeaAdditions = new ArrayList<>();
            for (ProductRecord o : out) {
                if (o.getOrEmpty("상품명칭").contains("진심가득H보장보험")
                        && "보장형 계약".equals(o.getOrEmpty("세부종목1"))
                        && !o.getOrEmpty("세부종목2").isEmpty()) {
                    for (String jong : new String[]{"2종(태아가입형 23주 이내)", "3종(태아가입형 23주 초과)"}) {
                        ProductRecord n = new ProductRecord(o);
                        int slot = 3;
                        while (!n.getOrEmpty("세부종목" + slot).isEmpty()) slot++;
                        n.put("세부종목" + slot, jong);
                        n.put("상품명", rebuildProductName(n));
                        taeaAdditions.add(n);
                    }
                }
            }
            out.addAll(taeaAdditions);

            // Rule 4: 상생친구 보장보험
            String jinsimName = null;
            for (String n : productNames) if (n != null && n.contains("진심가득H보장보험")) { jinsimName = n; break; }
            if (jinsimName != null) {
                String sangsaengName = jinsimName.replace("진심가득H보장보험", "상생친구 보장보험");
                for (String jong : new String[]{
                        "1종(출생아가입형)",
                        "2종(태아가입형 23주 이내)",
                        "3종(태아가입형 23주 초과)"}) {
                    ProductRecord n = new ProductRecord();
                    n.put("상품명칭", sangsaengName);
                    n.put("세부종목1", "보장형 계약");
                    n.put("세부종목2", jong);
                    n.put("상품명", rebuildProductName(n));
                    out.add(n);
                }
            }
        }

        // Rule 6: H간병보험 치매보장플랜형
        if (anyContains(productNames, "H간병보험")) {
            ProductRecord template = null;
            for (ProductRecord o : out) {
                String d1 = o.getOrEmpty("세부종목1");
                if (o.getOrEmpty("상품명칭").contains("H간병보험")
                        && (d1.equals("간편가입형(2년)") || d1.equals("일반가입형"))) {
                    template = o; break;
                }
            }
            if (template != null) {
                ProductRecord n = new ProductRecord();
                n.put("상품명칭", template.getOrEmpty("상품명칭"));
                n.put("세부종목1", "치매보장플랜형");
                n.put("세부종목2", template.getOrEmpty("세부종목2"));
                n.put("상품명", rebuildProductName(n));
                out.add(n);
            }
        }

        return out;
    }

    // ── unique_records ────────────────────────────────────────────
    public static List<ProductRecord> uniqueRecords(List<ProductRecord> objects) {
        LinkedHashMap<List<Map.Entry<String, String>>, ProductRecord> uniq = new LinkedHashMap<>();
        for (ProductRecord obj : objects) {
            List<String> keys = new ArrayList<>(obj.keySet());
            java.util.Collections.sort(keys);
            List<Map.Entry<String, String>> key = new ArrayList<>();
            for (String k : keys) key.add(Map.entry(k, obj.get(k)));
            uniq.put(key, obj);
        }
        return new ArrayList<>(uniq.values());
    }

    // ── extract_hydream_contract_types / _dental_terms / _annuity_year_terms ──
    public static Set<String> extractHydreamContractTypes(String fullText) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : new String[]{"1종(신계약체결용)", "2종(계좌이체용)"}) {
            if (fullText.contains(c)) out.add(c);
        }
        return out;
    }

    private static final Pattern YEAR_MANGI = Pattern.compile("(\\d+)\\s*년\\s*만기");
    private static final Pattern YEAR_ALONE =
            Pattern.compile("(\\d+)\\s*년(?!\\s*개월)\\b", Pattern.UNICODE_CHARACTER_CLASS);

    public static List<String> extractDentalTermsFromText(String fullText) {
        List<String> terms = new ArrayList<>();
        String periodText = fullText == null ? "" : fullText;
        List<String> periodLines = new ArrayList<>();
        for (String line : periodText.split("\n", -1)) if (line.contains("보험기간")) periodLines.add(line);
        for (String line : periodLines) {
            Matcher m = YEAR_MANGI.matcher(line);
            while (m.find()) {
                String y = m.group(1);
                if ((y.equals("5") || y.equals("10")) && !terms.contains(y + "년만기")) terms.add(y + "년만기");
            }
            Matcher m2 = YEAR_ALONE.matcher(line);
            while (m2.find()) {
                String y = m2.group(1);
                if ((y.equals("5") || y.equals("10")) && !terms.contains(y + "년만기")) terms.add(y + "년만기");
            }
        }
        if (terms.isEmpty()) {
            Matcher m = YEAR_MANGI.matcher(periodText);
            while (m.find()) {
                String y = m.group(1);
                if ((y.equals("5") || y.equals("10")) && !terms.contains(y + "년만기")) terms.add(y + "년만기");
            }
        }
        terms.sort(Comparator.comparingInt(t -> Integer.parseInt(t.replaceAll("\\D", ""))));
        return terms;
    }

    private static final Pattern HYDREAM_YEAR_HEADER = Pattern.compile(
            "확정(?:기간)?연금형\\s*[:\\-]?\\s*([0-9년\\s,·/및]+)");
    private static final Pattern YEAR_NUM = Pattern.compile("(\\d+)\\s*년");

    public static List<String> extractHydreamAnnuityYearTerms(String fullText) {
        List<String> years = new ArrayList<>();
        Matcher m = HYDREAM_YEAR_HEADER.matcher(fullText == null ? "" : fullText);
        while (m.find()) {
            Matcher m2 = YEAR_NUM.matcher(m.group(1));
            while (m2.find()) years.add(m2.group(1));
        }
        if (years.isEmpty() || new HashSet<>(years).size() < 3) {
            years = new ArrayList<>();
            for (int i = 1; i <= 10; i++) years.add(String.valueOf(i));
            years.add("15"); years.add("20");
        }
        Set<String> allowed = new HashSet<>();
        for (int i = 1; i <= 10; i++) allowed.add(String.valueOf(i));
        allowed.add("15"); allowed.add("20");
        TreeSet<Integer> filtered = new TreeSet<>();
        for (String y : years) if (allowed.contains(y)) filtered.add(Integer.parseInt(y));
        List<String> out = new ArrayList<>();
        for (Integer i : filtered) out.add(i + "년");
        return out;
    }

    // ── _build_obj ────────────────────────────────────────────────
    public static ProductRecord buildObj(String baseName, List<String> tokens) {
        ProductRecord obj = new ProductRecord();
        obj.put("상품명칭", baseName);
        List<String> detailValues = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String raw = tokens.get(i);
            if (raw == null) continue;
            String value = Normalizer.normalizeWs(raw);
            if (value.isEmpty()) continue;
            detailValues.add(value);
            obj.put("세부종목" + (i + 1), value);
        }
        if (detailValues.isEmpty()) {
            obj.put("상품명", Normalizer.normalizeWs(baseName));
        } else {
            obj.put("상품명", Normalizer.normalizeWs(baseName + " " + String.join(" ", detailValues)));
        }
        return obj;
    }

    // ── apply_dental_period_overrides ─────────────────────────────
    public static List<ProductRecord> applyDentalPeriodOverrides(
            List<ProductRecord> objects, List<String> productNames, String fullText) {
        if (!anyContains(productNames, "튼튼이 치아보험")) return objects;
        List<String> periods = extractDentalTermsFromText(fullText);
        if (periods.isEmpty()) return objects;
        Set<String> targetNames = new HashSet<>();
        for (String n : productNames) if (n != null && n.contains("튼튼이 치아보험")) targetNames.add(n);

        List<ProductRecord> out = new ArrayList<>();
        for (ProductRecord obj : objects) {
            if (!targetNames.contains(obj.getOrEmpty("상품명칭"))) { out.add(obj); continue; }
            List<String> existing = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                String v = obj.getOrEmpty("세부종목" + i);
                if (!v.isEmpty()) existing.add(v);
            }
            if (existing.isEmpty()) {
                for (String period : periods) {
                    out.add(buildObj(obj.getOrEmpty("상품명칭"), List.of(period)));
                }
                continue;
            }
            for (String period : periods) {
                List<String> tokens = new ArrayList<>(existing);
                tokens.add(period);
                out.add(buildObj(obj.getOrEmpty("상품명칭"), tokens));
            }
        }
        return uniqueRecords(out);
    }

    // ── apply_hydream_annuity_overrides ───────────────────────────
    public static List<ProductRecord> applyHydreamAnnuityOverrides(
            List<ProductRecord> objects, List<String> productNames, String fullText) {
        boolean match = false;
        for (String n : productNames) {
            if (n == null) continue;
            if (n.contains("연금저축 하이드림연금보험") || n.contains("연금저축 스마트하이드림연금보험")) { match = true; break; }
        }
        if (!match) return objects;

        List<String> yearsFixed = Arrays.asList("10년", "15년", "20년");
        List<String> yearsImmediate = extractHydreamAnnuityYearTerms(fullText);
        if (yearsImmediate.isEmpty()) {
            yearsImmediate = new ArrayList<>();
            for (int i = 1; i <= 10; i++) yearsImmediate.add(i + "년");
            yearsImmediate.add("15년"); yearsImmediate.add("20년");
        }
        Set<String> targetNames = new LinkedHashSet<>();
        for (String n : productNames) if (n != null && n.contains("연금저축 하이드림연금보험")) targetNames.add(n);
        if (targetNames.isEmpty()) {
            for (String n : productNames) if (n != null && n.contains("연금저축 스마트하이드림연금보험")) targetNames.add(n);
        }

        Set<String> extractedContracts = extractHydreamContractTypes(fullText);
        Set<String> existingContracts = new LinkedHashSet<>();
        for (ProductRecord o : objects) {
            if (targetNames.contains(o.getOrEmpty("상품명칭"))) {
                String d1 = o.getOrEmpty("세부종목1");
                if (d1.equals("1종(신계약체결용)") || d1.equals("2종(계좌이체용)")) existingContracts.add(d1);
            }
        }
        TreeSet<String> contractTypes = new TreeSet<>();
        contractTypes.addAll(existingContracts);
        contractTypes.addAll(extractedContracts);

        if (contractTypes.isEmpty()) {
            List<ProductRecord> hydrated = new ArrayList<>();
            for (ProductRecord obj : objects) {
                if (targetNames.contains(obj.getOrEmpty("상품명칭"))) continue;
                hydrated.add(obj);
            }
            for (String n : targetNames) {
                boolean found = false;
                for (ProductRecord o : objects) {
                    if (o.getOrEmpty("상품명칭").equals(n) && "종신연금형".equals(o.getOrEmpty("세부종목1"))) {
                        found = true; break;
                    }
                }
                if (!found) hydrated.add(buildObj(n, List.of("종신연금형")));
            }
            return uniqueRecords(hydrated);
        }

        List<ProductRecord> out = new ArrayList<>();
        Map<String, Set<String>> contractFixedMap = new LinkedHashMap<>();
        Map<String, Map<String, Set<String>>> contractYears = new LinkedHashMap<>();
        for (String c : contractTypes) {
            contractFixedMap.put(c, new LinkedHashSet<>());
            contractYears.put(c, new LinkedHashMap<>());
        }

        for (ProductRecord obj : objects) {
            if (!targetNames.contains(obj.getOrEmpty("상품명칭"))) { out.add(obj); continue; }

            ProductRecord newObj = new ProductRecord(obj);
            String contract = newObj.getOrEmpty("세부종목1");
            String detail2 = newObj.getOrEmpty("세부종목2");
            String detail1 = newObj.getOrEmpty("세부종목1");
            String detail3 = newObj.getOrEmpty("세부종목3");
            String detail4 = newObj.getOrEmpty("세부종목4");

            if ((detail2.equals("종신연금형") || detail2.equals("확정기간연금형"))
                    && detail3.isEmpty() && detail4.isEmpty()) continue;
            if ((detail1.equals("종신연금형") || detail1.equals("확정기간연금형"))
                    && detail2.isEmpty() && detail3.isEmpty() && detail4.isEmpty()) continue;

            if (detail2.equals("종신연금형")
                    && (contract.equals("1종(신계약체결용)") || contract.equals("2종(계좌이체용)"))) {
                if ((detail3.equals("거치형") || detail3.equals("적립형") || detail3.equals("즉시형"))
                        && detail4.isEmpty()) {
                    newObj.remove("세부종목3");
                    newObj.put("세부종목4", detail3);
                }
            }

            if (detail2.equals("확정기간연금형") && detail3.equals("즉시형") && detail4.isEmpty()) {
                newObj.put("세부종목3", "");
                newObj.put("세부종목4", "즉시형");
            }

            out.add(newObj);

            if (detail2.equals("확정기간연금형") && contractTypes.contains(contract)) {
                String addon = newObj.getOrEmpty("세부종목4");
                contractFixedMap.get(contract).add(addon);
                String year = newObj.getOrEmpty("세부종목3");
                if (!year.isEmpty()) {
                    contractYears.get(contract)
                            .computeIfAbsent(addon, k -> new LinkedHashSet<>())
                            .add(year);
                }
            }
        }

        for (String contract : contractTypes) {
            if (contract.equals("2종(계좌이체용)")) {
                String hydreamMain = targetNames.iterator().next();
                boolean hasImmediate = false;
                for (ProductRecord o : out) {
                    if (targetNames.contains(o.getOrEmpty("상품명칭"))
                            && "2종(계좌이체용)".equals(o.getOrEmpty("세부종목1"))
                            && "종신연금형".equals(o.getOrEmpty("세부종목2"))
                            && "즉시형".equals(o.getOrEmpty("세부종목4"))) {
                        hasImmediate = true; break;
                    }
                }
                if (!hasImmediate) {
                    out.add(buildObj(hydreamMain, Arrays.asList("2종(계좌이체용)", "종신연금형", "", "즉시형")));
                }
                for (String addon : contractFixedMap.getOrDefault(contract, Set.of())) {
                    Set<String> existingYears = contractYears.getOrDefault(contract, Map.of())
                            .getOrDefault(addon, Set.of());
                    for (String year : yearsFixed) {
                        if (existingYears.contains(year)) continue;
                        List<String> tokens = new ArrayList<>();
                        tokens.add("2종(계좌이체용)");
                        tokens.add("확정기간연금형");
                        tokens.add(year);
                        if (!addon.isEmpty()) tokens.add(addon);
                        out.add(buildObj(hydreamMain, tokens));
                    }
                }
                Set<String> existingImmediate = contractYears.getOrDefault(contract, Map.of())
                        .getOrDefault("즉시형", Set.of());
                for (String year : yearsImmediate) {
                    if (existingImmediate.contains(year)) continue;
                    out.add(buildObj(hydreamMain,
                            Arrays.asList("2종(계좌이체용)", "확정기간연금형", year, "즉시형")));
                }
            }
            if (contract.equals("1종(신계약체결용)") && !targetNames.isEmpty()) {
                String hydreamMain = targetNames.iterator().next();
                boolean found = false;
                for (ProductRecord o : out) {
                    if (hydreamMain.equals(o.getOrEmpty("상품명칭"))
                            && "1종(신계약체결용)".equals(o.getOrEmpty("세부종목1"))
                            && "종신연금형".equals(o.getOrEmpty("세부종목2"))
                            && o.getOrEmpty("세부종목3").isEmpty()
                            && o.getOrEmpty("세부종목4").isEmpty()) {
                        found = true; break;
                    }
                }
                if (!found) out.add(buildObj(hydreamMain, Arrays.asList("1종(신계약체결용)", "종신연금형")));
            }
        }

        return uniqueRecords(out);
    }

    // ── build_objects ─────────────────────────────────────────────
    private static final Pattern REJOIN_STRIP = Pattern.compile("\\s*재가입용\\s*");

    public static List<ProductRecord> buildObjects(
            List<String> productNames,
            List<List<String>> detailAxes,
            List<List<String>> rowCombos,
            boolean annuityMode) {
        List<List<String>> combos;
        if (rowCombos != null && !rowCombos.isEmpty()) {
            combos = rowCombos;
        } else if (detailAxes != null && !detailAxes.isEmpty()) {
            combos = cartesian(detailAxes);
        } else {
            combos = new ArrayList<>();
            combos.add(new ArrayList<>());
        }

        if (annuityMode) {
            List<List<String>> expanded = new ArrayList<>();
            for (List<String> c : combos) expanded.addAll(PcAxes.expandAnnuityCombo(c));
            combos = expanded;

            List<List<String>> normalized = new ArrayList<>();
            for (List<String> c : combos) {
                List<String> cc = new ArrayList<>(c);
                if (!cc.isEmpty() && "환급플랜".equals(cc.get(0))) {
                    cc.add(0, "상속연금형");
                }
                if (cc.size() == 2 && "종신연금형".equals(cc.get(0)) && "일반형".equals(cc.get(1))) continue;
                if (cc.size() == 3 && cc.get(0).startsWith("2종(")
                        && "종신연금형".equals(cc.get(1))
                        && ("적립형".equals(cc.get(2)) || "거치형".equals(cc.get(2)))) {
                    String third = cc.get(2);
                    cc = new ArrayList<>(Arrays.asList(cc.get(0), cc.get(1), "", third));
                }
                normalized.add(cc);
            }
            combos = normalized;
        }

        boolean isLifeInsurance = anyContains(productNames, "종신보험");
        boolean isMedical = anyContains(productNames, "실손의료비보장보험")
                && (anyContains(productNames, "급여")
                || anyContains(productNames, "재가입용")
                || anyContains(productNames, "계약전환"));

        List<ProductRecord> objects = new ArrayList<>();
        for (String pname : productNames) {
            String pnameBase = REJOIN_STRIP.matcher(pname).replaceAll("").trim();
            for (List<String> combo : combos) {
                List<String> cc = normalizeCombo(combo);

                if (isMedical && !cc.isEmpty() && "3대비급여형".equals(cc.get(0))) continue;
                if (!cc.isEmpty() && pname.contains("스마트V상해보험") && "2종".equals(cc.get(0))) continue;
                if (pname.contains("스마트H상해보험") && !cc.isEmpty() && "1종".equals(cc.get(0))) {
                    List<String> next = new ArrayList<>();
                    next.add("2종");
                    next.addAll(cc.subList(1, cc.size()));
                    cc = next;
                }

                if (isLifeInsurance && !cc.isEmpty() && "적립형 계약".equals(cc.get(0)) && cc.size() == 1
                        && (pname.contains("한화생명 H종신보험")
                        || pname.contains("한화생명 간편가입 하나로H종신보험"))) {
                    cc = new ArrayList<>(Arrays.asList(cc.get(0), "", ""));
                }

                if (isMedical) {
                    if (pname.contains("재가입용")) {
                        if (!cc.isEmpty()
                                && !Arrays.asList("상해입원형", "상해통원형", "질병입원형", "질병통원형").contains(cc.get(0))) {
                            continue;
                        }
                    }
                } else if (pname.contains("비급여")) {
                    if (!cc.isEmpty()
                            && !Arrays.asList("상해급여형", "질병급여형", "3대비급여형").contains(cc.get(0))) {
                        continue;
                    }
                }

                String displayName = pnameBase;
                String productName = pnameBase;
                List<String> suffixTokens = new ArrayList<>(cc);

                if (cc.size() == 2 && (cc.get(0).equals("적립형") || cc.get(0).equals("거치형"))
                        && "상속연금플러스형".equals(cc.get(1))) {
                    cc = new ArrayList<>(Arrays.asList(cc.get(0), cc.get(1), ""));
                } else if (cc.size() == 4 && cc.get(0).startsWith("2종(")
                        && "종신연금형".equals(cc.get(1))
                        && "".equals(cc.get(2))
                        && (cc.get(3).equals("적립형") || cc.get(3).equals("거치형"))) {
                    cc = new ArrayList<>(Arrays.asList(cc.get(0), cc.get(1), null, cc.get(3)));
                }

                ProductRecord obj = new ProductRecord();
                obj.put("상품명칭", displayName);
                for (int i = 0; i < cc.size(); i++) {
                    String token = cc.get(i);
                    if (token == null) continue;
                    obj.put("세부종목" + (i + 1), token);
                }
                StringBuilder suffix = new StringBuilder();
                for (String t : suffixTokens) {
                    if (t == null || t.isEmpty()) continue;
                    if (suffix.length() > 0) suffix.append(' ');
                    suffix.append(t);
                }
                if (suffix.length() > 0) {
                    obj.put("상품명", Normalizer.normalizeWs(productName + " " + suffix));
                } else {
                    obj.put("상품명", Normalizer.normalizeWs(productName));
                }
                objects.add(obj);
            }
        }

        List<ProductRecord> out = uniqueRecords(objects);
        out.sort(Comparator
                .comparing((ProductRecord r) -> r.getOrEmpty("상품명칭"))
                .thenComparing(r -> r.getOrEmpty("상품명")));
        return out;
    }

    // ── helpers ───────────────────────────────────────────────────
    private static boolean anyContains(List<String> names, String needle) {
        if (names == null) return false;
        for (String n : names) if (n != null && n.contains(needle)) return true;
        return false;
    }

    private static List<String> normalizeCombo(List<String> combo) {
        List<String> out = new ArrayList<>();
        for (String v : combo) {
            if ("상해비급여형".equals(v)) out.add("상해급여형");
            else if ("질병비급여형".equals(v)) out.add("질병급여형");
            else out.add(v);
        }
        return out;
    }

    private static List<List<String>> cartesian(List<List<String>> axes) {
        List<List<String>> result = new ArrayList<>();
        result.add(new ArrayList<>());
        for (List<String> axis : axes) {
            List<List<String>> next = new ArrayList<>();
            for (List<String> prefix : result) {
                for (String v : axis) {
                    List<String> ext = new ArrayList<>(prefix);
                    ext.add(v);
                    next.add(ext);
                }
            }
            result = next;
        }
        return result;
    }
}
