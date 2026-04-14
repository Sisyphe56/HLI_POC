package com.hanwha.setdata.store;

/**
 * Single table from a parsed docx document.
 *
 * @param seq     0-based table index within the document
 * @param section section heading active when the table appeared ("최초계약", "갱신계약", or "")
 * @param cells   expanded cells (merged cells duplicated, python-docx semantics)
 * @param merges  merge flags per cell: 'N' = normal, 'H' = gridSpan continuation, 'V' = vMerge continuation
 */
public record ParsedTable(
        int seq,
        String section,
        String[][] cells,
        char[][] merges
) {}
