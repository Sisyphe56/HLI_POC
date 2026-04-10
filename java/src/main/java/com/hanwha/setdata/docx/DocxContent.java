package com.hanwha.setdata.docx;

import java.util.List;

/**
 * Result of {@link DocxReader#read(java.nio.file.Path)}.
 * Mirrors Python {@code extract_docx_content()} 3-tuple:
 * (lines, tables, tableSections).
 *
 * <ul>
 *   <li>{@code lines}: paragraphs and cell text concatenated in document order</li>
 *   <li>{@code tables}: each table as rows × cells of raw strings</li>
 *   <li>{@code tableSections}: section label ("최초계약"/"갱신계약"/"") for each table, parallel to {@code tables}</li>
 * </ul>
 */
public record DocxContent(
        List<String> lines,
        List<List<List<String>>> tables,
        List<String> tableSections
) {
}
