package com.hanwha.setdata.store;

import java.util.List;

/**
 * Complete parsed representation of a single .docx file.
 *
 * @param filename NFC-normalized filename
 * @param fileHash SHA-256 hex digest of the file bytes
 * @param elements body elements in document order (paragraphs + table placeholders)
 * @param tables   parsed tables with expanded cells and merge metadata
 */
public record ParsedDocument(
        String filename,
        String fileHash,
        List<BodyElement> elements,
        List<ParsedTable> tables
) {
    /**
     * A single body element in document order.
     *
     * @param kind     "P" for paragraph, "T" for table placeholder
     * @param text     paragraph text (null for table placeholders)
     * @param section  active section heading ("최초계약", "갱신계약", or "")
     * @param tableSeq table seq number for kind="T", -1 for kind="P"
     */
    public record BodyElement(String kind, String text, String section, int tableSeq) {}
}
