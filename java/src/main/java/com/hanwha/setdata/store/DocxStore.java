package com.hanwha.setdata.store;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Interface for retrieving parsed docx documents, backed by a cache.
 */
public interface DocxStore {

    /**
     * Get parsed document. Returns from cache if hash matches, otherwise parses and stores.
     */
    ParsedDocument get(Path docxPath) throws IOException;

    /**
     * Force parse and store (or update) a single document.
     */
    void parseAndStore(Path docxPath) throws IOException;

    /**
     * Parse all .docx files in a directory. Only re-parses files whose hash has changed.
     */
    void parseAll(Path docxDir) throws IOException;
}
