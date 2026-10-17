package com.orqix.integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/** Deterministic plain-text templates. No scripts, uploaded Office packages, expressions or external resources. */
@Component
public final class DocumentGeneration {
    public static final int MAX_TEMPLATE_CHARS = 16_000;
    public static final int MAX_RENDERED_CHARS = 32_000;
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*)}}");
    public record Generated(String name, String contentType, byte[] bytes) {}

    public Generated generate(String format, String name, String template, JsonNode data) {
        if (!("pdf".equals(format) || "docx".equals(format))) throw invalid("Unsupported document format.");
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9 _.-]{0,99}") || name.contains(".."))
            throw invalid("Use a simple document name without a path.");
        String text = render(template, data);
        try {
            return new Generated(name + "." + format,
                    "pdf".equals(format) ? "application/pdf" : "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "pdf".equals(format) ? pdf(text) : docx(text));
        } catch (IOException e) { throw new IllegalStateException("Document generation failed.", e); }
    }

    /**
     * The distinct placeholders of a template, in order of first use. Applies the same syntax and character rules as
     * {@link #render}, so a registry template that is accepted here is accepted by the generator's compiler checks.
     */
    public static java.util.List<String> placeholders(String template) {
        if (template == null || template.isBlank() || template.length() > MAX_TEMPLATE_CHARS)
            throw invalid("A bounded template is required.");
        if (template.codePoints().anyMatch(c -> (c < 32 && c != '\n' && c != '\r' && c != '\t') || c == 0xFFFE || c == 0xFFFF))
            throw invalid("The template contains unsupported control characters.");
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        Matcher matcher = VARIABLE.matcher(template);
        int end = 0;
        while (matcher.find()) {
            String literal = template.substring(end, matcher.start());
            if (literal.contains("{{") || literal.contains("}}")) throw invalid("The template contains an invalid placeholder.");
            names.add(matcher.group(1));
            end = matcher.end();
        }
        String tail = template.substring(end);
        if (tail.contains("{{") || tail.contains("}}")) throw invalid("The template contains an invalid placeholder.");
        if (names.size() > 50) throw invalid("A template can use at most 50 placeholders.");
        return java.util.List.copyOf(names);
    }

    public String render(String template, JsonNode data) {
        if (template == null || template.isBlank() || template.length() > MAX_TEMPLATE_CHARS || data == null || !data.isObject())
            throw invalid("A bounded template and object input are required.");
        if (template.codePoints().anyMatch(c -> (c < 32 && c != '\n' && c != '\r' && c != '\t') || c == 0xFFFE || c == 0xFFFF))
            throw invalid("The template contains unsupported control characters.");
        StringBuilder result = new StringBuilder();
        Matcher matcher = VARIABLE.matcher(template);
        int end = 0;
        while (matcher.find()) {
            String literal = template.substring(end, matcher.start());
            if (literal.contains("{{") || literal.contains("}}")) throw invalid("The template contains an invalid placeholder.");
            result.append(literal);
            JsonNode value = data;
            for (String part : matcher.group(1).split("\\.")) value = value.path(part);
            if (value.isMissingNode() || value.isNull() || (value.isObject() || value.isArray())) throw invalid("A template field is missing or is not scalar.");
            String replacement = value.isString() ? value.asString() : value.toString();
            if (replacement.length() > 8_000 || replacement.codePoints().anyMatch(c -> (c < 32 && c != '\n' && c != '\r' && c != '\t') || c == 0xFFFE || c == 0xFFFF))
                throw invalid("A template field exceeds the supported text bounds.");
            result.append(replacement);
            if (result.length() > MAX_RENDERED_CHARS) throw invalid("The generated text exceeds its limit.");
            end = matcher.end();
        }
        String tail = template.substring(end);
        if (tail.contains("{{") || tail.contains("}}")) throw invalid("The template contains an invalid placeholder.");
        result.append(tail);
        if (result.length() > MAX_RENDERED_CHARS) throw invalid("The generated text exceeds its limit.");
        return result.toString().replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ');
    }

    private static byte[] docx(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            StringBuilder body = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
            for (String line : text.split("\n", -1)) body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(xml(line)).append("</w:t></w:r></w:p>");
            body.append("<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\"/></w:sectPr></w:body></w:document>");
            entry(zip, "word/document.xml", body.toString());
        }
        return bytes.toByteArray();
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    private static void entry(ZipOutputStream zip, String path, String text) throws IOException {
        ZipEntry entry = new ZipEntry(path); entry.setTime(0); zip.putNextEntry(entry);
        zip.write(text.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }

    private static byte[] pdf(String text) throws IOException {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        // Explicitly reject glyphs the embedded PDF font cannot represent; DOCX retains Unicode.
        try { for (String line : text.split("\n", -1)) font.getStringWidth(line); }
        catch (IllegalArgumentException e) { throw invalid("PDF text contains unsupported glyphs. Choose DOCX for Unicode text."); }
        try (PDDocument document = new PDDocument()) {
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (String paragraph : text.split("\n", -1)) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < paragraph.length(); i++) {
                    char c = paragraph.charAt(i);
                    if (font.getStringWidth(line.toString() + c) * 11 / 1000 > 504 && !line.isEmpty()) {
                        lines.add(line.toString()); line.setLength(0);
                    }
                    line.append(c);
                }
                lines.add(line.toString());
                if (lines.size() > 1_000) throw invalid("The PDF exceeds its page limit.");
            }
            for (int offset = 0; offset < lines.size(); offset += 45) {
                PDPage page = new PDPage(PDRectangle.LETTER); document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText(); content.setFont(font, 11); content.setLeading(15); content.newLineAtOffset(54, 738);
                    for (int index = offset; index < Math.min(lines.size(), offset + 45); index++) { content.showText(lines.get(index)); content.newLine(); }
                    content.endText();
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); document.save(bytes); return bytes.toByteArray();
        }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
