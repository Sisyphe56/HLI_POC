package com.hanwha.setdata.extract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ports the product-name, names-block and details-section finders from
 * {@code extract_product_classification_v2.py} (lines 353–617).
 */
public final class PcNames {

    private PcNames() {}

    // ── has_dependent_endorsement ──────────────────────────────────
    private static final Pattern NUM_DOT_START = Pattern.compile("^\\s*\\d+\\.");
    private static final Pattern HANGUL_DOT_START = Pattern.compile("^\\s*[가-힣]\\.");
    private static final List<Pattern> NEG_PATTERNS = Arrays.asList(
            Pattern.compile("종속특약\\s*(?:은|는)\\s*없음"),
            Pattern.compile("종속특약\\s*(?:항목|항목의)\\s*없음"),
            Pattern.compile("종속특약\\s*(?:이|가)\\s*없(다|습니다|습니다\\.)"),
            Pattern.compile("종속특약\\s*해당\\s*없(?:음|다)")
    );
    private static final List<Pattern> POS_PATTERNS = Arrays.asList(
            Pattern.compile("종속특약[^\\n]{0,120}(?:계약|해당|해지|해당사항|상품|구분|종류|특약)"),
            Pattern.compile("\\d+\\)\\s*[^\\n]*(?:종속특약|특약)"),
            Pattern.compile("[·•\\-]\\s*[^\\n]*특약")
    );

