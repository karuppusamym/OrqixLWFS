package com.orqix.runtime.evidencepack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.node.ObjectNode;

/**
 * Renders an evidence pack as PDF, DOCX or JSON. Like the document step generator (integration DocumentGeneration),
 * output is built from fixed code only: no templates, scripts or external resources. PDF uses the standard Helvetica
 * font, so characters outside its Latin set are shown as '?'; DOCX and JSON keep full Unicode.
 */
@Component
public class EvidenceRenderer {

    public static final Set<String> FORMATS = Set.of("pdf", "docx", "json");

    /** A rendered file. */
    public record Rendered(String fileName, String contentType, byte[] bytes) {
    }

    private final Json json;

    public EvidenceRenderer(Json json) {
        this.json = json;
    }

    public static String requireFormat(String format) {
        if (format == null || !FORMATS.contains(format)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose pdf, docx or json.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/format", "enum", "Choose pdf, docx or json.")));
        }
        return format;
    }

    public Rendered render(String format, String baseName, ObjectNode pack, EvidenceDocument document) {
        String name = baseName.replaceAll("[^A-Za-z0-9._-]", "-") + "." + format;
        try {
            return switch (format) {
                case "json" -> new Rendered(name, "application/json",
                        (json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(pack) + "\n")
                                .getBytes(StandardCharsets.UTF_8));
                case "docx" -> new Rendered(name,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx(document));
                case "pdf" -> new Rendered(name, "application/pdf", pdf(document));
                default -> throw new IllegalArgumentException(format);
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ PDF

    private static final PDRectangle PAGE = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private static final float MARGIN = 40;
    private static final float WIDTH = PAGE.getWidth() - 2 * MARGIN;
    private static final float BOTTOM = 50;

    static byte[] pdf(EvidenceDocument document) throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle(document.title());
            info.setCreator("Orqix control evidence pack");
            pdf.setDocumentInformation(info);
            try (PdfWriter writer = new PdfWriter(pdf)) {
                writer.paragraph(document.title(), writer.bold, 18, 6);
                if (document.subtitle() != null) {
                    writer.paragraph(document.subtitle(), writer.regular, 10, 10);
                }
                for (EvidenceDocument.Section section : document.sections()) {
                    writer.section(section);
                }
                if (document.integrity() != null) {
                    writer.space(8);
                    writer.paragraph("Integrity", writer.bold, 12, 4);
                    writer.paragraph(document.integrity(), writer.regular, 8, 4);
                }
            }
            int total = pdf.getNumberOfPages();
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < total; i++) {
                PDPage page = pdf.getPage(i);
                try (PDPageContentStream content = new PDPageContentStream(pdf, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    content.beginText();
                    content.setFont(font, 8);
                    content.newLineAtOffset(MARGIN, 25);
                    content.showText(PdfWriter.safe(font, truncate(document.title(), 120) + "  -  page " + (i + 1)
                            + " of " + total));
                    content.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    /** Minimal flowing layout: wrapped paragraphs, label/value rows and tables with repeated headers. */
    private static final class PdfWriter implements AutoCloseable {
        final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private final PDDocument pdf;
        private PDPageContentStream content;
        private float y;

        PdfWriter(PDDocument pdf) throws IOException {
            this.pdf = pdf;
            newPage();
        }

        private void newPage() throws IOException {
            if (content != null) {
                content.close();
            }
            PDPage page = new PDPage(PAGE);
            pdf.addPage(page);
            content = new PDPageContentStream(pdf, page);
            y = PAGE.getHeight() - MARGIN;
        }

        private void ensure(float height) throws IOException {
            if (y - height < BOTTOM) {
                newPage();
            }
        }

        void space(float height) {
            y -= height;
        }

        void paragraph(String text, PDType1Font font, float size, float after) throws IOException {
            float leading = size * 1.3f;
            for (String line : wrap(font, size, safe(font, text), WIDTH)) {
                ensure(leading);
                write(font, size, MARGIN, y - size, line);
                y -= leading;
            }
            y -= after;
        }

        void section(EvidenceDocument.Section section) throws IOException {
            ensure(60);
            y -= 8;
            paragraph(section.heading(), bold, 13, 3);
            if (section.note() != null) {
                paragraph(section.note(), regular, 8.5f, 4);
            }
            if (!section.isTable()) {
                for (EvidenceDocument.Field field : section.fields()) {
                    row(List.of(field.label(), field.value()), new float[] {170, WIDTH - 170}, false, 9);
                }
                return;
            }
            if (section.rows().isEmpty()) {
                paragraph(section.emptyText() == null ? "None recorded." : section.emptyText(), regular, 9, 2);
                return;
            }
            float[] widths = widths(section);
            row(section.columns(), widths, true, 8.5f);
            for (List<String> cells : section.rows()) {
                if (y - measure(cells, widths, false, 8.5f) < BOTTOM) {
                    // The row starts a new page: repeat the header above it.
                    newPage();
                    row(section.columns(), widths, true, 8.5f);
                }
                row(cells, widths, false, 8.5f);
            }
        }

        /**
         * Column widths: each column first gets room for its longest word (capped, so hashes still wrap), then the
         * remaining width is shared by typical content length. Words are only broken when even that does not fit.
         */
        private float[] widths(EvidenceDocument.Section section) {
            int n = section.columns().size();
            float size = 8.5f;
            float[] minimum = new float[n];
            float[] weight = new float[n];
            for (int c = 0; c < n; c++) {
                minimum[c] = longestWord(bold, size, section.columns().get(c));
                weight[c] = Math.max(4, section.columns().get(c).length());
            }
            for (List<String> row : section.rows()) {
                for (int c = 0; c < n; c++) {
                    minimum[c] = Math.max(minimum[c], longestWord(regular, size, row.get(c)));
                    weight[c] = Math.max(weight[c], Math.min(60, row.get(c).length()));
                }
            }
            float floor = 0;
            float sum = 0;
            for (int c = 0; c < n; c++) {
                minimum[c] = Math.min(120, minimum[c] + 8);
                floor += minimum[c];
                sum += weight[c];
            }
            float[] widths = new float[n];
            if (floor >= WIDTH) {
                for (int c = 0; c < n; c++) {
                    widths[c] = minimum[c] * WIDTH / floor;
                }
                return widths;
            }
            float spare = WIDTH - floor;
            for (int c = 0; c < n; c++) {
                widths[c] = minimum[c] + spare * weight[c] / sum;
            }
            return widths;
        }

        private static float longestWord(PDType1Font font, float size, String text) {
            float longest = 0;
            for (String word : safe(font, text).split(" ")) {
                longest = Math.max(longest, width(font, size, word));
            }
            return longest;
        }

        private PDType1Font cellFont(boolean header, float[] widths, int column) {
            return header || (widths.length == 2 && column == 0) ? bold : regular;
        }

        private List<List<String>> cellLines(List<String> cells, float[] widths, boolean header, float size) {
            List<List<String>> lines = new ArrayList<>();
            for (int c = 0; c < cells.size(); c++) {
                PDType1Font font = cellFont(header, widths, c);
                lines.add(wrap(font, size, safe(font, cells.get(c)), widths[c] - 6));
            }
            return lines;
        }

        private float measure(List<String> cells, float[] widths, boolean header, float size) {
            int height = 1;
            for (List<String> lines : cellLines(cells, widths, header, size)) {
                height = Math.max(height, lines.size());
            }
            return height * size * 1.25f + 4;
        }

        /** Draws one row; a row taller than the space left starts a new page. */
        private void row(List<String> cells, float[] widths, boolean header, float size) throws IOException {
            float leading = size * 1.25f;
            List<List<String>> lines = cellLines(cells, widths, header, size);
            float rowHeight = measure(cells, widths, header, size);
            ensure(rowHeight);
            if (header) {
                content.setNonStrokingColor(0.9f, 0.9f, 0.9f);
                content.addRect(MARGIN, y - rowHeight, WIDTH, rowHeight);
                content.fill();
                content.setNonStrokingColor(0f, 0f, 0f);
            }
            float x = MARGIN;
            for (int c = 0; c < cells.size(); c++) {
                PDType1Font cellFont = cellFont(header, widths, c);
                float lineY = y - 2 - size;
                for (String line : lines.get(c)) {
                    write(cellFont, size, x + 3, lineY, line);
                    lineY -= leading;
                }
                x += widths[c];
            }
            content.setStrokingColor(0.75f, 0.75f, 0.75f);
            content.setLineWidth(0.4f);
            content.moveTo(MARGIN, y - rowHeight);
            content.lineTo(MARGIN + WIDTH, y - rowHeight);
            content.stroke();
            y -= rowHeight;
        }

        private void write(PDType1Font font, float size, float x, float baseline, String text) throws IOException {
            content.beginText();
            content.setFont(font, size);
            content.newLineAtOffset(x, baseline);
            content.showText(text);
            content.endText();
        }

        /** Whether the standard Latin font can encode a character (same set for regular and bold). */
        private static final Map<Character, Boolean> SUPPORTED = new java.util.concurrent.ConcurrentHashMap<>();

        /** Replaces characters the standard font cannot encode. */
        static String safe(PDType1Font font, String text) {
            StringBuilder out = new StringBuilder(text.length());
            for (char c : text.toCharArray()) {
                if (c == '\n' || c == '\r' || c == '\t') {
                    out.append(' ');
                    continue;
                }
                boolean ok = SUPPORTED.computeIfAbsent(c, ch -> {
                    try {
                        font.getStringWidth(String.valueOf(ch));
                        return true;
                    } catch (IOException | IllegalArgumentException e) {
                        return false;
                    }
                });
                out.append(ok ? c : '?');
            }
            return out.toString();
        }

        static float width(PDType1Font font, float size, String text) {
            try {
                return font.getStringWidth(text) / 1000 * size;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        static List<String> wrap(PDType1Font font, float size, String text, float max) {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (width(font, size, candidate) <= max) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                // A single word wider than the column is broken by characters (hashes, ids).
                for (char c : word.toCharArray()) {
                    if (width(font, size, line.toString() + c) > max && !line.isEmpty()) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    line.append(c);
                }
            }
            lines.add(line.toString());
            return lines;
        }

        @Override
        public void close() throws IOException {
            content.close();
        }
    }

    // ------------------------------------------------------------------ DOCX

    static byte[] docx(EvidenceDocument document) throws IOException {
        StringBuilder body = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
        paragraph(body, document.title(), true, 36, "1F3864", 120);
        if (document.subtitle() != null) {
            paragraph(body, document.subtitle(), false, 18, "595959", 200);
        }
        for (EvidenceDocument.Section section : document.sections()) {
            paragraph(body, section.heading(), true, 28, "1F3864", 80);
            if (section.note() != null) {
                paragraph(body, section.note(), false, 17, "595959", 80);
            }
            if (!section.isTable()) {
                List<List<String>> rows = new ArrayList<>();
                section.fields().forEach(f -> rows.add(List.of(f.label(), f.value())));
                table(body, null, rows, true);
            } else if (section.rows().isEmpty()) {
                paragraph(body, section.emptyText() == null ? "None recorded." : section.emptyText(), false, 18, null, 80);
            } else {
                table(body, section.columns(), section.rows(), false);
            }
            paragraph(body, "", false, 10, null, 0);
        }
        if (document.integrity() != null) {
            paragraph(body, "Integrity", true, 24, "1F3864", 60);
            paragraph(body, document.integrity(), false, 16, null, 60);
        }
        body.append("<w:sectPr><w:pgSz w:w=\"16838\" w:h=\"11906\" w:orient=\"landscape\"/>"
                + "<w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\" w:header=\"360\" w:footer=\"360\" w:gutter=\"0\"/>"
                + "</w:sectPr></w:body></w:document>");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                    + "</Types>");
            entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                    + "</Relationships>");
            entry(zip, "docProps/core.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                    + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>" + xml(document.title())
                    + "</dc:title><dc:creator>Orqix control evidence pack</dc:creator></cp:coreProperties>");
            entry(zip, "word/document.xml", body.toString());
        }
        return bytes.toByteArray();
    }

    private static void paragraph(StringBuilder out, String text, boolean bold, int halfPoints, String color, int after) {
        out.append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"").append(after).append("\"/></w:pPr><w:r><w:rPr>");
        if (bold) {
            out.append("<w:b/>");
        }
        if (color != null) {
            out.append("<w:color w:val=\"").append(color).append("\"/>");
        }
        out.append("<w:sz w:val=\"").append(halfPoints).append("\"/></w:rPr><w:t xml:space=\"preserve\">")
                .append(xml(text)).append("</w:t></w:r></w:p>");
    }

    private static void table(StringBuilder out, List<String> header, List<List<String>> rows, boolean labelColumn) {
        out.append("<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>");
        for (String side : List.of("top", "left", "bottom", "right", "insideH", "insideV")) {
            out.append("<w:").append(side).append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBFBF\"/>");
        }
        out.append("</w:tblBorders><w:tblCellMar><w:left w:w=\"80\" w:type=\"dxa\"/><w:right w:w=\"80\" w:type=\"dxa\"/>"
                + "</w:tblCellMar></w:tblPr>");
        if (header != null) {
            out.append("<w:tr><w:trPr><w:tblHeader/></w:trPr>");
            header.forEach(h -> cell(out, h, true, "E7E6E6"));
            out.append("</w:tr>");
        }
        for (List<String> row : rows) {
            out.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>");
            for (int c = 0; c < row.size(); c++) {
                cell(out, row.get(c), labelColumn && c == 0, labelColumn && c == 0 ? "F2F2F2" : null);
            }
            out.append("</w:tr>");
        }
        out.append("</w:tbl>");
    }

    private static void cell(StringBuilder out, String text, boolean bold, String fill) {
        out.append("<w:tc><w:tcPr>");
        if (fill != null) {
            out.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(fill).append("\"/>");
        }
        out.append("</w:tcPr><w:p><w:pPr><w:spacing w:before=\"20\" w:after=\"20\"/></w:pPr><w:r><w:rPr>");
        if (bold) {
            out.append("<w:b/>");
        }
        out.append("<w:sz w:val=\"17\"/></w:rPr><w:t xml:space=\"preserve\">").append(xml(text))
                .append("</w:t></w:r></w:p></w:tc>");
    }

    /** XML-escapes text and drops characters XML 1.0 cannot carry. */
    static String xml(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    if (c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD)
                            || c >= 0x10000) {
                        out.appendCodePoint(c == 0x9 || c == 0xA || c == 0xD ? ' ' : c);
                    }
                }
            }
        });
        return out.toString();
    }

    private static void entry(ZipOutputStream zip, String path, String text) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
