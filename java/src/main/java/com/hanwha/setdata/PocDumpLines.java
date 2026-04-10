package com.hanwha.setdata;

import com.hanwha.setdata.docx.DocxContent;
import com.hanwha.setdata.docx.DocxReader;

import java.nio.file.Path;

/** Dump lines for comparison with Python output. */
public final class PocDumpLines {
    public static void main(String[] args) throws Exception {
        DocxContent c = new DocxReader().read(Path.of(args[0]));
        for (int i = 0; i < c.lines().size(); i++) {
            String s = c.lines().get(i);
            System.out.println(String.format("%3d: %s", i, s.substring(0, Math.min(80, s.length()))));
        }
    }
}
