package com.orqix.integration;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class DocumentGenerationTest {
    private final DocumentGeneration generation = new DocumentGeneration();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void pdfIsReadableWithMappedScalarFieldsAndBoundedPages() throws Exception {
        var document = generation.generate("pdf", "Approved request", "Request: {{request.id}}\nTotal: {{total}}\nApproved: {{approved}}", json.readTree("{\"request\":{\"id\":\"R-42\"},\"total\":25,\"approved\":true}"));
        try (var pdf = Loader.loadPDF(document.bytes())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            assertThat(new PDFTextStripper().getText(pdf)).contains("Request: R-42", "Total: 25", "Approved: true");
        }
        assertThat(document.name()).isEqualTo("Approved request.pdf");
        assertThat(document.contentType()).isEqualTo("application/pdf");
    }

    @Test void docxEscapesDataSupportsUnicodeAndHasNoExternalRelationships() throws Exception {
        var data = json.readTree("{\"name\":\"<script>& 世界\"}");
        var document = generation.generate("docx", "Receipt", "Hello {{name}}", data);
        assertThat(generation.generate("docx", "Receipt", "Hello {{name}}", data).bytes()).isEqualTo(document.bytes());
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(document.bytes()), StandardCharsets.UTF_8)) {
            int count = 0;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String text = new String(zip.readAllBytes(), StandardCharsets.UTF_8); count++;
                assertThat(text).doesNotContain("TargetMode=\"External\"", "<script>");
                if (entry.getName().equals("word/document.xml")) assertThat(text).contains("&lt;script&gt;&amp; 世界");
            }
            assertThat(count).isEqualTo(3);
        }
    }

    @Test void rejectsMissingComplexAndMalformedFieldsWithoutLeakingValues() throws Exception {
        var data = json.readTree("{\"secret\":{\"token\":\"sensitive\"}}");
        for (String template : new String[] {"{{missing}}", "{{secret}}", "{{secret[token]}}", "{{ secret }}"})
            assertThatThrownBy(() -> generation.generate("docx", "Receipt", template, data)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("sensitive");
    }

    @Test void rejectsPathsOversizedOutputAndUnsupportedPdfGlyphs() throws Exception {
        var data = json.readTree("{\"name\":\"世界\",\"long\":\"" + "a".repeat(8_000) + "\"}");
        assertThatThrownBy(() -> generation.generate("pdf", "../receipt", "hello", data)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generation.generate("pdf", "Receipt", "{{name}}", data)).hasMessageContaining("DOCX");
        assertThatThrownBy(() -> generation.generate("docx", "Receipt", "{{long}}".repeat(5), data)).hasMessageContaining("limit");
        assertThatThrownBy(() -> generation.generate("docx", "Receipt", "a".repeat(16_001), data)).isInstanceOf(IllegalArgumentException.class);
    }
}
