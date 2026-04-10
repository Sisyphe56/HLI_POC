package com.hanwha.setdata.docx;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a .docx file and produces {@link DocxContent}.
 *
 * <p>Ports Python {@code extract_docx_content(docx_path)} from
 * {@code extract_insurance_period_v2.py}: walks body elements in document
 * order, tracking "최초계약"/"갱신계약" section headings so each table can be
 * tagged with its section context.
 *
 * <h3>python-docx parity: merged cell duplication</h3>
 * Python {@code row.cells} expands each horizontally-merged {@code <w:tc>}
 * by its {@code gridSpan} — a cell spanning 3 columns appears 3 times in the
 * iteration. POI's {@link XWPFTableRow#getTableCells()} returns unique cells
 * only. To match Python line counts (and text-search behavior), we expand
 * gridSpan manually by reading raw {@link CTTc} list.
 */
public final class DocxReader {

    public DocxContent read(Path docxPath) throws IOException {
        try (InputStream in = Files.newInputStream(docxPath);
             XWPFDocument doc = new XWPFDocument(in)) {
            return read(doc);
        }
    }

    public DocxContent read(XWPFDocument doc) {
        List<String> lines = new ArrayList<>();
        List<List<List<String>>> tables = new ArrayList<>();
        List<String> tableSections = new ArrayList<>();

        String currentSection = "";

        for (IBodyElement el : doc.getBodyElements()) {
            if (el instanceof XWPFParagraph p) {
                String normalized = Normalizer.normalizeWs(p.getText());
                if (!normalized.isEmpty()) {
                    lines.add(normalized);
                    String stripped = Normalizer.stripAllWs(normalized);
                    if (stripped.contains("최초계약")) {
                        currentSection = "최초계약";
                    } else if (stripped.contains("갱신계약")) {
                        currentSection = "갱신계약";
                    }
                }
            } else if (el instanceof XWPFTable t) {
                List<List<String>> rowsExpanded = buildRowsWithGridSpan(t);
                tables.add(rowsExpanded);
                tableSections.add(currentSection);
                for (List<String> row : rowsExpanded) {
                    for (String cellText : row) {
                        String normalized = Normalizer.normalizeWs(cellText);
                        if (!normalized.isEmpty()) {
                            lines.add(normalized);
                        }
                    }
                }
            }
        }

        return new DocxContent(lines, tables, tableSections);
    }

    /**
     * Build row data expanding horizontally-merged (gridSpan) and vertically-merged
     * (vMerge) cells, matching python-docx {@code row.cells} semantics.
     *
     * <p>python-docx's {@code Table._cells} builds a logical grid where a vMerge
     * <em>continuation</em> cell ({@code <w:vMerge/>} with no val) returns the
     * <em>master</em> cell's text from an earlier row. We emulate this with a
     * per-grid-column master text map that persists across rows and is reset on
     * {@code vMerge val="restart"} or on a non-merged cell.
     */
    private List<List<String>> buildRowsWithGridSpan(XWPFTable table) {
        List<List<String>> result = new ArrayList<>();
        Map<Integer, String> vMasterByGrid = new HashMap<>();
        for (XWPFTableRow row : table.getRows()) {
            Map<CTTc, XWPFTableCell> cellByCt = new HashMap<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cellByCt.put(cell.getCTTc(), cell);
            }
            List<String> expanded = new ArrayList<>();
            int gridCol = 0;
            for (CTTc tc : row.getCtRow().getTcList()) {
                int span = gridSpanOf(tc);
                VMergeState vmerge = vMergeOf(tc);
                XWPFTableCell cell = cellByCt.get(tc);
                String text;
                if (vmerge == VMergeState.CONTINUE) {
                    text = vMasterByGrid.getOrDefault(gridCol, cell != null ? joinParagraphs(cell) : "");
                } else {
                    text = cell != null ? joinParagraphs(cell) : "";
                    if (vmerge == VMergeState.RESTART) {
                        for (int i = 0; i < span; i++) vMasterByGrid.put(gridCol + i, text);
                    } else {
                        for (int i = 0; i < span; i++) vMasterByGrid.remove(gridCol + i);
                    }
                }
                for (int i = 0; i < span; i++) expanded.add(text);
                gridCol += span;
            }
            result.add(expanded);
        }
        return result;
    }

    private enum VMergeState { NONE, RESTART, CONTINUE }

    private static VMergeState vMergeOf(CTTc tc) {
        CTTcPr pr = tc.getTcPr();
        if (pr == null || pr.getVMerge() == null) return VMergeState.NONE;
        STMerge.Enum val = pr.getVMerge().getVal();
        if (val == null) return VMergeState.CONTINUE; // absent val = continuation
        return val == STMerge.RESTART ? VMergeState.RESTART : VMergeState.CONTINUE;
    }

    /**
     * Python {@code XWPFTableCell} text is {@code '\n'.join(p.text for p in cell.paragraphs)}.
     * POI's {@link XWPFTableCell#getText()} concatenates without separators, which collapses
     * "보험료\n예시" to "보험료예시". Mirror python-docx behavior here.
     */
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
}
