package com.hanwha.setdata.extract;

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
import java.util.List;

/**
 * Classification-specific docx reader.
 *
 * <p>Ports Python {@code extract_docx_with_meta()} and {@code _docx_table_to_list()}
 * from {@code extract_product_classification_v2.py} (lines 1388–1446).
 *
 * <p>Unlike {@link com.hanwha.setdata.docx.DocxReader} which <em>expands</em>
 * merged cells into duplicated text (matching python-docx {@code row.cells}),
 * this reader produces pdfplumber-style tables where vMerge/gridSpan
 * continuation cells are {@code null}. This is what
 * {@code extract_product_classification_v2._docx_table_to_list()} does.
 *
 * <p>{@link #read(Path)} returns a {@link Result} containing {@code fullText}
 * (paragraphs + non-null table cell values joined with {@code \n}) and
 * {@code tables} (list of tables as {@code List<List<String>>} with nulls).
 */
public final class PcDocx {

    public record Result(String fullText, List<List<List<String>>> tables) {}

    public static Result read(Path docxPath) throws IOException {
        try (InputStream in = Files.newInputStream(docxPath);
             XWPFDocument doc = new XWPFDocument(in)) {
            return read(doc);
        }
    }

    public static Result read(XWPFDocument doc) {
        List<String> orderedLines = new ArrayList<>();
        List<List<List<String>>> tables = new ArrayList<>();

        for (IBodyElement el : doc.getBodyElements()) {
            if (el instanceof XWPFParagraph p) {
                String text = p.getText() == null ? "" : p.getText().strip();
                if (!text.isEmpty()) orderedLines.add(text);
            } else if (el instanceof XWPFTable t) {
                List<List<String>> table = docxTableToList(t);
                tables.add(table);
                for (List<String> row : table) {
                    for (String cell : row) {
                        if (cell == null) continue;
                        String stripped = cell.strip();
                        if (!stripped.isEmpty()) orderedLines.add(stripped);
                    }
                }
            }
        }

        return new Result(String.join("\n", orderedLines), tables);
    }

    /** Python: _docx_table_to_list — vMerge/gridSpan continuation cells become null. */
    public static List<List<String>> docxTableToList(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (CTTc tc : row.getCtRow().getTcList()) {
                CTTcPr pr = tc.getTcPr();
                int span = 1;
                if (pr != null && pr.getGridSpan() != null) {
                    BigInteger val = pr.getGridSpan().getVal();
                    if (val != null) span = Math.max(1, val.intValue());
                }
                // vMerge continuation → null * span
                if (pr != null && pr.getVMerge() != null) {
                    STMerge.Enum val = pr.getVMerge().getVal();
                    if (val == null) {
                        for (int i = 0; i < span; i++) cells.add(null);
                        continue;
                    }
                    // val present (RESTART or CONTINUE-with-val): fall through as normal cell
                }
                // Regular cell or vMerge RESTART: first slot gets text, rest null
                XWPFTableCell cell = findCell(row, tc);
                String text = cell == null ? "" : joinParagraphs(cell).strip();
                cells.add(text);
                for (int i = 1; i < span; i++) cells.add(null);
            }
            rows.add(cells);
        }
        return rows;
    }

    private static XWPFTableCell findCell(XWPFTableRow row, CTTc tc) {
        for (XWPFTableCell c : row.getTableCells()) {
            if (c.getCTTc() == tc) return c;
        }
        return null;
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

    private PcDocx() {}
}
