package com.hanwha.setdata.extract;

import com.hanwha.setdata.config.OverridesConfig;
import com.hanwha.setdata.model.ProductRecord;
import com.hanwha.setdata.output.PythonStyleJson;
import com.hanwha.setdata.store.DocxStore;
import com.hanwha.setdata.store.DocxStoreAdapters;
import com.hanwha.setdata.store.SqliteDocxStore;
import com.hanwha.setdata.util.Normalizer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main entry point for 상품분류 extraction — Java port of
 * {@code extract_product_classification_v2.py}.
 */
public final class ProductClassificationExtractor {

    public record Result(List<ProductRecord> objects, boolean hasDependent) {}

    private final OverridesConfig config;
    private final DocxStore store;

    public ProductClassificationExtractor(OverridesConfig config, DocxStore store) {
        this.config = config;
        this.store = store;
    }

    public Result extractWithMeta(Path docxPath) throws IOException {
        PcDocx.Result docx = DocxStoreAdapters.toPcDocxResult(store.get(docxPath));
        String fullText = docx.fullText();
        String filename = docxPath.getFileName().toString();

        boolean hasDependent = PcNames.hasDependentEndorsement(fullText);
        List<String> productNames = PcNames.findProductNames(fullText, filename);

        String detailsSectionText = PcNames.findDetailsSectionText(fullText);
        List<String> details = PcText.splitDetailCandidates(detailsSectionText);
        boolean excludeStandard = PcNames.shouldExcludeStandard(fullText);
        boolean isRejoin = filename.contains("재가입용");

        if (isRejoin) {
            List<String> renamed = new ArrayList<>();
            for (String n : productNames) renamed.add(n + " 재가입용");
            productNames = renamed;
        }

        // Extract tables with 세부보험종목
        List<List<String>> axesFromTables = new ArrayList<>();
        List<List<String>> rowCombos = new ArrayList<>();
        for (List<List<String>> t : docx.tables()) {
            StringBuilder flat = new StringBuilder();
            for (List<String> row : t) {
                for (String c : row) {
                    if (flat.length() > 0) flat.append(' ');
                    flat.append(PcText.cleanItem(c == null ? "" : c));
                }
            }
            if (!flat.toString().contains("세부보험종목")) continue;
            rowCombos.addAll(PcAxes.extractRowCombosFromDetailTable(t, excludeStandard));
            axesFromTables.addAll(PcAxes.extractAxesFromDetailTable(t));
        }

        if (rowCombos.isEmpty()) {
            rowCombos = PcAxes.extractRowCombosFromTextSection(
                    detailsSectionText, excludeStandard, productNames);
        }

        List<List<String>> detailAxes;
        if (!axesFromTables.isEmpty()) {
            detailAxes = PcAxes.dedupeAxes(axesFromTables);
        } else if (!details.isEmpty()) {
            detailAxes = new ArrayList<>();
            detailAxes.add(details);
        } else {
            detailAxes = new ArrayList<>();
        }
        if (excludeStandard) {
            List<List<String>> next = new ArrayList<>();
            for (List<String> axis : detailAxes) {
                List<String> filtered = new ArrayList<>();
                for (String x : axis) if (!"표준형".equals(x)) filtered.add(x);
                if (!filtered.isEmpty()) next.add(filtered);
            }
            detailAxes = next;
        }

        List<ProductRecord> objects = PcOverrides.buildObjects(
                productNames, detailAxes, rowCombos, PcNames.isAnnuityProduct(productNames));

        if (anyContains(productNames, "케어백간병플러스보험")) {
            Set<String> careMainNames = new HashSet<>();
            Set<String> careDependentNames = new HashSet<>();
            for (String n : productNames) {
                if (n == null) continue;
                if (n.contains("케어백간병플러스보험")) careMainNames.add(n);
                if (n.contains("특약")) careDependentNames.add(n);
            }
            List<ProductRecord> filtered = new ArrayList<>();
            for (ProductRecord obj : objects) {
                String name = obj.getOrEmpty("상품명칭");
                String p1 = obj.getOrEmpty("세부종목1");
                String p3 = obj.getOrEmpty("세부종목3");
                if (careMainNames.contains(name)) {
                    if (!p1.equals("보장형 계약") && !p1.equals("적립형 계약Ⅱ")) continue;
                    if (p1.equals("적립형 계약Ⅱ") && !p3.isEmpty()) continue;
                } else if (careDependentNames.contains(name)) {
                    if (p1.isEmpty() || !p1.startsWith("간편가입형")) continue;
                }
                filtered.add(obj);
            }
            objects = filtered;
        }

        objects = PcOverrides.applySmartAccidentProductOverrides(filename, objects);
        objects = PcOverrides.applyDentalPeriodOverrides(objects, productNames, fullText);
        objects = PcOverrides.applyHydreamAnnuityOverrides(objects, productNames, fullText);
        if (anyContains(productNames, "진심가득H보장보험")) {
            List<ProductRecord> next = new ArrayList<>();
            for (ProductRecord obj : objects) {
                String d1 = obj.getOrEmpty("세부종목1");
                String d2 = obj.getOrEmpty("세부종목2");
                if ((d1.equals("New Start 계약") || d1.equals("보장형 계약")) && d2.isEmpty()) continue;
                next.add(obj);
            }
            objects = next;
        }
        objects = PcOverrides.applyUnmatchedProductOverrides(objects, productNames);
        objects = PcOverrides.applyClassificationOverrides(filename, objects, config);

        return new Result(objects, hasDependent);
    }

