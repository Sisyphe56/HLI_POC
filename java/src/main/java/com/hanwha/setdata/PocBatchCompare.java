package com.hanwha.setdata;

import com.hanwha.setdata.docx.DocxContent;
import com.hanwha.setdata.docx.DocxReader;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Print one-line metrics for every docx in a directory (mirrors Python comparison format). */
public final class PocBatchCompare {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        DocxReader reader = new DocxReader();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.docx")) {
            for (Path p : ds) {
                if (p.getFileName().toString().startsWith("~$")) continue;
                DocxContent c = reader.read(p);
                StringBuilder sect = new StringBuilder();
                for (String s : c.tableSections()) {
                    sect.append("최초계약".equals(s) ? 'C' : "갱신계약".equals(s) ? 'G' : '.');
                }
                String name = p.getFileName().toString();
                if (name.length() > 40) name = name.substring(0, 40);
                System.out.printf("%-40s... | %d %d %s%n",
                        name, c.lines().size(), c.tables().size(), sect);
            }
        }
    }
}