    public static boolean hasDependentEndorsement(String fullText) {
        if (!fullText.contains("종속특약")) return false;
        String[] rawLines = fullText.split("\\n", -1);
        List<String> lines = new ArrayList<>(rawLines.length);
        for (String r : rawLines) lines.add(PcText.normalizeWs(r));

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.contains("종속특약")) continue;
            List<String> context = new ArrayList<>();
            context.add(line);
            int limit = Math.min(i + 8, lines.size());
            for (int j = i + 1; j < limit; j++) {
                String nxt = lines.get(j);
                if (NUM_DOT_START.matcher(nxt).find()) break;
                if (HANGUL_DOT_START.matcher(nxt).find()) break;
                context.add(nxt);
            }
            String text = String.join(" ", context);
            boolean anyNeg = false;
            for (Pattern p : NEG_PATTERNS) if (p.matcher(text).find()) { anyNeg = true; break; }
            if (anyNeg) continue;
            String compact = text.replace(" ", "");
            if (compact.contains("종속특약은없음") || compact.contains("종속특약해당없")) continue;
            for (Pattern p : POS_PATTERNS) if (p.matcher(text).find()) return true;
            if (text.contains("종속특약") && !compact.contains("없음")) return true;
        }
        return false;
    }

    // ── find_names_block ───────────────────────────────────────────
    private static final Pattern NAMES_BLOCK = Pattern.compile(
            "보험종목의\\s*명칭(.*?)(?:\\n\\s*나\\.\\s*보험종목의\\s*구성|\\n\\s*2\\.|\\n\\s*제\\s*2\\s*조|$)",
            Pattern.DOTALL);

    public static String findNamesBlock(String fullText) {
        Matcher m = NAMES_BLOCK.matcher(fullText);
        return m.find() ? m.group(1) : "";
    }

    // ── find_product_names ─────────────────────────────────────────
    private static final Set<String> SKIP_NAME_LINES = new HashSet<>(Arrays.asList(
            "구분", "구분 상품명칭", "상품명칭", "주계약", "종속특약",
            "(가)", "(나)", "온라인 채널", "온라인 이외 채널"
    ));
    private static final Pattern PAREN_NUM = Pattern.compile("^\\(\\d+\\)");
    private static final Pattern NUM_PAREN = Pattern.compile("^\\d+\\)");
    private static final Pattern OPEN_BRACKET = Pattern.compile("^[\\(\\[]");
    private static final Pattern LEAD_NAMES_COLON = Pattern.compile("^보험종목의\\s*명칭\\s*[:：]\\s*");
    private static final Pattern LEAD_DASH_BULLET = Pattern.compile("^[\\-•]\\s*");
    private static final Pattern ALT_IN_CORNER = Pattern.compile("「([^」]+)」");
    private static final Pattern STEM_CLEANUP = Pattern.compile("[\\s_]사업방법서.*$");
    private static final Pattern E_PATTERN = Pattern.compile("\\be");

    public static List<String> findProductNames(String fullText, String filename) {
        List<String> names = new ArrayList<>();
        List<String> renameContextNames = new ArrayList<>();
        String block = findNamesBlock(fullText);
        String stem = PcText.cleanItem(STEM_CLEANUP.matcher(filename).replaceAll(""));

        List<String> mergedLines = new ArrayList<>();
        for (String raw : block.split("\\n", -1)) {
            String line = PcText.cleanItem(raw);
            if (line.isEmpty()) continue;
            if (SKIP_NAME_LINES.contains(line)) continue;
            if (PAREN_NUM.matcher(line).find() || NUM_PAREN.matcher(line).find()) continue;
            if (!mergedLines.isEmpty()
                    && OPEN_BRACKET.matcher(line).find()
                    && !mergedLines.get(mergedLines.size() - 1).endsWith("무배당")) {
                int lastIdx = mergedLines.size() - 1;
                mergedLines.set(lastIdx, mergedLines.get(lastIdx) + " " + line);
            } else {
                mergedLines.add(line);
            }
        }

        for (String orig : mergedLines) {
            String line = orig;
            if (line.contains("구분") && line.contains("상품명칭")) continue;
            line = LEAD_NAMES_COLON.matcher(line).replaceAll("");
            line = LEAD_DASH_BULLET.matcher(line).replaceAll("");
            boolean inRenameContext = line.contains("으로 함");

            if (line.contains("※") && !line.contains("으로 함")) {
                int idx = line.indexOf('※');
                line = line.substring(0, idx).trim();
                if (line.isEmpty()) continue;
            }

            Matcher altM = ALT_IN_CORNER.matcher(line);
            while (altM.find()) {
                String a = PcText.cleanItem(altM.group(1));
                a = PcText.stripRolePrefix(a);
                a = PcText.normalizeSpecialTerms(a, filename);
                if ((a.contains("보험") || a.contains("특약"))
                        && (a.contains("무배당") || a.contains("배당"))
                        && !PcText.looksLikeNoise(a)) {
                    names.add(a);
                    if (inRenameContext) renameContextNames.add(a);
                }
            }

            for (String part : PcText.splitOutsideParentheses(line)) {
                String item = PcText.cleanItem(part);
                item = PcText.stripRolePrefix(item);
                item = PcText.normalizeSpecialTerms(item, filename);
                if ((item.contains("보험") || item.contains("특약"))
                        && (item.contains("무배당") || item.contains("배당"))
                        && !PcText.looksLikeNoise(item)) {
                    names.add(item);
                    if (inRenameContext) renameContextNames.add(item);
                }
            }
        }

        if (names.isEmpty()) names.add(stem);

        List<String> normalized = new ArrayList<>(names.size());
        for (String n : names) normalized.add(PcText.normalizeSpecialTerms(n, filename));

        List<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String n : normalized) {
            String cleaned = PcText.cleanItem(n);
            cleaned = PcText.normalizeSpecialTerms(cleaned, filename);
            if (cleaned.isEmpty() || !seen.add(cleaned)) continue;
            out.add(cleaned);
        }

        // Drop 종속특약 entries
        List<String> filtered = new ArrayList<>();
        for (String n : out) if (!n.contains("특약")) filtered.add(n);
        out = filtered;
        if (out.isEmpty()) {
            out.add(PcText.normalizeSpecialTerms(stem, filename));
        }

        boolean hasEChannel = E_PATTERN.matcher(stem).find();
        boolean hasInlineEName = false;
        for (String n : out) if (E_PATTERN.matcher(n).find()) { hasInlineEName = true; break; }
        if (hasEChannel) {
            List<String> hinted = new ArrayList<>();
            for (String n : out) if (n.contains("e")) hinted.add(n);
            if (!hinted.isEmpty()) out = hinted;
        } else if (hasInlineEName) {
            List<String> nonE = new ArrayList<>();
            for (String n : out) if (!n.contains("e")) nonE.add(n);
            if (!nonE.isEmpty()) out = nonE;
        }

        if (!renameContextNames.isEmpty() && out.contains(stem)) {
            List<String> only = new ArrayList<>();
            only.add(PcText.normalizeSpecialTerms(stem, filename));
            return only;
        }
        return out;
    }

    // ── find_details_section_text / find_details_section ──────────
    private static final Pattern DETAILS_START = Pattern.compile("보험종목의\\s*구성");
    private static final List<Pattern> DETAILS_END_PATTERNS = Arrays.asList(
            Pattern.compile("보험기간[^\\n]*가입나이"),
            Pattern.compile("\\n\\s*2\\."),
            Pattern.compile("\\n\\s*제\\s*2\\s*조")
    );

    public static String findDetailsSectionText(String fullText) {
        Matcher start = DETAILS_START.matcher(fullText);
        if (!start.find()) return "";
        String tail = fullText.substring(start.end());
        int min = -1;
        for (Pattern p : DETAILS_END_PATTERNS) {
            Matcher m = p.matcher(tail);
            if (m.find()) {
                int s = m.start();
                if (min < 0 || s < min) min = s;
            }
        }
        return min < 0 ? tail : tail.substring(0, min);
    }

    public static List<String> findDetailsSection(String fullText) {
        return PcText.splitDetailCandidates(findDetailsSectionText(fullText));
    }

    // ── extract_dependent_endorsement_types ────────────────────────
    private static final Pattern DEP_HEADER = Pattern.compile("^\\(?\\s*2\\s*[\\)\\.]?\\s*.*종속\\s*특약");
    private static final Pattern BIG_NUM_STOP = Pattern.compile("^\\(?\\s*(?:[3-9]|\\d{2,})\\s*[\\)\\.]");
    private static final Pattern HANGUL_STOP = Pattern.compile("^(?:[가-하][\\)\\.]|\\[가\\])");
    private static final Pattern NUM_STOP = Pattern.compile("^\\(?\\s*\\d+\\s*[\\)\\.]");
    private static final Pattern LEAD_DASH_LINE = Pattern.compile("^\\s*[-·•▪]\\s*(.+)$");

    public static List<String> extractDependentEndorsementTypes(String fullText) {
        String[] raw = fullText.split("\\n", -1);
        List<String> lines = new ArrayList<>(raw.length);
        for (String r : raw) lines.add(PcText.normalizeWs(r));
        List<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        boolean inDependent = false;

        for (String line : lines) {
            if (line.isEmpty()) continue;
            if (DEP_HEADER.matcher(line).find()) { inDependent = true; continue; }
            if (!inDependent) continue;
            if (BIG_NUM_STOP.matcher(line).find()) break;
            if (HANGUL_STOP.matcher(line).find()) break;
            if (NUM_STOP.matcher(line).find()) break;
            Matcher m = LEAD_DASH_LINE.matcher(line);
            if (!m.find()) continue;
            String token = PcText.cleanItem(m.group(1));
            if (token.isEmpty() || !seen.add(token)) continue;
            out.add(token);
        }
        return out;
    }

    // ── should_exclude_standard ────────────────────────────────────
    private static final Pattern EX1 = Pattern.compile("표준형.{0,80}(판매하지 않|비교용)", Pattern.DOTALL);
    private static final Pattern EX2 = Pattern.compile("판매하지 않.{0,80}표준형", Pattern.DOTALL);
    private static final Pattern EX3 = Pattern.compile("표준형.{0,120}사용", Pattern.DOTALL);
    private static final Pattern EX4 = Pattern.compile("비교용.{0,120}표준형", Pattern.DOTALL);
    private static final Pattern ALL_WS = Pattern.compile("\\s+");

    public static boolean shouldExcludeStandard(String fullText) {
        if (fullText == null) return false;
        String compact = ALL_WS.matcher(fullText).replaceAll("");
        return EX1.matcher(fullText).find()
                || EX2.matcher(fullText).find()
                || EX3.matcher(fullText).find()
                || EX4.matcher(fullText).find()
                || compact.contains("표준형은판매하지않")
                || (compact.contains("표준형") && compact.contains("비교용으로사용"));
    }

    // ── is_annuity_product ─────────────────────────────────────────
    public static boolean isAnnuityProduct(List<String> productNames) {
        for (String n : productNames) if (n != null && n.contains("연금")) return true;
        return false;
    }
}
