package com.orqix.integration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/** Parser-only entry point. Never starts Spring, executes macros/formulas, resolves links, or renders document code. */
public final class DocumentExtractionWorker {
    static final int MAX_EXPANDED_BYTES = 20 * 1024 * 1024;
    static final int MAX_ENTRIES = 256;
    static final int MAX_PAGES = 100;
    /** Worker type for blueprint PDFs: one text block per page, separated by form feeds, after a header line. */
    static final String PDF_PAGES = "pdfpages";
    static final String PAGES_HEADER = "ORQIX-PDF-PAGES/1 ";
    private DocumentExtractionWorker() { }

    public static void main(String[] args) {
        int exit = 2;
        try {
            if (args.length != 3) { System.exit(2); }
            Path source = Path.of(args[1]), output = Path.of(args[2]);
            boolean pages = PDF_PAGES.equals(args[0]);
            if (Files.size(source) > (pages ? DocumentExtraction.BLUEPRINT_MAX_FILE_BYTES : DocumentExtraction.MAX_FILE_BYTES)) { throw new Limit(); }
            String text = pages ? pdfPages(Files.readAllBytes(source)) : extract(Files.readAllBytes(source), args[0]);
            if (!pages && text.isBlank()) { System.exit(5); }
            Files.writeString(output, text, StandardCharsets.UTF_8);
            return;
        } catch (Limit e) { exit = 3; }
        catch (InvalidPasswordException | Encrypted e) { exit = 4; }
        catch (Exception | OutOfMemoryError e) { exit = 2; }
        System.exit(exit);
    }

    static String type(String mime) {
        return switch (mime.toLowerCase(Locale.ROOT).split(";", 2)[0].trim()) {
            case "application/pdf" -> "pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";
            default -> throw new MicrosoftGraphDocuments.Failure("DOCUMENT_CONTENT_TYPE_UNSUPPORTED", null, HttpExecutor.Observation.CLIENT_ERROR);
        };
    }

