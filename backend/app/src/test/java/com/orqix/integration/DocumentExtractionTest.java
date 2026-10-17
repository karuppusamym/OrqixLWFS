package com.orqix.integration;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/** Exercises real PDF parsing and bounded OOXML parsing in the isolated worker JVM. */
class DocumentExtractionTest {
    static final String PDF = "application/pdf";
    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private final DocumentExtraction extraction = new DocumentExtraction();

    static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(); document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(20, 700); content.showText(text); content.endText();
            }
            document.save(bytes); return bytes.toByteArray();
        }
    }

    static byte[] office(Map<String, String> parts) throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (var entry : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
            zip.finish(); return bytes.toByteArray();
        }
    }

    private String extract(byte[] bytes, String mime) {
        return extraction.extract(bytes, mime, System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
    }

    @Test
    void readsPdfInABoundedSeparateJvm() throws Exception {
        assertThat(extract(pdf("Approved purchase policy"), PDF)).contains("Approved purchase policy");
    }

    @Test
    void readsDocxTextWithoutResolvingExternalRelationships() throws Exception {
        byte[] file = office(Map.of("word/document.xml", "<w:document xmlns:w=\"urn:word\"><w:p><w:r><w:t>Approved policy</w:t></w:r></w:p></w:document>",
                "word/_rels/document.xml.rels", "<Relationships><Relationship Target=\"https://must-not-be-fetched.invalid/payload\" TargetMode=\"External\"/></Relationships>"));
        assertThat(extract(file, DOCX)).contains("Approved policy").doesNotContain("must-not-be-fetched");
    }

    @Test
    void readsPptxSlidesAndXlsxCachedValuesWithoutEvaluatingFormulas() throws Exception {
        assertThat(extract(office(Map.of("ppt/slides/slide1.xml", "<slide><p><t>Board review</t></p></slide>")), PPTX))
                .contains("Board review");
        byte[] workbook = office(Map.of("xl/sharedStrings.xml", "<sst><si><t>Vendor</t></si></sst>",
                "xl/worksheets/sheet1.xml", "<sheet><row><c t=\"s\"><v>0</v></c><c><f>WEBSERVICE(\"https://must-not-run.invalid\")</f><v>100</v></c></row></sheet>"));
        assertThat(extract(workbook, XLSX)).contains("Vendor", "100").doesNotContain("WEBSERVICE", "must-not-run");
    }

    @Test
    void rejectsDtdEntitiesEmbeddedExecutablesAndOversizedOutput() throws Exception {
        for (byte[] file : new byte[][] {
                office(Map.of("word/document.xml", "<!DOCTYPE a [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><document><t>&secret;</t></document>")),
                office(Map.of("word/document.xml", "<document><t>Text</t></document>", "word/vbaProject.bin", "macro")),
                office(Map.of("word/document.xml", "<document><t>" + "x".repeat(65537) + "</t></document>")) }) {
            assertThatThrownBy(() -> extract(file, DOCX)).isInstanceOf(MicrosoftGraphDocuments.Failure.class);
        }
    }

    @Test
    void rejectsEncryptedPdfScansLegacyOfficeAndExpiredDeadline() throws Exception {
        byte[] encrypted;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "password",
                    new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            document.save(bytes); encrypted = bytes.toByteArray();
        }
        assertThatThrownBy(() -> extract(encrypted, PDF)).isInstanceOfSatisfying(MicrosoftGraphDocuments.Failure.class,
                e -> assertThat(e.code()).isEqualTo("DOCUMENT_ENCRYPTED"));
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            document.addPage(new PDPage()); document.save(bytes);
            assertThatThrownBy(() -> extract(bytes.toByteArray(), PDF)).isInstanceOfSatisfying(MicrosoftGraphDocuments.Failure.class,
                    e -> assertThat(e.code()).isEqualTo("DOCUMENT_TEXT_UNAVAILABLE"));
        }
        assertThatThrownBy(() -> extract(new byte[] {1}, "application/msword"))
                .isInstanceOfSatisfying(MicrosoftGraphDocuments.Failure.class,
                        e -> assertThat(e.code()).isEqualTo("DOCUMENT_CONTENT_TYPE_UNSUPPORTED"));
        assertThatThrownBy(() -> extraction.extract(pdfUnchecked(), PDF, System.nanoTime() - 1))
                .isInstanceOf(MicrosoftGraphDocuments.Failure.class);
    }

    private byte[] pdfUnchecked() { try { return pdf("text"); } catch (Exception e) { throw new RuntimeException(e); } }

    @Test
    void enforcesArchiveEntryDepthExpansionAndPageLimits() throws Exception {
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("word/document.xml", "<document><t>Text</t></document>");
        for (int i = 0; i < 256; i++) { entries.put("parts/" + i, "x"); }
        assertThatThrownBy(() -> extract(officeUnchecked(entries), DOCX)).isInstanceOf(MicrosoftGraphDocuments.Failure.class);
        assertThatThrownBy(() -> extract(officeUnchecked(Map.of("word/document.xml", "<a>".repeat(65) + "<t>Text</t>" + "</a>".repeat(65))), DOCX))
                .isInstanceOf(MicrosoftGraphDocuments.Failure.class);
        assertThatThrownBy(() -> extract(officeUnchecked(Map.of("word/document.xml", "<document><t>Text</t></document>",
                "parts/huge.bin", "x".repeat(DocumentExtractionWorker.MAX_EXPANDED_BYTES + 1))), DOCX))
                .isInstanceOf(MicrosoftGraphDocuments.Failure.class);
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            for (int i = 0; i < 101; i++) { document.addPage(new PDPage()); }
            document.save(bytes);
            assertThatThrownBy(() -> extract(bytes.toByteArray(), PDF)).isInstanceOfSatisfying(MicrosoftGraphDocuments.Failure.class,
                    e -> assertThat(e.code()).isEqualTo("DOCUMENT_EXTRACTION_LIMIT_EXCEEDED"));
        }
    }

    private static byte[] officeUnchecked(Map<String, String> parts) { try { return office(parts); } catch (Exception e) { throw new RuntimeException(e); } }

    @Test
    void writesAPackagingProbeForExecutableJarVerification() throws Exception {
        Path evidence = Path.of("../../evidence/document-worker-probe");
        Files.createDirectories(evidence);
        Files.write(evidence.resolve("policy.pdf"), pdf("Packaged worker verified"));
        Files.write(evidence.resolve("policy.docx"), office(Map.of("word/document.xml", "<document><p><t>Packaged Office worker verified</t></p></document>")));
        assertThat(DocumentExtraction.workerCommand("pdf", evidence.resolve("policy.pdf"), evidence.resolve("text.txt")))
                .contains("-Xmx128m", "-XX:MaxMetaspaceSize=64m");
    }
}
