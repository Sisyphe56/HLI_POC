package com.hanwha.setdata.extract.joinage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ports of secondary table parsers in {@code extract_join_age_v2.py}:
 * parse_variant_age_table, parse_direct_age_table, parse_period_gender_age_table,
 * parse_inline_age_table, parse_spin_paym_age_table, parse_max_age_table,
 * parse_double_gubun_age_table, parse_deduction_table.
 */
public final class JaOtherTables {

    private JaOtherTables() {}

    private static final int UF = Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern PAYM_PATTERN = Pattern.compile(
            "^(전기납|일시납|종신납|\\d+년납|\\d+세납)", UF);
    private static final Pattern VARIANT_JONG = Pattern.compile("\\d+종", UF);
    private static final Pattern NUM_DIGITS = Pattern.compile("(\\d+)\\s*세?", UF);
    private static final Pattern AGE_WORD = Pattern.compile("(\\d+)\\s*세", UF);

    private static List<String> normRow(List<String> row) {
        List<String> out = new ArrayList<>();
        for (String c : row) out.add(JaText.normalizeWs(c == null ? "" : c));
        return out;
    }

    // ── parse_variant_age_table ────────────────────────────────────
    public static List<Map<String, Object>> parseVariant(List<List<List<String>>> tables) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (List<List<String>> table : tables) {
            if (table == null || table.size() < 3) continue;
            List<String> h0 = normRow(table.get(0));
            List<String> h1 = table.size() > 1 ? normRow(table.get(1)) : new ArrayList<>();
            List<String> h2 = table.size() > 2 ? normRow(table.get(2)) : new ArrayList<>();

            boolean hasVariantR0 = false;
            for (String h : h0) if (!h.isEmpty() && VARIANT_JONG.matcher(h).find()) { hasVariantR0 = true; break; }
            boolean hasGubunR0 = false;
            for (String h : h0) if (h.contains("구") && h.contains("분")) { hasGubunR0 = true; break; }
            boolean hasGubunR1 = false;
            for (String h : h1) if (h.contains("구") && h.contains("분")) { hasGubunR1 = true; break; }
            boolean hasGenderR1 = false;
            for (String h : h1) if (h.contains("남자") || h.contains("남 자")) { hasGenderR1 = true; break; }
            boolean hasGenderR2 = false;
            for (String h : h2) if (h.contains("남자") || h.contains("남 자")) { hasGenderR2 = true; break; }

            List<String> variantRow = h0;
            List<String> genderRow;
            int dataStart;
            if (hasVariantR0 && hasGubunR1 && hasGenderR2) {
                genderRow = h2;
                dataStart = 3;
            } else if (hasVariantR0 && hasGubunR0 && hasGenderR1) {
                genderRow = h1;
                dataStart = 2;
            } else {
                continue;
            }
            if (table.size() <= dataStart) continue;

            Map<Integer, String> colVariant = new LinkedHashMap<>();
            String lastVariant = "";
            for (int ci = 0; ci < variantRow.size(); ci++) {
                String cell = variantRow.get(ci);
                if (!cell.isEmpty() && VARIANT_JONG.matcher(cell).find()) lastVariant = cell;
                if (!lastVariant.isEmpty() && ci > 0) colVariant.put(ci, lastVariant);
            }

            Map<Integer, String> colGender = new LinkedHashMap<>();
            for (int ci = 0; ci < genderRow.size(); ci++) {
                String cell = genderRow.get(ci);
                if (cell.contains("남자") || cell.contains("남 자")) colGender.put(ci, "1");
                else if (cell.contains("여자") || cell.contains("여 자")) colGender.put(ci, "2");
            }

            if (colVariant.isEmpty() || colGender.isEmpty()) continue;

            for (int ri = dataStart; ri < table.size(); ri++) {
                List<String> rowCells = normRow(table.get(ri));
                boolean any = false;
                for (String c : rowCells) if (!c.isEmpty()) { any = true; break; }
                if (!any) continue;
                String paymText = rowCells.isEmpty() ? "" : rowCells.get(0);
                if (paymText.isEmpty()) continue;

                List<Integer> sortedVarKeys = new ArrayList<>(colVariant.keySet());
                Collections.sort(sortedVarKeys);
                for (int ci : sortedVarKeys) {
                    if (ci >= rowCells.size() || rowCells.get(ci).isEmpty()) continue;
                    Matcher m = NUM_DIGITS.matcher(rowCells.get(ci));
                    if (!m.find()) continue;
                    String variant = colVariant.getOrDefault(ci, "");
                    String gender = colGender.getOrDefault(ci, "");
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("context", variant);
                    r.put("납입기간", paymText);
                    r.put("성별", gender);
                    r.put("최대가입나이", m.group(1));
                    results.add(r);
                }
            }
        }
        return results;
    }

    // ── parse_direct_age_table ─────────────────────────────────────
    public static List<Map<String, Object>> parseDirect(List<List<List<String>>> tables) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (List<List<String>> table : tables) {
            if (table == null || table.size() < 2) continue;
            List<String> header = normRow(table.get(0));
            String headerText = String.join(" ", header);
            boolean hasGubun = false;
            for (String h : header) if (h.contains("구") && h.contains("분")) { hasGubun = true; break; }
            boolean hasGenderHeader = headerText.contains("남자") || headerText.contains("여자");
            boolean hasManki = false;
            for (String h : header) if (h.contains("만기")) { hasManki = true; break; }
            if (!(hasGubun && hasGenderHeader && !hasManki)) continue;

            List<Integer> maleCols = new ArrayList<>();
            List<Integer> femaleCols = new ArrayList<>();
            for (int ci = 0; ci < header.size(); ci++) {
                if (header.get(ci).contains("남자")) maleCols.add(ci);
                if (header.get(ci).contains("여자")) femaleCols.add(ci);
            }
            if (maleCols.isEmpty() && femaleCols.isEmpty()) continue;

            for (int ri = 1; ri < table.size(); ri++) {
                List<String> cells = normRow(table.get(ri));
                String context = "";
                for (int ci = 0; ci < cells.size(); ci++) {
                    if (!cells.get(ci).isEmpty() && !maleCols.contains(ci) && !femaleCols.contains(ci)) {
                        context = cells.get(ci); break;
                    }
                }
                if (context.isEmpty()) continue;

                for (int ci : maleCols) {
                    if (ci < cells.size()) {
                        String[] parsed = JaText.parseAgeCell(cells.get(ci));
                        if (parsed != null) {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("context", context);
                            r.put("성별", "1");
                            r.put("최소가입나이", parsed[0] == null ? "" : parsed[0]);
                            r.put("최대가입나이", parsed[1]);
                            results.add(r);
                        }
                    }
                }
                for (int ci : femaleCols) {
                    if (ci < cells.size()) {
                        String[] parsed = JaText.parseAgeCell(cells.get(ci));
                        if (parsed != null) {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("context", context);
                            r.put("성별", "2");
                            r.put("최소가입나이", parsed[0] == null ? "" : parsed[0]);
                            r.put("최대가입나이", parsed[1]);
                            results.add(r);
                        }
                    }
                }

                // male+female columns exist: check if female empty → collapse to gender=''
                if (!maleCols.isEmpty() && !femaleCols.isEmpty()) {
                    boolean femaleEmpty = true;
                    for (int ci : femaleCols) {
                        String v = ci < cells.size() ? cells.get(ci) : "";
                        if (!JaText.normalizeWs(v).isEmpty()) { femaleEmpty = false; break; }
                    }
                    if (femaleEmpty) {
                        for (int ci : maleCols) {
                            if (ci < cells.size()) {
                                String[] parsed = JaText.parseAgeCell(cells.get(ci));
                                if (parsed != null) {
                                    final String ctx = context;
                                    final String maxA = parsed[1];
                                    results.removeIf(r -> ctx.equals(r.get("context"))
                                            && "1".equals(r.get("성별"))
                                            && maxA.equals(r.get("최대가입나이")));
                                    Map<String, Object> nr = new LinkedHashMap<>();
                                    nr.put("context", context);
                                    nr.put("성별", "");
                                    nr.put("최소가입나이", parsed[0] == null ? "" : parsed[0]);
                                    nr.put("최대가입나이", parsed[1]);
                                    results.add(nr);
                                }
                            }
                        }
                    }
                }
            }
        }
        return results;
    }

    // ── parse_period_gender_age_table ──────────────────────────────
    private static final Pattern CTX_LABEL = Pattern.compile(
            "^\\([가-힣]\\)\\s", UF);
    private static final Pattern CTX_LABEL_STRIP = Pattern.compile(
            "^\\([가-힣]\\)\\s*", UF);
    private static final Pattern SUB_BOUND = Pattern.compile("^나\\.\\s", UF);

    public static List<Map<String, Object>> parsePeriodGender(
            List<List<List<String>>> tables, List<String> lines) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> contextLabels = new ArrayList<>();
        if (lines != null) {
            boolean inAgeSec = false;
            for (String line : lines) {
                String stripped = line.trim();
                if (stripped.contains("가입나이")
                        && (stripped.contains("보험기간") || stripped.contains("납입기간"))) {
                    inAgeSec = true;
                    continue;
                }
                if (inAgeSec) {
                    if (SUB_BOUND.matcher(stripped).lookingAt()) break;
                    if (CTX_LABEL.matcher(stripped).lookingAt()) contextLabels.add(stripped);
                }
            }
        }

        int matchingTableIdx = 0;
        for (List<List<String>> table : tables) {
            if (table == null || table.size() < 2) continue;
            List<String> header = normRow(table.get(0));
            String headerText = String.join(" ", header);
            boolean hasIns = false, hasPaym = false;
            for (String h : header) {
                if (h.contains("보험기간")) hasIns = true;
                if (h.contains("납입기간")) hasPaym = true;
            }
            boolean hasGenderHeader = headerText.contains("남자") || headerText.contains("여자");
            if (!(hasIns && hasPaym && hasGenderHeader)) continue;

            String context = "";
            if (matchingTableIdx < contextLabels.size()) {
                String raw = contextLabels.get(matchingTableIdx);
                context = CTX_LABEL_STRIP.matcher(raw).replaceFirst("");
            }
            matchingTableIdx++;

            int insCol = -1, paymCol = -1;
            List<Integer> maleCols = new ArrayList<>(), femaleCols = new ArrayList<>();
            for (int ci = 0; ci < header.size(); ci++) {
                String h = header.get(ci);
                if (h.contains("보험기간") && insCol < 0) insCol = ci;
                if (h.contains("납입기간") && paymCol < 0) paymCol = ci;
                if (h.contains("남자")) maleCols.add(ci);
                if (h.contains("여자")) femaleCols.add(ci);
            }
            if (insCol < 0 || paymCol < 0 || (maleCols.isEmpty() && femaleCols.isEmpty())) continue;

            for (int ri = 1; ri < table.size(); ri++) {
                List<String> cells = normRow(table.get(ri));
                String insText = insCol < cells.size() ? cells.get(insCol) : "";
                String paymText = paymCol < cells.size() ? cells.get(paymCol) : "";
                for (int ci : maleCols) {
                    if (ci < cells.size()) {
                        String[] p = JaText.parseAgeCell(cells.get(ci));
                        if (p != null) {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("context", context);
                            r.put("성별", "1");
                            r.put("최소가입나이", p[0] == null ? "" : p[0]);
                            r.put("최대가입나이", p[1]);
                            r.put("보험기간", insText);
                            r.put("납입기간", paymText);
                            results.add(r);
                        }
                    }
                }
                for (int ci : femaleCols) {
                    if (ci < cells.size()) {
                        String[] p = JaText.parseAgeCell(cells.get(ci));
                        if (p != null) {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("context", context);
                            r.put("성별", "2");
                            r.put("최소가입나이", p[0] == null ? "" : p[0]);
                            r.put("최대가입나이", p[1]);
                            r.put("보험기간", insText);
                            r.put("납입기간", paymText);
                            results.add(r);
                        }
                    }
                }
            }
        }
        return results;
    }

    // ── parse_inline_age_table ─────────────────────────────────────
    private static final Pattern INLINE_INS1 = Pattern.compile("\\d+년만기|\\d+년$|종신", UF);
    private static final Pattern INLINE_INS2 = Pattern.compile("\\d+년$", UF);

    public static List<Map<String, Object>> parseInline(List<List<List<String>>> tables) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (List<List<String>> table : tables) {
            if (table == null || table.size() < 2) continue;
            List<String> allHeader = new ArrayList<>();
            int limit = Math.min(2, table.size());
            for (int hi = 0; hi < limit; hi++) {
                for (String c : table.get(hi)) allHeader.add(JaText.normalizeWs(c == null ? "" : c));
            }
            String headerText = String.join(" ", allHeader);
            if (headerText.contains("태아보장") || headerText.contains("태아보장기간")) continue;

            List<String> header = normRow(table.get(0));

            // Pattern 1: 만기 columns
            Map<Integer, String> mankiCols = new LinkedHashMap<>();
            for (int ci = 0; ci < header.size(); ci++) {
                if (header.get(ci).contains("만기") && ci > 0) mankiCols.put(ci, header.get(ci));
            }
            if (!mankiCols.isEmpty()) {
                for (int ri = 1; ri < table.size(); ri++) {
                    List<String> cells = normRow(table.get(ri));
                    String paymText = cells.isEmpty() ? "" : cells.get(0);
                    for (Map.Entry<Integer, String> e : mankiCols.entrySet()) {
                        int ci = e.getKey();
                        if (ci >= cells.size()) continue;
                        String[] parsed = JaText.parseAgeCell(cells.get(ci));
                        if (parsed == null) continue;
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("context", "");
                        r.put("보험기간", e.getValue());
                        r.put("납입기간", paymText);
                        r.put("성별", "");
                        r.put("최소가입나이", parsed[0] == null ? "" : parsed[0]);
                        r.put("최대가입나이", parsed[1]);
                        results.add(r);
                    }
                }
                continue;
            }

            boolean hasAgeHeader = headerText.contains("가입나이");
            if (hasAgeHeader) {
                for (int ri = 1; ri < table.size(); ri++) {
                    List<String> cells = normRow(table.get(ri));
                    String[] ageVal = null;
                    String insText = "";
                    String paymText = "";
                    for (String cell : cells) {
                        if (cell.isEmpty()) continue;
                        String[] parsed = JaText.parseAgeCell(cell);
                        if (parsed != null && ageVal == null) {
                            ageVal = parsed;
                        } else if (INLINE_INS1.matcher(cell).find() && insText.isEmpty()) {
                            insText = cell;
                        } else if (PAYM_PATTERN.matcher(cell).lookingAt() && paymText.isEmpty()) {
                            paymText = cell;
                        } else if (INLINE_INS2.matcher(cell).matches() && insText.isEmpty()) {
                            insText = cell;
                        }
                    }
                    if (ageVal != null) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("context", "");
                        r.put("보험기간", insText);
                        r.put("납입기간", paymText);
                        r.put("성별", "");
                        r.put("최소가입나이", ageVal[0] == null ? "" : ageVal[0]);
                        r.put("최대가입나이", ageVal[1]);
                        results.add(r);
                    }
                }
            }
        }
        return results;
    }

    // ── parse_spin_paym_age_table ──────────────────────────────────
    private static final Pattern GENDER_M_RE = Pattern.compile("[\\[]\\s*남\\s*자?\\s*[\\]]", UF);
    private static final Pattern GENDER_F_RE = Pattern.compile("[\\[]\\s*여\\s*자?\\s*[\\]]", UF);
    private static final Pattern NUM_ONLY = Pattern.compile("\\d+$", UF);
    private static final Pattern PAYM_SUB = Pattern.compile("(\\d+년납|\\d+세납|전기납|일시납)", UF);

    public static List<Map<String, Object>> parseSpinPaym(
            List<List<List<String>>> tables, List<String> lines) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> genderLabels = new ArrayList<>();
        if (lines != null) {
            for (String line : lines) {
                String nl = JaText.normalizeWs(line);
                if (GENDER_M_RE.matcher(nl).find()) genderLabels.add("1");
                else if (GENDER_F_RE.matcher(nl).find()) genderLabels.add("2");
            }
        }

        int spinTableIdx = 0;
        for (List<List<String>> table : tables) {
            if (table == null || table.size() < 3) continue;
            List<String> header0 = normRow(table.get(0));
            String headerText = String.join(" ", header0);
            if (!headerText.contains("연금개시나이")) continue;

            String tableGender = "";
            if (!genderLabels.isEmpty() && spinTableIdx < genderLabels.size()) {
                tableGender = genderLabels.get(spinTableIdx);
            }
            spinTableIdx++;

            List<String> header1 = table.size() > 1 ? normRow(table.get(1)) : new ArrayList<>();

            Map<Integer, String> paymCols = new LinkedHashMap<>();
            for (int ci = 0; ci < header1.size(); ci++) {
                String cell = header1.get(ci);
                if (PAYM_SUB.matcher(cell).lookingAt()) paymCols.put(ci, cell);
            }
            if (paymCols.isEmpty()) continue;

            Integer geochiCol = null;
            for (int ci = 0; ci < header0.size(); ci++) {
                if (header0.get(ci).contains("거치형")) { geochiCol = ci; break; }
            }

            for (int ri = 2; ri < table.size(); ri++) {
                List<String> cells = normRow(table.get(ri));
                String first = cells.isEmpty() ? "" : cells.get(0);
                Matcher spinM = AGE_WORD.matcher(first);
                if (!spinM.find()) continue;
                String spinVal = spinM.group(1);

                if (geochiCol != null) {
                    for (int offset = 0; offset < 3; offset++) {
                        int ci = geochiCol + offset;
                        if (ci < cells.size() && !cells.get(ci).isEmpty()
                                && NUM_ONLY.matcher(cells.get(ci)).matches()) {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("context", "거치형");
                            r.put("spin", spinVal);
                            r.put("납입기간", "");
                            r.put("성별", tableGender);
                            r.put("최대가입나이", cells.get(ci));
                            results.add(r);
                            break;
                        }
                    }
                }

                List<String> dataValues = new ArrayList<>();
                for (int ci = 1; ci < cells.size(); ci++) {
                    if (!cells.get(ci).isEmpty() && NUM_ONLY.matcher(cells.get(ci)).matches()) {
                        dataValues.add(cells.get(ci));
                    }
                }
                List<Integer> pk = new ArrayList<>(paymCols.keySet());
                Collections.sort(pk);
                List<String> paymList = new ArrayList<>();
                for (int ci : pk) paymList.add(paymCols.get(ci));
                for (int idx = 0; idx < paymList.size(); idx++) {
                    int dataIdx = idx + 1;
                    if (dataIdx < dataValues.size()) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("context", "적립형");
                        r.put("spin", spinVal);
                        r.put("납입기간", paymList.get(idx));
                        r.put("성별", tableGender);
                        r.put("최대가입나이", dataValues.get(dataIdx));
                        results.add(r);
                    }
                }
            }
        }
        return results;
    }

    // ── parse_max_age_table ────────────────────────────────────────
    public static List<Map<String, Object>> parseMaxAge(List<List<List<String>>> tables) {
        List<Map<String, Object>> results = new ArrayList<>();
        int tableGroupIdx = 0;

        for (List<List<String>> table : tables) {
            if (table == null || table.size() < 2) continue;
            List<String> header = normRow(table.get(0));
            int ageCol = -1;
            for (int ci = 0; ci < header.size(); ci++) {
                if (header.get(ci).contains("최고가입나이") || header.get(ci).contains("최고나이")) {
                    ageCol = ci; break;
                }
            }
            if (ageCol < 0) continue;
            int curGroup = tableGroupIdx++;
            String prevContext = "";
            String prevPaym = "";
            for (int ri = 1; ri < table.size(); ri++) {
                List<String> cells = normRow(table.get(ri));
                String context = "";
                String paym = "";
                String gender = "";
                String maxAgeText = "";
                for (int ci = 0; ci < cells.size(); ci++) {
                    String cell = cells.get(ci);
                    if (ci == ageCol) {
                        maxAgeText = cell;
                    } else if (ci == 0) {
                        context = !cell.isEmpty() ? cell : prevContext;
                        if (!cell.isEmpty()) prevContext = cell;
                    } else if (cell.contains("납") || cell.contains("년납")
                            || cell.contains("일시납") || cell.contains("전기납")) {
                        paym = !cell.isEmpty() ? cell : prevPaym;
                        if (!cell.isEmpty()) prevPaym = cell;
                    } else if ("남자".equals(cell) || "여자".equals(cell)) {
                        gender = "남자".equals(cell) ? "1" : "2";
                    } else if (cell.isEmpty() && ci == 1) {
                        paym = prevPaym;
                    }
                }
                if (maxAgeText.isEmpty()) continue;
                Matcher m = NUM_DIGITS.matcher(maxAgeText);
                if (!m.find()) continue;
                if (paym.isEmpty()) paym = prevPaym;
                if (paym.isEmpty() && PAYM_PATTERN.matcher(context).lookingAt()) paym = context;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("context", context);
                r.put("납입기간", paym);
                r.put("성별", gender);
                r.put("최대가입나이", m.group(1));
                r.put("table_group", curGroup);
                results.add(r);
            }
        }

        if (!results.isEmpty()) {
            String prevCtx = "";
            String prevPaymCarry = "";
            for (Map<String, Object> e : results) {
                Object c = e.get("context");
                String ctx = c == null ? "" : c.toString();
                if (!ctx.isEmpty()) prevCtx = ctx;
                else if (!prevCtx.isEmpty()) e.put("context", prevCtx);
                Object p = e.get("납입기간");
                String pay = p == null ? "" : p.toString();
                if (!pay.isEmpty()) prevPaymCarry = pay;
                else if (!prevPaymCarry.isEmpty()) e.put("납입기간", prevPaymCarry);
            }
        }

        // merge small continuation groups
        if (!results.isEmpty()) {
            TreeMap<Integer, Integer> counts = new TreeMap<>();
            for (Map<String, Object> e : results) {
                int g = ((Number) e.getOrDefault("table_group", 0)).intValue();
                counts.merge(g, 1, Integer::sum);
            }
            if (counts.size() > 1) {
                List<Integer> values = new ArrayList<>(counts.values());
                Collections.sort(values);
                int medianCnt = values.get(values.size() / 2);
                Integer prevGrp = null;
                double threshold = Math.max(2.0, medianCnt * 0.3);
                for (Map<String, Object> e : results) {
                    int g = ((Number) e.get("table_group")).intValue();
                    if (counts.get(g) <= threshold && prevGrp != null) {
                        e.put("table_group", prevGrp);
                    } else {
                        prevGrp = g;
                    }
                }
            }
        }
        return results;
    }

    // ── parse_double_gubun_age_table ───────────────────────────────
    private static final Pattern DG_LINE = Pattern.compile(
            ".*?(\\d+[년세]만기|\\d+년)\\s*[:：]\\s*(.+)", UF);
    private static final Pattern DG_PAYM_SPLIT = Pattern.compile("[,，、]");
    private static final Pattern YN_PAT = Pattern.compile("(\\d+)\\s*년납", UF);

    public static List<Map<String, Object>> parseDoubleGubun(
            List<List<List<String>>> tables, List<String> lines, String productName) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<List<String>> target = null;
        for (List<List<String>> table : tables) {
            if (table == null || table.size() < 2) continue;
            List<String> header = normRow(table.get(0));
            if (header.size() < 3) continue;
            int gubunCount = 0;
            for (int i = 0; i < 2; i++) {
                if (header.get(i).replace(" ", "").contains("구분")) gubunCount++;
            }
            boolean hasJoin = false;
            for (int i = 2; i < Math.min(4, header.size()); i++) {
                if (header.get(i).contains("가입나이")) { hasJoin = true; break; }
            }
            if (gubunCount >= 2 && hasJoin) { target = table; break; }
        }
        if (target == null) return results;

        Map<String, List<String>> paymMap = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.replace("\u200b", "");
            Matcher m = DG_LINE.matcher(line);
            if (m.matches()) {
                String insRaw = JaText.normalizeWs(m.group(1));
                String paymsRaw = m.group(2);
                List<String> payms = new ArrayList<>();
                for (String part : DG_PAYM_SPLIT.split(paymsRaw)) {
                    String p = JaText.normalizeWs(part);
                    if (!p.isEmpty() && (p.contains("년납") || p.contains("전기납") || p.contains("일시납"))) {
                        payms.add(p);
                    }
                }
                if (!payms.isEmpty()) paymMap.put(insRaw, payms);
            }
        }

        String prevInsRaw = "";
        for (int ri = 1; ri < target.size(); ri++) {
            List<String> cells = normRow(target.get(ri));
            if (cells.size() < 3) continue;
            String col0 = cells.get(0);
            String col1 = cells.get(1);
            String col2 = cells.get(2);
            if (col0.isEmpty() && col1.isEmpty() && col2.isEmpty()) continue;
            if (!col0.isEmpty()) prevInsRaw = col0;
            String insRaw = prevInsRaw;

            String[] ageParsed = JaText.parseAgeCell(col2);
            if (ageParsed == null) continue;
            String minAgeVal = ageParsed[0] == null ? "" : ageParsed[0];
            String maxAgeVal = ageParsed[1];
            if (maxAgeVal == null || maxAgeVal.isEmpty()) continue;

            var insCode = JaText.formatPeriod(insRaw);
            if (insCode.value().isEmpty()) continue;
            String insVal = insCode.value();
            String insDvsn = insCode.kind();

            if (col0.equals(col1)) {
                List<String> mapped = paymMap.getOrDefault(insRaw, new ArrayList<>());
                if (mapped.size() <= 1) {
                    results.add(newDgRec(minAgeVal, maxAgeVal, insVal, insDvsn, insVal, insDvsn));
                } else {
                    boolean hasJeon = false;
                    for (String p : mapped) if (p.contains("전기납")) { hasJeon = true; break; }
                    List<Integer> numPayms = new ArrayList<>();
                    for (String p : mapped) {
                        Matcher pm = YN_PAT.matcher(p);
                        if (pm.find()) numPayms.add(Integer.parseInt(pm.group(1)));
                    }
                    if (hasJeon && "N".equals(insDvsn) && insVal.matches("\\d+")) {
                        numPayms.add(Integer.parseInt(insVal));
                    }
                    if (!numPayms.isEmpty()) {
                        int mn = Collections.min(numPayms);
                        int mx = Collections.max(numPayms);
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("성별", "");
                        r.put("최소가입나이", minAgeVal);
                        r.put("최대가입나이", maxAgeVal);
                        r.put("최소보험기간", insVal);
                        r.put("최대보험기간", insVal);
                        r.put("보험기간구분코드", insDvsn);
                        r.put("최소납입기간", String.valueOf(mn));
                        r.put("최대납입기간", String.valueOf(mx));
                        r.put("납입기간구분코드", "N");
                        r.put("최소제2보기개시나이", "");
                        r.put("최대제2보기개시나이", "");
                        r.put("제2보기개시나이구분코드", "");
                        results.add(r);
                    } else {
                        results.add(newDgRec(minAgeVal, maxAgeVal, insVal, insDvsn, insVal, insDvsn));
                    }
                }
            } else {
                String paymText = col1;
                var paymCode = JaText.formatPeriod(paymText);
                String paymDvsn = paymCode.kind();
                String paymVal = paymCode.value();
                if (paymText.contains("전기납")) {
                    paymDvsn = insDvsn;
                    paymVal = insVal;
                }
                if (paymVal.isEmpty()) continue;
                results.add(newDgRec(minAgeVal, maxAgeVal, insVal, insDvsn, paymVal, paymDvsn));
            }
        }
        return results;
    }

    private static Map<String, Object> newDgRec(String minA, String maxA,
                                                 String insVal, String insDvsn,
                                                 String paymVal, String paymDvsn) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("성별", "");
        r.put("최소가입나이", minA);
        r.put("최대가입나이", maxA);
        r.put("최소보험기간", insVal);
        r.put("최대보험기간", insVal);
        r.put("보험기간구분코드", insDvsn);
        r.put("최소납입기간", paymVal);
        r.put("최대납입기간", paymVal);
        r.put("납입기간구분코드", paymDvsn);
        r.put("최소제2보기개시나이", "");
        r.put("최대제2보기개시나이", "");
        r.put("제2보기개시나이구분코드", "");
        return r;
    }

    // ── parse_deduction_table ──────────────────────────────────────
    private static final Pattern DED_INLINE_YEAR = Pattern.compile(
            "(\\d+)\\s*년납.*연금개시나이\\s*.\\s*(\\d+)", UF);
    private static final Pattern DED_INLINE_JEON = Pattern.compile(
            "전기납.*연금개시나이\\s*.\\s*(\\d+)", UF);
    private static final Pattern DED_INLINE_GEO = Pattern.compile(
            "^\\s*\\(?연금개시나이\\s*.\\s*(\\d+)\\)?\\s*세", UF);
    private static final Pattern DED_FORMULA = Pattern.compile(
            "연금개시나이\\s*[–\\-]\\s*(\\d+)", UF);
    private static final Pattern DED_SEC_END = Pattern.compile(
            "^[가-힣라마바사]\\.\\s|^\\d+\\.\\s", UF);

    /** Result holder for deduction table parsing. */
    public static final class Deduction {
        public final Map<String, Integer> paymDeductions;
        public final Integer geochiDeduction;
        public Deduction(Map<String, Integer> p, Integer g) {
            this.paymDeductions = p;
            this.geochiDeduction = g;
        }
    }

    public static Deduction parseDeduction(List<String> lines, List<List<List<String>>> tables) {
        Map<String, Integer> paymDeductions = new LinkedHashMap<>();
        Integer geochiDeduction = null;

        if (tables != null) {
            for (List<List<String>> table : tables) {
                if (table == null || table.size() < 2) continue;
                StringBuilder hb = new StringBuilder();
                for (String c : table.get(0)) {
                    if (hb.length() > 0) hb.append(' ');
                    hb.append(c == null ? "" : c);
                }
                String headerText = hb.toString();
                if (!headerText.contains("가입최고나이") && !headerText.contains("최고나이")) continue;
                if (!headerText.contains("납입기간") && table.size() > 1) {
                    StringBuilder hb2 = new StringBuilder(headerText);
                    hb2.append(' ');
                    for (String c : table.get(1)) {
                        hb2.append(' ').append(c == null ? "" : c);
                    }
                    headerText = hb2.toString();
                }
                if (!headerText.contains("납입기간")) continue;
                List<String> headerRow = table.get(0);
                List<String> secondRow = table.size() > 1 ? table.get(1) : new ArrayList<>();
                boolean hasGeochi = false;
                for (String c : headerRow) if (c != null && c.contains("거치")) { hasGeochi = true; break; }
                if (!hasGeochi) for (String c : secondRow) if (c != null && c.contains("거치")) { hasGeochi = true; break; }
                int jeokCol = 1;
                Integer geoCol = (hasGeochi && headerRow.size() > 2) ? 2 : null;
                int dataStart = 1;
                boolean secondRowLabel = false;
                for (String c : secondRow) {
                    if (c != null && (c.contains("적립") || c.contains("거치"))) { secondRowLabel = true; break; }
                }
                if (secondRowLabel) dataStart = 2;

                Map<String, Integer> localPayms = new LinkedHashMap<>();
                Integer localGeochi = null;
                for (int ri = dataStart; ri < table.size(); ri++) {
                    List<String> row = table.get(ri);
                    if (row.size() < 2) continue;
                    String paymText = row.get(0) == null ? "" : row.get(0).trim();
                    String formulaText = jeokCol < row.size() && row.get(jeokCol) != null
                            ? row.get(jeokCol).trim() : "";
                    Matcher mDed = DED_FORMULA.matcher(formulaText);
                    if (!mDed.find()) continue;
                    int dedVal = Integer.parseInt(mDed.group(1));
                    Matcher mPaym = YN_PAT.matcher(paymText);
                    if (mPaym.find()) {
                        localPayms.put(mPaym.group(1), dedVal);
                    } else if (paymText.contains("전기납")) {
                        localPayms.put("전기납", dedVal);
                    }
                    if (geoCol != null && geoCol < row.size()) {
                        String geoText = row.get(geoCol) == null ? "" : row.get(geoCol).trim();
                        Matcher mGeo = DED_FORMULA.matcher(geoText);
                        if (mGeo.find() && localGeochi == null) {
                            localGeochi = Integer.parseInt(mGeo.group(1));
                        }
                    }
                }
                if (!localPayms.isEmpty()) {
                    return new Deduction(localPayms, localGeochi);
                }
            }
        }

        boolean inSection = false;
        for (String line : lines) {
            if (line.contains("가입최고나이") && line.contains("연금개시나이")) {
                inSection = true;
                continue;
            }
            if (line.contains("가입최고나이") && (line.contains("납입기간") || line.contains("아래"))) {
                inSection = true;
                continue;
            }
            if (!inSection) continue;
            if (DED_SEC_END.matcher(line).lookingAt()) break;
            Matcher m = DED_INLINE_YEAR.matcher(line);
            if (m.find()) {
                paymDeductions.put(m.group(1), Integer.parseInt(m.group(2)));
                continue;
            }
            Matcher mj = DED_INLINE_JEON.matcher(line);
            if (mj.find()) {
                paymDeductions.put("전기납", Integer.parseInt(mj.group(1)));
                continue;
            }
            Matcher mg = DED_INLINE_GEO.matcher(line);
            if (mg.find()) {
                geochiDeduction = Integer.parseInt(mg.group(1));
            }
        }
        return new Deduction(paymDeductions, geochiDeduction);
    }
}
