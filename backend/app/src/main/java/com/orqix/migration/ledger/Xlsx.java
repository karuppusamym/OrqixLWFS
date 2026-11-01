package com.orqix.migration.ledger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A minimal, dependency-free Office Open XML workbook writer: text cells only (inline strings), a bold header row and a
 * frozen first row per sheet. Every cell is a string, never a formula, so untrusted source labels cannot become
 * spreadsheet formulas. Entry order and timestamps are fixed, so the same rows produce the same bytes.
 */
public final class Xlsx {
    /** Fixed ZIP entry timestamp for reproducible bytes. */
    public static final java.time.LocalDateTime FIXED_TIME = java.time.LocalDateTime.of(2000, 1, 1, 0, 0);
    private final Map<String, List<List<String>>> sheets = new LinkedHashMap<>();

    /** Adds a sheet; the first row is the header. Sheet names are trimmed to Excel's 31 characters. */
    public Xlsx sheet(String name, List<List<String>> rows) {
        String clean = name.replaceAll("[\\[\\]:*?/\\\\]", " ");
        sheets.put(clean.length() > 31 ? clean.substring(0, 31) : clean, rows);
        return this;
    }

    public byte[] bytes() {
        if (sheets.isEmpty()) throw new IllegalStateException("A workbook needs at least one sheet.");
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            List<String> names = new ArrayList<>(sheets.keySet());
            StringBuilder types = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            for (int i = 1; i <= names.size(); i++) {
                types.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            }
            types.append("</Types>");
            put(zip, "[Content_Types].xml", types.toString());
            put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");
            StringBuilder workbook = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
            StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            for (int i = 1; i <= names.size(); i++) {
                workbook.append("<sheet name=\"").append(escape(names.get(i - 1))).append("\" sheetId=\"").append(i)
                        .append("\" r:id=\"rId").append(i).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(i)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                        .append(i).append(".xml\"/>");
            }
            workbook.append("</sheets></workbook>");
            int styles = names.size() + 1;
            rels.append("<Relationship Id=\"rId").append(styles)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
                    .append("</Relationships>");
            put(zip, "xl/workbook.xml", workbook.toString());
            put(zip, "xl/_rels/workbook.xml.rels", rels.toString());
            put(zip, "xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                    + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
                    + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                    + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                    + "<cellXfs count=\"3\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                    + "<xf numFmtId=\"49\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyAlignment=\"1\"><alignment wrapText=\"1\" vertical=\"top\"/></xf></cellXfs>"
                    + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                    + "</styleSheet>");
            for (int i = 1; i <= names.size(); i++) {
                put(zip, "xl/worksheets/sheet" + i + ".xml", sheetXml(sheets.get(names.get(i - 1))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static String sheetXml(List<List<String>> rows) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>"
                + "<sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            xml.append("<row r=\"").append(r + 1).append("\">");
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                String value = row.get(c);
                if (value == null || value.isEmpty()) continue;
                xml.append("<c r=\"").append(column(c)).append(r + 1).append("\" t=\"inlineStr\" s=\"").append(r == 0 ? 1 : 2)
                        .append("\"><is><t xml:space=\"preserve\">").append(escape(limit(value))).append("</t></is></c>");
            }
            xml.append("</row>");
        }
        return xml.append("</sheetData></worksheet>").toString();
    }

    /** Excel's per-cell text limit is 32,767 characters. */
    private static String limit(String value) {
        return value.length() > 32_000 ? value.substring(0, 32_000) + " [truncated]" : value;
    }

    /** Zero-based column index as letters: 0 is A, 26 is AA. */
    static String column(int index) {
        StringBuilder s = new StringBuilder();
        for (int n = index + 1; n > 0; n = (n - 1) / 26) s.insert(0, (char) ('A' + (n - 1) % 26));
        return s.toString();
    }

    /** XML text escaping that also drops characters XML 1.0 cannot carry. */
    static String escape(String value) {
        StringBuilder s = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> {
            switch (cp) {
                case '&' -> s.append("&amp;");
                case '<' -> s.append("&lt;");
                case '>' -> s.append("&gt;");
                case '"' -> s.append("&quot;");
                default -> {
                    if (cp == 0x9 || cp == 0xA || cp == 0xD || cp >= 0x20 && cp <= 0xD7FF || cp >= 0xE000 && cp <= 0xFFFD || cp >= 0x10000) s.appendCodePoint(cp);
                }
            }
        });
        return s.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        var entry = new ZipEntry(name);
        entry.setTimeLocal(FIXED_TIME);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