    public List<ProductRecord> extract(Path docxPath) throws IOException {
        return extractWithMeta(docxPath).objects();
    }

    private static final Pattern STEM_EXT = Pattern.compile("\\.\\w+$");
    private static final Pattern STEM_UP_TO_SAEOP = Pattern.compile("^(.+사업방법서)");

    public static String makeOutputStem(String filename) {
        String name = Normalizer.nfc(filename);
        String noExt = STEM_EXT.matcher(name).replaceAll("");
        Matcher m = STEM_UP_TO_SAEOP.matcher(noExt);
        return m.find() ? m.group(1) : noExt;
    }

    private static boolean anyContains(List<String> names, String needle) {
        for (String n : names) if (n != null && n.contains(needle)) return true;
        return false;
    }

    // ── CLI ────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        // Find project root (contains 사업방법서_워드)
        Path targetDir = null, outputDir = null, overridesPath = null;
        Path cur = root;
        for (int i = 0; i < 5; i++) {
            Path candidate = cur.resolve("사업방법서_워드");
            if (Files.isDirectory(candidate)) {
                targetDir = candidate;
                outputDir = cur.resolve("상품분류");
                overridesPath = cur.resolve("config").resolve("product_overrides.json");
                break;
            }
            if (cur.getParent() == null) break;
            cur = cur.getParent();
        }

        String singleDocx = null;
        String singleOutput = null;
        for (int i = 0; i < args.length; i++) {
            if ("--docx".equals(args[i]) && i + 1 < args.length) singleDocx = args[++i];
            else if ("--output".equals(args[i]) && i + 1 < args.length) singleOutput = args[++i];
            else if ("--target-dir".equals(args[i]) && i + 1 < args.length) targetDir = Paths.get(args[++i]);
            else if ("--output-dir".equals(args[i]) && i + 1 < args.length) outputDir = Paths.get(args[++i]);
            else if ("--overrides".equals(args[i]) && i + 1 < args.length) overridesPath = Paths.get(args[++i]);
        }

        OverridesConfig cfg = (overridesPath != null && Files.exists(overridesPath))
                ? OverridesConfig.load(overridesPath) : null;
        DocxStore store = new SqliteDocxStore(targetDir.getParent().resolve("cache/docx_cache.db"));
        ProductClassificationExtractor extractor = new ProductClassificationExtractor(cfg, store);

        List<Path> docs = new ArrayList<>();
        if (singleDocx != null) {
            docs.add(Paths.get(singleDocx));
        } else {
            if (targetDir == null || !Files.isDirectory(targetDir)) {
                System.err.println("target dir not found: " + targetDir);
                System.exit(1);
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetDir, "*.docx")) {
                for (Path p : stream) {
                    if (p.getFileName().toString().startsWith("~$")) continue;
                    docs.add(p);
                }
            }
            Collections.sort(docs);
        }

        if (outputDir != null) Files.createDirectories(outputDir);

        for (Path docx : docs) {
            List<ProductRecord> objs = extractor.extract(docx);
            String outStem = makeOutputStem(docx.getFileName().toString());
            Path outPath;
            if (singleOutput != null && singleDocx != null) {
                outPath = Paths.get(singleOutput);
            } else {
                outPath = outputDir.resolve(outStem + ".json");
            }
            PythonStyleJson.writeFile(outPath, objs);
            System.out.printf("%s -> %s (%d items)%n",
                    docx.getFileName(), outPath.getFileName(), objs.size());

            for (PcOverrides.AliasOutput alias :
                    PcOverrides.getAliasOutputs(docx.getFileName().toString(), objs, cfg)) {
                Path aliasOut = outputDir.resolve(alias.outputStem() + ".json");
                PythonStyleJson.writeFile(aliasOut, alias.objects());
                System.out.printf("  + alias: %s (%d items)%n",
                        aliasOut.getFileName(), alias.objects().size());
            }
        }
    }
}
