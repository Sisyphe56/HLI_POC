package com.hanwha.setdata.store;

import com.hanwha.setdata.util.Normalizer;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Parses a .docx file into a {@link ParsedDocument} with expanded cells
 * and merge metadata.
 *
 * <p>Reuses the same POI traversal logic as {@link com.hanwha.setdata.docx.DocxReader}
 * but additionally records per-cell merge flags ('N', 'H', 'V') so that
 * pdfplumber-style null views can be reconstructed without re-parsing.
 */
public final class DocxParser {

    private DocxParser() {}

    /**
     * Parse a .docx file into a {@link ParsedDocument}.
     */
    public static ParsedDocument parse(Path docxPath) throws IOException {
        String filename = Normalizer.nfc(docxPath.getFileName().toString());
        String fileHash = sha256(docxPath);

        try (InputStream in = Files.newInputStream(docxPath);
             XWPFDocument doc = new XWPFDocument(in)) {
            return parse(doc, filename, fileHash);
        }
    }

    static ParsedDocument parse(XWPFDocument doc, String filename, String fileHash) {
        List<ParsedDocument.BodyElement> elements = new ArrayList<>();
        List<ParsedTable> tables = new ArrayList<>();

        String currentSection = "";
        int tableSeq = 0;

        for (IBodyElement el : doc.getBodyElements()) {
            if (el instanceof XWPFParagraph p) {
                String normalized = Normalizer.normalizeWs(p.getText());
                if (!normalized.isEmpty()) {
                    elements.add(new ParsedDocument.BodyElement("P", normalized, currentSection, -1));
                    String stripped = Normalizer.stripAllWs(normalized);
                    if (stripped.contains("최초계약")) {
                        currentSection = "최초계약";
                    } else if (stripped.contains("갱신계약")) {
                        currentSection = "갱신계약";
                    }
                }
            } else if (el instanceof XWPFTable t) {
                TableParseResult tpr = buildTableWithMerges(t);
                ParsedTable pt = new ParsedTable(tableSeq, currentSection, tpr.cells, tpr.merges);
                tables.add(pt);
                elements.add(new ParsedDocument.BodyElement("T", null, currentSection, tableSeq));

                // Add cell text as body elements (for lines reconstruction)
                for (int ri = 0; ri < tpr.cells.length; ri++) {
                    for (int ci = 0; ci < tpr.cells[ri].length; ci++) {
                        String cellText = tpr.cells[ri][ci];
                        if (cellText != null) {
                            String normalized = Normalizer.normalizeWs(cellText);
                            if (!normalized.isEmpty()) {
                                elements.add(new ParsedDocument.BodyElement("C", normalized, currentSection, tableSeq));
                            }
                        }
                    }
                }

                tableSeq++;
            }
        }

        return new ParsedDocument(filename, fileHash, elements, tables);
    }

    // ── Table parsing with merge metadata ────────────────────────

    private record TableParseResult(String[][] cells, char[][] merges) {}

    private static TableParseResult buildTableWithMerges(XWPFTable table) {
        List<String[]> cellRows = new ArrayList<>();
        List<char[]> mergeRows = new ArrayList<>();
        Map<Integer, String> vMasterByGrid = new HashMap<>();

        for (XWPFTableRow row : table.getRows()) {
            Map<CTTc, XWPFTableCell> cellByCt = new HashMap<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cellByCt.put(cell.getCTTc(), cell);
            }

            List<String> expandedCells = new ArrayList<>();
            List<Character> expandedMerges = new ArrayList<>();
            int gridCol = 0;

            for (CTTc tc : row.getCtRow().getTcList()) {
                int span = gridSpanOf(tc);
                VMergeState vmerge = vMergeOf(tc);
                XWPFTableCell cell = cellByCt.get(tc);
                String text;
                char masterMerge;

                if (vmerge == VMergeState.CONTINUE) {
                    text = vMasterByGrid.getOrDefault(gridCol, cell != null ? joinParagraphs(cell) : "");
                    masterMerge = 'V';
                } else {
                    text = cell != null ? joinParagraphs(cell) : "";
                    masterMerge = 'N';
                    if (vmerge == VMergeState.RESTART) {
                        for (int i = 0; i < span; i++) vMasterByGrid.put(gridCol + i, text);
                    } else {
                        for (int i = 0; i < span; i++) vMasterByGrid.remove(gridCol + i);
                    }
                }

                // First slot: master merge flag; continuation slots: 'H'
                expandedCells.add(text);
                expandedMerges.add(masterMerge);
                for (int i = 1; i < span; i++) {
                    expandedCells.add(text);
                    expandedMerges.add('H');
                }
                gridCol += span;
            }

            cellRows.add(expandedCells.toArray(String[]::new));
            char[] mArr = new char[expandedMerges.size()];
            for (int i = 0; i < mArr.length; i++) mArr[i] = expandedMerges.get(i);
            mergeRows.add(mArr);
        }

        return new TableParseResult(
                cellRows.toArray(String[][]::new),
                mergeRows.toArray(char[][]::new));
    }

    // ── POI helpers (same as DocxReader) ─────────────────────────

    private enum VMergeState { NONE, RESTART, CONTINUE }

    private static VMergeState vMergeOf(CTTc tc) {
        CTTcPr pr = tc.getTcPr();
        if (pr == null || pr.getVMerge() == null) return VMergeState.NONE;
        STMerge.Enum val = pr.getVMerge().getVal();
        if (val == null) return VMergeState.CONTINUE;
        return val == STMerge.RESTART ? VMergeState.RESTART : VMergeState.CONTINUE;
    }

    private static String joinParagraphs(XWPFTableCell cell) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (XWPFParagraph p : cell.getParagraphs()) {
            if (!first) sb.append('\n');
            sb.append(p.getText());
            first = false;
        }
        return sb.toString();
    }

    private static int gridSpanOf(CTTc tc) {
        CTTcPr pr = tc.getTcPr();
        if (pr == null || pr.getGridSpan() == null) return 1;
        BigInteger val = pr.getGridSpan().getVal();
        return val == null ? 1 : Math.max(1, val.intValue());
    }

    // ── SHA-256 ─────────────────────────────────────────────────

    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(path);
            byte[] hash = digest.digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
