package com.hanwha.setdata.extract.cycle;

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
 * Payment-cycle-specific docx reader.
 *
 * <p>Ports Python {@code _extract_docx_with_sections(docx_path)} from
 * {@code extract_payment_cycle_v2.py}: walks body elements in document order,
 * collecting <em>only</em> paragraph text into {@code lines} (table cell text
 * is NOT appended, unlike the classification reader), and records the
 * "최초계약"/"갱신계약" section tag for each table. Tables are expanded using
 * python-docx {@code row.cells} semantics (merged cells duplicated), matching
 * the Python iteration pattern {@code [[cell.text for cell in row.cells] ...]}.
 */
public final class PcCycleDocx {

    public record Result(
            List<String> lines,
            List<List<List<String>>> tables,
            List<String> tableSections
    ) {}

    private PcCycleDocx() {}

    public static Result read(Path docxPath) throws IOException {
        try (InputStream in = Files.newInputStream(docxPath);
             XWPFDocument doc = new XWPFDocument(in)) {
            return read(doc);
        }
    }

    public static Result read(XWPFDocument doc) {
        List<String> lines = new ArrayList<>();
        List<List<List<String>>> tables = new ArrayList<>();
        List<String> tableSections = new ArrayList<>();

        String currentSection = "";

        for (IBodyElement el : doc.getBodyElements()) {
            if (el instanceof XWPFParagraph p) {
                String raw = p.getText();
                if (raw == null) continue;
                String stripped = raw.strip();
                if (stripped.isEmpty()) continue;
                lines.add(stripped);
                String allStripped = Normalizer.stripAllWs(stripped);
                if (allStripped.contains("최초계약")) {
                    currentSection = "최초계약";
                } else if (allStripped.contains("갱신계약")) {
                    currentSection = "갱신계약";
                }
            } else if (el instanceof XWPFTable t) {
                tables.add(buildRowsWithGridSpan(t));
                tableSections.add(currentSection);
            }
        }

        return new Result(lines, tables, tableSections);
    }

    /**
     * Build rows mirroring python-docx {@code row.cells}: gridSpan+vMerge
     * continuation cells are expanded into duplicated text copies of the
     * master cell. This matches the expansion in
     * {@code com.hanwha.setdata.docx.DocxReader#buildRowsWithGridSpan}.
     */
    private static List<List<String>> buildRowsWithGridSpan(XWPFTable table) {
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
        if (val == null) return VMergeState.CONTINUE;
        return val == STMerge.RESTART ? VMergeState.RESTART : VMergeState.CONTINUE;
    }

    private static int gridSpanOf(CTTc tc) {
        CTTcPr pr = tc.getTcPr();
        if (pr == null || pr.getGridSpan() == null) return 1;
        BigInteger val = pr.getGridSpan().getVal();
        return val == null ? 1 : Math.max(1, val.intValue());
    }

    private static String joinParagraphs(XWPFTableCell cell) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (XWPFParagraph p : cell.getParagraphs()) {
            if (!first) sb.append('\n');
            sb.append(p.getText() == null ? "" : p.getText());
            first = false;
        }
        return sb.toString();
    }
}
