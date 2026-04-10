package com.hanwha.setdata;

import com.hanwha.setdata.docx.DocxContent;
import com.hanwha.setdata.docx.DocxReader;

import java.nio.file.Path;

/**
 * Temporary PoC entry point: reads a single docx and prints a summary.
 * Will be replaced by {@code cli.PipelineCli} in Phase 4.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: setdata-extractor <docx-path>");
            System.exit(2);
        }
        Path docxPath = Path.of(args[0]);
        DocxContent content = new DocxReader().read(docxPath);

        System.out.println("file          : " + docxPath.getFileName());
        System.out.println("paragraph+cell lines : " + content.lines().size());
        System.out.println("tables        : " + content.tables().size());

        int firstIdx = -1, renewalIdx = -1;
        for (int i = 0; i < content.tableSections().size(); i++) {
            String s = content.tableSections().get(i);
            if (firstIdx < 0 && "최초계약".equals(s)) firstIdx = i;
            if (renewalIdx < 0 && "갱신계약".equals(s)) renewalIdx = i;
        }
        System.out.println("first 최초계약 table idx : " + firstIdx);
        System.out.println("first 갱신계약 table idx : " + renewalIdx);
        System.out.println("section labels (first 10): "
                + content.tableSections().subList(0, Math.min(10, content.tableSections().size())));
    }
}
