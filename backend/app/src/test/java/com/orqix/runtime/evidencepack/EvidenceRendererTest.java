package com.orqix.runtime.evidencepack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class EvidenceRendererTest {

    private final Json json = new Json(JsonMapper.builder().build());
    private final EvidenceRenderer renderer = new EvidenceRenderer(json);

    private ObjectNode pack(int rows) {
        ObjectNode pack = json.object();
        pack.putObject("release").put("workflowName", "Purchase approval").put("releaseNumber", 3)
                .put("publishedAt", "2026-10-30T09:15:02.123Z").put("active", true)
                .put("semanticHash", "a".repeat(64));
        ArrayNode items = pack.putArray("items");
        for (int i = 0; i < rows; i++) {
            items.addObject().put("step", "Manager approval " + i).put("decision", "approve")
                    .put("at", "2026-10-30T10:00:00Z").set("by", json.object().put("id", "x").put("displayName", "Mani Manager"));
        }
        return ReleaseEvidencePack.seal(json, pack);
    }

    private EvidenceDocument document(ObjectNode pack) {
        return new EvidenceDocument("Release evidence pack: Purchase approval, release 3", "Workspace Purchasing.",
                List.of(EvidenceDocument.fields("Release identity", "Checksums are SHA-256.", pack,
                        "release.workflowName", "Workflow", "release.publishedAt", "Published", "release.active",
                        "Active", "release.semanticHash", "Semantic hash", "release.missing", "Not shown"),
                        EvidenceDocument.table("Decisions", null, pack.path("items"), "None.", "step", "Step",
                                "decision", "Decision", "by", "By", "at", "When"),
                        EvidenceDocument.table("Empty", null, json.mapper().createArrayNode(), "Nothing here.", "a", "A")),
                ReleaseEvidencePack.integrity(pack));
    }

    @Test
    void formatsValuesForAuditors() {
        assertThat(EvidenceDocument.text(json.parse("\"2026-10-30T09:15:02.123Z\""))).isEqualTo("2026-10-30 09:15:02 UTC");
        assertThat(EvidenceDocument.text(json.parse("true"))).isEqualTo("Yes");
        assertThat(EvidenceDocument.text(json.parse("{\"id\":\"1\",\"displayName\":\"Dana\"}"))).isEqualTo("Dana");
        assertThat(EvidenceDocument.text(json.parse("[\"a\",\"b\"]"))).isEqualTo("a, b");
        assertThat(EvidenceDocument.text(json.parse("null"))).isEqualTo("-");
        EvidenceDocument.Section fields = document(pack(1)).sections().get(0);
        assertThat(fields.fields()).extracting(EvidenceDocument.Field::label)
                .containsExactly("Workflow", "Published", "Active", "Semantic hash");
    }

    @Test
    void pdfCarriesEveryFactAndPagesLongTables() throws Exception {
        ObjectNode pack = pack(120);
        EvidenceRenderer.Rendered pdf = renderer.render("pdf", "purchase r3/evidence", pack, document(pack));
        assertThat(pdf.fileName()).isEqualTo("purchase-r3-evidence.pdf");
        assertThat(pdf.contentType()).isEqualTo("application/pdf");
        try (PDDocument loaded = Loader.loadPDF(pdf.bytes())) {
            assertThat(loaded.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(loaded);
            assertThat(text).contains("Release evidence pack: Purchase approval, release 3", "Release identity",
                    "2026-10-30 09:15:02 UTC", "Manager approval 119", "Mani Manager", "Nothing here.",
                    pack.path("integrity").path("digest").asString(), "page 1 of " + loaded.getNumberOfPages());
            // The table header repeats on continuation pages.
            String lastPage = text.substring(text.lastIndexOf("page " + (loaded.getNumberOfPages() - 1) + " of"));
            assertThat(lastPage).contains("Decision");
        }
    }

    @Test
    void pdfReplacesGlyphsOutsideTheStandardFontAndDocxKeepsThem() throws Exception {
        ObjectNode pack = pack(1);
        ((ObjectNode) pack.path("release")).put("workflowName", "Ankauf 审批 & <b>");
        EvidenceDocument document = document(pack);
        try (PDDocument loaded = Loader.loadPDF(renderer.render("pdf", "x", pack, document).bytes())) {
            assertThat(new PDFTextStripper().getText(loaded)).contains("Ankauf ?? & <b>");
        }
        String xml = entries(renderer.render("docx", "x", pack, document).bytes()).get(1);
        assertThat(xml).contains("Ankauf 审批 &amp; &lt;b&gt;");
    }

    @Test
    void docxIsAMinimalWordPackageWithTables() throws Exception {
        ObjectNode pack = pack(3);
        EvidenceRenderer.Rendered docx = renderer.render("docx", "pack", pack, document(pack));
        assertThat(docx.contentType()).isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx.bytes()))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                names.add(e.getName());
            }
        }
        assertThat(names).containsExactly("[Content_Types].xml", "_rels/.rels", "docProps/core.xml", "word/document.xml");
        String document = entries(docx.bytes()).get(1);
        assertThat(document).contains("<w:tbl>", "<w:tblHeader/>", "Manager approval 2", "Mani Manager",
                "2026-10-30 10:00:00 UTC", "w:orient=\"landscape\"", pack.path("integrity").path("digest").asString());
    }

    @Test
    void jsonIsThePackItselfWithAVerifiableDigest() {
        ObjectNode pack = pack(2);
        EvidenceRenderer.Rendered rendered = renderer.render("json", "pack", pack, document(pack));
        ObjectNode parsed = (ObjectNode) json.parse(new String(rendered.bytes(), StandardCharsets.UTF_8));
        String digest = parsed.path("integrity").path("digest").asString();
        parsed.remove("integrity");
        assertThat(json.canonicalSha256(parsed)).isEqualTo(digest);
    }

    @Test
    void rejectsUnknownFormats() {
        assertThatThrownBy(() -> EvidenceRenderer.requireFormat("xlsx")).isInstanceOf(ApiException.class);
        assertThat(EvidenceRenderer.requireFormat("docx")).isEqualTo("docx");
    }

    /** [document.xml content at index 1 when found], index 0 unused. */
    private static List<String> entries(byte[] docx) throws Exception {
        List<String> out = new ArrayList<>(List.of("", ""));
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.getName().equals("word/document.xml")) {
                    out.set(1, new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }
}
