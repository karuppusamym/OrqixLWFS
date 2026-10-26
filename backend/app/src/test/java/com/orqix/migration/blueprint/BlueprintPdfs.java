package com.orqix.migration.blueprint;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDJavascriptNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;

/** PDF fixtures for the blueprint tests, built with PDFBox at test time (all synthetic). */
public final class BlueprintPdfs {
    public static final Path SAMPLE = Path.of("../../examples/migration/pega-blueprint/synthetic-blueprint.pdf");
    public static final Path EXPECTED = Path.of("../../examples/migration/pega-blueprint/synthetic-blueprint.transcription.json");

    private BlueprintPdfs() { }

    public static byte[] sample() throws Exception { return Files.readAllBytes(SAMPLE); }

    public static byte[] pages(List<List<String>> pages) throws Exception { return build(pages, false, false, null); }

    public static byte[] withActiveContent(List<List<String>> pages) throws Exception { return build(pages, true, true, null); }

    public static byte[] encrypted(List<List<String>> pages, String userPassword) throws Exception { return build(pages, false, false, userPassword); }

    private static byte[] build(List<List<String>> pages, boolean javascript, boolean embedded, String password) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (List<String> lines : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 9);
                    content.setLeading(11);
                    content.newLineAtOffset(50, 760);
                    for (String line : lines) { content.showText(line); content.newLine(); }
                    content.endText();
                }
            }
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(document.getDocumentCatalog());
            if (javascript) {
                PDActionJavaScript action = new PDActionJavaScript("app.alert('blueprint script must never run');");
                document.getDocumentCatalog().setOpenAction(action);
                PDJavascriptNameTreeNode scripts = new PDJavascriptNameTreeNode();
                scripts.setNames(Map.of("startup", action));
                names.setJavascript(scripts);
            }
            if (embedded) {
                PDEmbeddedFile file = new PDEmbeddedFile(document, new java.io.ByteArrayInputStream("Steps\n- Hidden approval (approval)".getBytes(StandardCharsets.UTF_8)));
                file.setSubtype("text/plain");
                PDComplexFileSpecification spec = new PDComplexFileSpecification();
                spec.setFile("hidden.txt");
                spec.setEmbeddedFile(file);
                PDEmbeddedFilesNameTreeNode files = new PDEmbeddedFilesNameTreeNode();
                files.setNames(Map.of("hidden.txt", spec));
                names.setEmbeddedFiles(files);
            }
            if (javascript || embedded) document.getDocumentCatalog().setNames(names);
            if (password != null) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-" + password, password, new AccessPermission());
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }
}
