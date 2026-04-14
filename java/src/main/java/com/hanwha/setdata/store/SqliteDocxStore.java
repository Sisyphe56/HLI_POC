package com.hanwha.setdata.store;

import com.hanwha.setdata.util.Normalizer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed {@link DocxStore} implementation.
 *
 * <p>Stores parsed docx content with SHA-256 hash-based invalidation.
 * Tables are stored as JSON 2D arrays for cells and merge flags.
 */
public final class SqliteDocxStore implements DocxStore {

    private Connection conn;

    public SqliteDocxStore(Path dbPath) throws IOException {
        Files.createDirectories(dbPath.getParent());
        try {
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            conn.setAutoCommit(false);
            initSchema();
        } catch (SQLException e) {
            throw new IOException("Failed to open SQLite DB: " + dbPath, e);
        }
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS documents (
                    id        INTEGER PRIMARY KEY,
                    filename  TEXT NOT NULL UNIQUE,
                    file_hash TEXT NOT NULL,
                    file_size INTEGER NOT NULL,
                    parsed_at TEXT NOT NULL
                )""");
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS body_elements (
                    doc_id    INTEGER NOT NULL REFERENCES documents(id),
                    seq       INTEGER NOT NULL,
                    kind      TEXT NOT NULL,
                    text      TEXT,
                    section   TEXT NOT NULL DEFAULT '',
                    table_seq INTEGER,
                    PRIMARY KEY(doc_id, seq)
                )""");
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS tables (
                    doc_id    INTEGER NOT NULL REFERENCES documents(id),
                    seq       INTEGER NOT NULL,
                    section   TEXT NOT NULL DEFAULT '',
                    row_count INTEGER NOT NULL,
                    col_count INTEGER NOT NULL,
                    cells     TEXT NOT NULL,
                    merges    TEXT NOT NULL,
                    PRIMARY KEY(doc_id, seq)
                )""");
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_doc_hash ON documents(file_hash)");
            conn.commit();
        }
    }

    @Override
    public ParsedDocument get(Path docxPath) throws IOException {
        String filename = Normalizer.nfc(docxPath.getFileName().toString());
        String currentHash = DocxParser.sha256(docxPath);

        try {
            // Check cache
            String storedHash = queryHash(filename);
            if (currentHash.equals(storedHash)) {
                ParsedDocument cached = load(filename);
                if (cached != null) return cached;
            }
            // Cache miss — parse and store
            ParsedDocument doc = DocxParser.parse(docxPath);
            store(doc, Files.size(docxPath));
            return doc;
        } catch (SQLException e) {
            throw new IOException("DB error for " + filename, e);
        }
    }

    @Override
    public void parseAndStore(Path docxPath) throws IOException {
        ParsedDocument doc = DocxParser.parse(docxPath);
        try {
            store(doc, Files.size(docxPath));
        } catch (SQLException e) {
            throw new IOException("DB error storing " + doc.filename(), e);
        }
    }

    @Override
    public void parseAll(Path docxDir) throws IOException {
        List<Path> docs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(docxDir, "*.docx")) {
            for (Path p : stream) {
                if (p.getFileName().toString().startsWith("~$")) continue;
                docs.add(p);
            }
        }
        int parsed = 0, cached = 0;
        for (Path p : docs) {
            String filename = Normalizer.nfc(p.getFileName().toString());
            String currentHash = DocxParser.sha256(p);
            try {
                String storedHash = queryHash(filename);
                if (currentHash.equals(storedHash)) {
                    cached++;
                    continue;
                }
            } catch (SQLException e) {
                // fall through to parse
            }
            parseAndStore(p);
            parsed++;
        }
        System.out.printf("[DocxStore] %d parsed, %d cached (%d total)%n", parsed, cached, docs.size());
    }

    // ── DB read ──────────────────────────────────────────────────

    private String queryHash(String filename) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT file_hash FROM documents WHERE filename = ?")) {
            ps.setString(1, filename);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private ParsedDocument load(String filename) throws SQLException {
        long docId;
        String fileHash;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, file_hash FROM documents WHERE filename = ?")) {
            ps.setString(1, filename);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            docId = rs.getLong(1);
            fileHash = rs.getString(2);
        }

        // Load body elements
        List<ParsedDocument.BodyElement> elements = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT kind, text, section, table_seq FROM body_elements WHERE doc_id = ? ORDER BY seq")) {
            ps.setLong(1, docId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String kind = rs.getString(1);
                String text = rs.getString(2);
                String section = rs.getString(3);
                int tableSeq = rs.getInt(4);
                if (rs.wasNull()) tableSeq = -1;
                elements.add(new ParsedDocument.BodyElement(kind, text, section, tableSeq));
            }
        }

        // Load tables
        List<ParsedTable> tables = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT seq, section, row_count, col_count, cells, merges FROM tables WHERE doc_id = ? ORDER BY seq")) {
            ps.setLong(1, docId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                int seq = rs.getInt(1);
                String section = rs.getString(2);
                int rowCount = rs.getInt(3);
                int colCount = rs.getInt(4);
                String cellsJson = rs.getString(5);
                String mergesJson = rs.getString(6);
                String[][] cells = deserializeCells(cellsJson, rowCount, colCount);
                char[][] merges = deserializeMerges(mergesJson, rowCount, colCount);
                tables.add(new ParsedTable(seq, section, cells, merges));
            }
        }

        return new ParsedDocument(filename, fileHash, elements, tables);
    }

    // ── DB write ─────────────────────────────────────────────────

    private void store(ParsedDocument doc, long fileSize) throws SQLException {
        // Delete existing
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM documents WHERE filename = ?")) {
            ps.setString(1, doc.filename());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                long oldId = rs.getLong(1);
                try (Statement s = conn.createStatement()) {
                    s.executeUpdate("DELETE FROM body_elements WHERE doc_id = " + oldId);
                    s.executeUpdate("DELETE FROM tables WHERE doc_id = " + oldId);
                    s.executeUpdate("DELETE FROM documents WHERE id = " + oldId);
                }
            }
        }

        // Insert document
        long docId;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO documents (filename, file_hash, file_size, parsed_at) VALUES (?, ?, ?, datetime('now'))")) {
            ps.setString(1, doc.filename());
            ps.setString(2, doc.fileHash());
            ps.setLong(3, fileSize);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            docId = keys.getLong(1);
        }

        // Insert body elements
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO body_elements (doc_id, seq, kind, text, section, table_seq) VALUES (?, ?, ?, ?, ?, ?)")) {
            int seq = 0;
            for (ParsedDocument.BodyElement el : doc.elements()) {
                ps.setLong(1, docId);
                ps.setInt(2, seq++);
                ps.setString(3, el.kind());
                ps.setString(4, el.text());
                ps.setString(5, el.section());
                if (el.tableSeq() >= 0) {
                    ps.setInt(6, el.tableSeq());
                } else {
                    ps.setNull(6, java.sql.Types.INTEGER);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }

        // Insert tables
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tables (doc_id, seq, section, row_count, col_count, cells, merges) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (ParsedTable t : doc.tables()) {
                int colCount = t.cells().length > 0 ? maxCols(t.cells()) : 0;
                ps.setLong(1, docId);
                ps.setInt(2, t.seq());
                ps.setString(3, t.section());
                ps.setInt(4, t.cells().length);
                ps.setInt(5, colCount);
                ps.setString(6, serializeCells(t.cells()));
                ps.setString(7, serializeMerges(t.merges()));
                ps.addBatch();
            }
            ps.executeBatch();
        }

        conn.commit();
    }

    // ── JSON serialization (minimal, no external dep) ────────────

    private static String serializeCells(String[][] cells) {
        StringBuilder sb = new StringBuilder("[");
        for (int ri = 0; ri < cells.length; ri++) {
            if (ri > 0) sb.append(',');
            sb.append('[');
            for (int ci = 0; ci < cells[ri].length; ci++) {
                if (ci > 0) sb.append(',');
                sb.append(jsonString(cells[ri][ci]));
            }
            sb.append(']');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String serializeMerges(char[][] merges) {
        StringBuilder sb = new StringBuilder("[");
        for (int ri = 0; ri < merges.length; ri++) {
            if (ri > 0) sb.append(',');
            sb.append('"');
            sb.append(new String(merges[ri]));
            sb.append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String[][] deserializeCells(String json, int rowCount, int colCount) {
        // Minimal JSON 2D array parser
        List<List<String>> rows = new ArrayList<>();
        int i = skipWs(json, 0);
        if (json.charAt(i) != '[') throw new IllegalArgumentException("Expected [");
        i++;
        while (i < json.length()) {
            i = skipWs(json, i);
            if (json.charAt(i) == ']') break;
            if (json.charAt(i) == ',') { i++; continue; }
            if (json.charAt(i) == '[') {
                i++;
                List<String> row = new ArrayList<>();
                while (i < json.length()) {
                    i = skipWs(json, i);
                    if (json.charAt(i) == ']') { i++; break; }
                    if (json.charAt(i) == ',') { i++; continue; }
                    if (json.charAt(i) == 'n' && json.startsWith("null", i)) {
                        row.add(null);
                        i += 4;
                    } else {
                        int[] pos = {i};
                        row.add(parseJsonStringValue(json, pos));
                        i = pos[0];
                    }
                }
                rows.add(row);
            }
        }
        String[][] cells = new String[rows.size()][];
        for (int r = 0; r < rows.size(); r++) {
            cells[r] = rows.get(r).toArray(String[]::new);
        }
        return cells;
    }

    private static char[][] deserializeMerges(String json, int rowCount, int colCount) {
        // Format: ["NNH","NVN",...]
        List<char[]> rows = new ArrayList<>();
        int i = skipWs(json, 0);
        if (json.charAt(i) != '[') throw new IllegalArgumentException("Expected [");
        i++;
        while (i < json.length()) {
            i = skipWs(json, i);
            if (json.charAt(i) == ']') break;
            if (json.charAt(i) == ',') { i++; continue; }
            if (json.charAt(i) == '"') {
                int end = json.indexOf('"', i + 1);
                rows.add(json.substring(i + 1, end).toCharArray());
                i = end + 1;
            }
        }
        return rows.toArray(char[][]::new);
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static String parseJsonStringValue(String json, int[] posHolder) {
        int i = posHolder[0];
        if (json.charAt(i) != '"') throw new IllegalArgumentException("Expected \" at " + i);
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') {
                posHolder[0] = i + 1;
                return sb.toString();
            }
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        String hex = json.substring(i + 2, i + 6);
                        sb.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                    }
                    default -> { sb.append('\\'); sb.append(next); }
                }
                i += 2;
                continue;
            }
            sb.append(c);
            i++;
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static int maxCols(String[][] cells) {
        int max = 0;
        for (String[] row : cells) if (row.length > max) max = row.length;
        return max;
    }

    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
    }
}