    static String extract(byte[] bytes, String type) throws Exception {
        BoundedWriter text = new BoundedWriter();
        if (type.equals("pdf")) {
            if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) { throw new IOException(); }
            try (var document = Loader.loadPDF(bytes)) {
                if (document.isEncrypted() || !document.getCurrentAccessPermission().canExtractContent()) { throw new Encrypted(); }
                if (document.getNumberOfPages() > MAX_PAGES) { throw new Limit(); }
                new PDFTextStripper().writeText(document, text);
            }
        } else {
            Map<String, byte[]> parts = parts(bytes);
            byte[] contentTypes = parts.get("[Content_Types].xml");
            if (contentTypes == null) { throw new IOException(); }
            contentTypes(contentTypes);
            if (type.equals("docx")) {
                if (!parts.containsKey("word/document.xml")) { throw new IOException(); }
                xmlText(parts.get("word/document.xml"), text, false);
            } else if (type.equals("pptx")) {
                var slides = parts.keySet().stream().filter(k -> k.matches("ppt/slides/slide[0-9]+\\.xml"))
                        .sorted(java.util.Comparator.comparingInt(k -> Integer.parseInt(k.replaceAll("[^0-9]", "")))).toList();
                if (slides.isEmpty() || slides.size() > MAX_PAGES) { throw new Limit(); }
                for (String slide : slides) { xmlText(parts.get(slide), text, false); text.append("\n"); }
            } else if (type.equals("xlsx")) {
                List<String> strings = sharedStrings(parts.get("xl/sharedStrings.xml"));
                var sheets = parts.keySet().stream().filter(k -> k.matches("xl/worksheets/sheet[0-9]+\\.xml"))
                        .sorted(java.util.Comparator.comparingInt(k -> Integer.parseInt(k.replaceAll("[^0-9]", "")))).toList();
                if (sheets.isEmpty() || sheets.size() > MAX_PAGES) { throw new Limit(); }
                for (String sheet : sheets) { sheet(parts.get(sheet), strings, text); }
            } else { throw new IOException(); }
        }
        return text.toString();
    }

    /**
     * Blueprint PDFs: text only, page by page. JavaScript, open actions, forms and embedded files are never executed or
     * extracted; their presence is only reported so the review can say they were ignored. Control characters other than
     * line breaks and tabs are removed so a page cannot forge the page separator.
     */
    static String pdfPages(byte[] bytes) throws Exception {
        if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) { throw new IOException(); }
        BoundedWriter text = new BoundedWriter(DocumentExtraction.BLUEPRINT_MAX_TEXT_BYTES);
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted() || !document.getCurrentAccessPermission().canExtractContent()) { throw new Encrypted(); }
            int count = document.getNumberOfPages();
            if (count > DocumentExtraction.BLUEPRINT_MAX_PAGES) { throw new Limit(); }
            if (count < 1) { throw new IOException(); }
            var catalog = document.getDocumentCatalog();
            var names = catalog.getNames();
            boolean javascript = (names != null && names.getJavaScript() != null)
                    || catalog.getCOSObject().containsKey(org.apache.pdfbox.cos.COSName.AA)
                    || catalog.getCOSObject().getDictionaryObject(org.apache.pdfbox.cos.COSName.OPEN_ACTION) instanceof org.apache.pdfbox.cos.COSDictionary;
            boolean embedded = names != null && names.getEmbeddedFiles() != null;
            for (var page : document.getPages()) {
                for (var annotation : page.getAnnotations()) {
                    if (annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment) { embedded = true; }
                    if (annotation.getCOSObject().containsKey(org.apache.pdfbox.cos.COSName.A)
                            || annotation.getCOSObject().containsKey(org.apache.pdfbox.cos.COSName.AA)) { javascript = true; }
                }
                if (page.getCOSObject().containsKey(org.apache.pdfbox.cos.COSName.AA)) { javascript = true; }
            }
            text.append(PAGES_HEADER).append(Integer.toString(count)).append(" javascript=").append(Boolean.toString(javascript))
                    .append(" embedded=").append(Boolean.toString(embedded)).append("\n");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= count; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                text.append(stripper.getText(document).replaceAll("[\\p{Cc}&&[^\\n\\t]]", "")).append("\f");
            }
        }
        return text.toString();
    }

    private static Map<String, byte[]> parts(byte[] bytes) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        int expanded = 0, count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                if (++count > MAX_ENTRIES || name.startsWith("/") || name.contains("..") || name.contains("\\")
                        || name.toLowerCase(Locale.ROOT).contains("vbaproject") || name.contains("/embeddings/")) { throw new Limit(); }
                byte[] part = zip.readNBytes(MAX_EXPANDED_BYTES - expanded + 1);
                expanded += part.length;
                if (expanded > MAX_EXPANDED_BYTES || (entry.getCompressedSize() > 0 && part.length > 1024 * 1024
                        && part.length / (double) entry.getCompressedSize() > 100)) { throw new Limit(); }
                if (parts.put(name, part) != null) { throw new IOException(); }
            }
        }
        return parts;
    }

    private static XMLStreamReader reader(byte[] bytes) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> { throw new javax.xml.stream.XMLStreamException("External XML is forbidden"); });
        return factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
    }

    private static void contentTypes(byte[] bytes) throws Exception {
        XMLStreamReader reader = reader(bytes);
        int events = 0, depth = 0;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (++events > 10000 || event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) { throw new Limit(); }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (++depth > 16) { throw new Limit(); }
                    for (int i = 0; i < reader.getAttributeCount(); i++) {
                        if (reader.getAttributeValue(i).toLowerCase(Locale.ROOT).contains("macroenabled")) { throw new IOException(); }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) { depth--; }
            }
        } finally { reader.close(); }
    }

    private static void xmlText(byte[] bytes, BoundedWriter output, boolean shared) throws Exception {
        XMLStreamReader reader = reader(bytes);
        int depth = 0, events = 0;
        boolean text = false;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (++events > 1_000_000) { throw new Limit(); }
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) { throw new IOException(); }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (++depth > 64) { throw new Limit(); }
                    text = reader.getLocalName().equals("t");
                } else if (event == XMLStreamConstants.CHARACTERS && text) { output.append(reader.getText()); }
                else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (reader.getLocalName().equals("p")) { output.append("\n"); }
                    text = false; depth--;
                }
            }
        } finally { reader.close(); }
    }

    private static List<String> sharedStrings(byte[] bytes) throws Exception {
        if (bytes == null) { return List.of(); }
        List<String> strings = new ArrayList<>();
        XMLStreamReader reader = reader(bytes);
        BoundedWriter current = null;
        boolean text = false;
        int events = 0, depth = 0, characters = 0;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (++events > 1_000_000 || event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) { throw new Limit(); }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (++depth > 64) { throw new Limit(); }
                    if (reader.getLocalName().equals("si")) { current = new BoundedWriter(); }
                    text = reader.getLocalName().equals("t");
                } else if (event == XMLStreamConstants.CHARACTERS && text && current != null) { current.append(reader.getText()); }
                else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (reader.getLocalName().equals("si") && current != null) {
                        characters += current.toString().length();
                        if (strings.size() >= 4096 || characters > DocumentExtraction.MAX_TEXT_BYTES) { throw new Limit(); }
                        strings.add(current.toString()); current = null;
                    }
                    text = false; depth--;
                }
            }
        } finally { reader.close(); }
        return strings;
    }

    private static void sheet(byte[] bytes, List<String> strings, BoundedWriter text) throws Exception {
        XMLStreamReader reader = reader(bytes);
        String cellType = "", value = "";
        boolean capture = false;
        int depth = 0, events = 0, cells = 0;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (++events > 1_000_000 || event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) { throw new Limit(); }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (++depth > 64) { throw new Limit(); }
                    String name = reader.getLocalName();
                    if (name.equals("c")) {
                        if (++cells > 10000) { throw new Limit(); }
                        cellType = reader.getAttributeValue(null, "t"); value = "";
                    }
                    capture = name.equals("v") || name.equals("t");
                } else if (event == XMLStreamConstants.CHARACTERS && capture) {
                    if (value.length() + reader.getTextLength() > DocumentExtraction.MAX_TEXT_BYTES) { throw new Limit(); }
                    value += reader.getText();
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = reader.getLocalName();
                    if (name.equals("c")) {
                        if ("s".equals(cellType)) {
                            int index = Integer.parseInt(value);
                            if (index < 0 || index >= strings.size()) { throw new IOException(); }
                            value = strings.get(index);
                        }
                        text.append(value).append("\t");
                    } else if (name.equals("row")) { text.append("\n"); }
                    capture = false; depth--;
                }
            }
        } finally { reader.close(); }
    }

    static final class Limit extends IOException { }
    static final class Encrypted extends IOException { }
    static final class BoundedWriter extends Writer {
        private final StringWriter delegate = new StringWriter();
        private final int limit;
        private int bytes;
        BoundedWriter() { this(DocumentExtraction.MAX_TEXT_BYTES); }
        BoundedWriter(int limit) { this.limit = limit; }
        @Override public void write(char[] buffer, int offset, int count) throws IOException {
            String text = new String(buffer, offset, count);
            bytes += text.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > limit) { throw new Limit(); }
            delegate.write(buffer, offset, count);
        }
        @Override public void flush() { }
        @Override public void close() { }
        @Override public String toString() { return delegate.toString(); }
    }
}
