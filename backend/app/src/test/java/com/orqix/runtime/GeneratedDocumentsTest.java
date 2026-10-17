package com.orqix.runtime;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.orqix.integration.DocumentGeneration;
import com.orqix.runtime.internal.RunAttachments;
import com.orqix.support.RuntimeTestBase;

class GeneratedDocumentsTest extends RuntimeTestBase {
    @Autowired RunAttachments attachments;
    @Autowired DocumentGeneration generation;

    private UUID workflow(String format) throws Exception {
        return publishPurchase(PURCHASING, d -> {
            d.set("nodes", json.parse("""
                [{"id":"start","type":"start","label":"Request submitted","config":{}},
                 {"id":"receipt","type":"generateDocument","label":"Generate receipt","config":{
                  "format":"%s","fileName":"Receipt","template":"Amount: {{amount}}\\nPublished receipt",
                  "inputMapping":{"amount":"/input/amount"}}},
                 {"id":"end","type":"end","label":"Receipt ready","config":{"businessOutcome":"Completed","outcomeKind":"success"}}]
                """.formatted(format)));
            d.set("transitions", json.parse("[{\"id\":\"t1\",\"from\":\"start\",\"to\":\"receipt\",\"on\":\"next\"},{\"id\":\"t2\",\"from\":\"receipt\",\"to\":\"end\",\"on\":\"next\"}]"));
            d.set("decisions", json.parse("[]")); d.set("dependencies", json.parse("{\"connections\":[]}"));
            d.set("outputMapping", json.object()); d.set("outputSchema", json.parse("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}")); d.remove("uiLayout");
        });
    }
    @Test void publishedPdfExecutesDownloadsAndUsesExistingDataAuthorization() throws Exception {
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow("pdf"), BigDecimal.TEN).path("id").asString());
        awaitLifecycle(PURCHASING, runId, "completed");
        var listed = api("rhea.requester").get("/workspaces/{ws}/runs/{run}/attachments", PURCHASING, runId);
        assertThat(listed.status()).isEqualTo(200); assertThat(listed.body().path("items")).hasSize(1);
        String id = listed.body().path("items").get(0).path("id").asString();
        var download = api("rhea.requester").get("/workspaces/{ws}/runs/{run}/attachments/{id}/content", PURCHASING, runId, id);
        assertThat(download.status()).isEqualTo(200);
        try (var pdf = Loader.loadPDF(download.bytes())) { assertThat(new PDFTextStripper().getText(pdf)).contains("Amount: 10", "Published receipt"); }
        assertThat(api("vic.viewer").get("/workspaces/{ws}/runs/{run}/attachments/{id}/content", PURCHASING, runId, id).status()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE run_id = :run AND type = 'DOCUMENT_GENERATED'").param("run", runId).query(Integer.class).single()).isEqualTo(1);
    }
    @Test void wordExecutesAndReusingNodeArtifactNeverAddsAnotherFile() throws Exception {
        UUID runId = UUID.fromString(start("rhea.requester", PURCHASING, workflow("docx"), BigDecimal.TEN).path("id").asString());
        awaitLifecycle(PURCHASING, runId, "completed");
        UUID initiator = jdbc.sql("SELECT initiator_subject_id FROM workflow_run WHERE id = :run").param("run", runId).query(UUID.class).single();
        var artifact = generation.generate("docx", "Receipt", "Amount: {{amount}}", json.object().put("amount", 10));
        UUID first = attachments.addGenerated(PURCHASING, runId, initiator, "receipt", artifact);
        UUID second = attachments.addGenerated(PURCHASING, runId, initiator, "receipt", artifact);
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.sql("SELECT count(*) FROM run_attachment WHERE run_id = :run").param("run", runId).query(Integer.class).single()).isEqualTo(1);
    }
}
