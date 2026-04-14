package com.hanwha.setdata.store;

import com.hanwha.setdata.docx.DocxContent;
import com.hanwha.setdata.extract.PcDocx;
import com.hanwha.setdata.extract.cycle.PcCycleDocx;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts {@link ParsedDocument} into the view types expected by existing extractors.
 *
 * <p>Three views are supported:
 * <ul>
 *   <li>{@link #toDocxContent} — expanded cells + paragraph/cell lines (DocxReader semantics)</li>
 *   <li>{@link #toPcDocxResult} — null-view tables + fullText (PcDocx semantics)</li>
 *   <li>{@link #toCycleResult} — expanded cells + paragraph-only lines (PcCycleDocx semantics)</li>
 * </ul>
 */
public final class DocxStoreAdapters {

    private DocxStoreAdapters() {}

    /**
     * Convert to {@link DocxContent} (DocxReader semantics).
     *
     * <p>Lines = paragraph text + normalizeWs'd cell text in document order.
     * Tables = expanded cells (merged cells duplicated). Section tags per table.
     */
    public static DocxContent toDocxContent(ParsedDocument doc) {
        List<String> lines = new ArrayList<>();
        for (ParsedDocument.BodyElement el : doc.elements()) {
            if ("P".equals(el.kind()) || "C".equals(el.kind())) {
                if (el.text() != null && !el.text().isEmpty()) {
                    lines.add(el.text());
                }
            }
        }

        List<List<List<String>>> tables = new ArrayList<>();
        List<String> tableSections = new ArrayList<>();
        for (ParsedTable pt : doc.tables()) {
            tables.add(cellsToList(pt.cells()));
            tableSections.add(pt.section());
        }

        return new DocxContent(lines, tables, tableSections);
    }

    /**
     * Convert to {@link PcDocx.Result} (PcDocx semantics).
     *
     * <p>Tables use null-view: H/V merge continuation cells become {@code null}.
     * fullText = paragraphs + non-null table cell values joined with {@code \n}.
     */
    public static PcDocx.Result toPcDocxResult(ParsedDocument doc) {
        // Build null-view tables indexed by seq
        List<List<List<String>>> nullTables = new ArrayList<>();
        for (ParsedTable pt : doc.tables()) {
            nullTables.add(buildNullView(pt));
        }

        // Build fullText: iterate elements in document order
        List<String> orderedLines = new ArrayList<>();
        for (ParsedDocument.BodyElement el : doc.elements()) {
            if ("P".equals(el.kind())) {
                String text = el.text();
                if (text != null && !text.isEmpty()) {
                    orderedLines.add(text);
                }
            } else if ("T".equals(el.kind())) {
                int tableSeq = el.tableSeq();
                if (tableSeq >= 0 && tableSeq < nullTables.size()) {
                    List<List<String>> table = nullTables.get(tableSeq);
                    for (List<String> row : table) {
                        for (String cell : row) {
                            if (cell == null) continue;
                            String stripped = cell.strip();
                            if (!stripped.isEmpty()) {
                                orderedLines.add(stripped);
                            }
                        }
                    }
                }
            }
            // Skip 'C' elements — PcDocx builds fullText from null-view table cells
        }

        return new PcDocx.Result(String.join("\n", orderedLines), nullTables);
    }

    /**
     * Convert to {@link PcCycleDocx.Result} (PcCycleDocx semantics).
     *
     * <p>Lines = paragraph text only (no table cell text).
     * Tables = expanded cells (merged cells duplicated). Section tags per table.
     */
    public static PcCycleDocx.Result toCycleResult(ParsedDocument doc) {
        List<String> lines = new ArrayList<>();
        for (ParsedDocument.BodyElement el : doc.elements()) {
            if ("P".equals(el.kind())) {
                String text = el.text();
                if (text != null && !text.isEmpty()) {
                    lines.add(text);
                }
            }
        }

        List<List<List<String>>> tables = new ArrayList<>();
        List<String> tableSections = new ArrayList<>();
        for (ParsedTable pt : doc.tables()) {
            tables.add(cellsToList(pt.cells()));
            tableSections.add(pt.section());
        }

        return new PcCycleDocx.Result(lines, tables, tableSections);
    }

    // ── Helpers ─────────────────────────────────────────────────

    /** Convert String[][] to List<List<String>>. */
    private static List<List<String>> cellsToList(String[][] cells) {
        List<List<String>> result = new ArrayList<>(cells.length);
        for (String[] row : cells) {
            List<String> rowList = new ArrayList<>(row.length);
            for (String cell : row) {
                rowList.add(cell);
            }
            result.add(rowList);
        }
        return result;
    }

    /** Build null-view table: H/V continuation cells → null, N cells → stripped text. */
    private static List<List<String>> buildNullView(ParsedTable pt) {
        String[][] cells = pt.cells();
        char[][] merges = pt.merges();
        List<List<String>> result = new ArrayList<>(cells.length);
        for (int r = 0; r < cells.length; r++) {
            List<String> row = new ArrayList<>(cells[r].length);
            for (int c = 0; c < cells[r].length; c++) {
                char m = (r < merges.length && c < merges[r].length) ? merges[r][c] : 'N';
                if (m == 'H' || m == 'V') {
                    row.add(null);
                } else {
                    row.add(cells[r][c] != null ? cells[r][c].strip() : "");
                }
            }
            result.add(row);
        }
        return result;
    }
}
