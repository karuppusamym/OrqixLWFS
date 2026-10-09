package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.orqix.shared.config.OrqixProperties;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;

/** T-SEC-08: a real file-backed credential is used on the wire but never returned or logged. */
@ExtendWith(OutputCaptureExtension.class)
class SecretIsolationTest extends RuntimeTestBase {
    @Autowired
    OrqixProperties properties;

    @Test
    void credentialNeverAppearsInSuccessFailureEventsExportOrLogs(CapturedOutput logs) throws Exception {
        String credential = "test-secret-canary-" + UUID.randomUUID();
        String secretRef = "test-" + UUID.randomUUID();
        Path directory = properties.secrets().dir();
        Files.createDirectories(directory);
        Path file = directory.resolve(secretRef);
        Files.writeString(file, credential, StandardCharsets.UTF_8);
        try {
            UUID workflow = publishPurchase(PURCHASING, null);
            jdbc.sql("UPDATE connection_binding SET auth_type = 'bearerToken', secret_ref = :ref "
                    + "WHERE workspace_id = :ws AND connection_key = 'procurement'")
                    .param("ref", secretRef).param("ws", PURCHASING).update();
            UUID success = UUID.fromString(start("rhea.requester", PURCHASING, workflow,
                    java.math.BigDecimal.ONE).path("id").asString());
            approve("mani.manager", PURCHASING, success, "manager");
            awaitLifecycle(PURCHASING, success, "completed", "needsAttention");
            assertThat(lifecycle(PURCHASING, success)).isEqualTo("completed");
            assertThat(Stub.postHeaders().get(0).get("authorization")).isEqualTo("Bearer " + credential);

            Stub.fault(Stub.Mode.STATUS400, 1, 0);
            UUID failure = UUID.fromString(start("rhea.requester", PURCHASING, workflow,
                    java.math.BigDecimal.ONE).path("id").asString());
            approve("mani.manager", PURCHASING, failure, "manager");
            awaitLifecycle(PURCHASING, failure, "needsAttention");
            assertThat(run(PURCHASING, failure).path("attention").path("reason").asString())
                    .isEqualTo("EFFECT_REJECTED");

            for (UUID id : List.of(success, failure)) {
                for (String user : List.of("rhea.requester", "olga.operator")) {
                    Response detail = api(user).get("/workspaces/{ws}/runs/{id}", PURCHASING, id);
                    assertThat(detail.status()).isEqualTo(200);
                    assertSafe(detail.toString(), credential);
                    Response events = api(user).get("/workspaces/{ws}/runs/{id}/events", PURCHASING, id);
                    assertThat(events.status()).isEqualTo(200);
                    assertSafe(events.toString(), credential);
                }
            }
            for (String endpoint : List.of("/connections", "/connections/procurement")) {
                Response response = api("wade.admin").get("/workspaces/{ws}" + endpoint, PURCHASING);
                assertThat(response.status()).isEqualTo(200);
                assertSafe(response.toString(), credential);
            }
            Response list = api("olga.operator").get("/workspaces/{ws}/runs", PURCHASING);
            assertThat(list.status()).isEqualTo(200);
            assertSafe(list.toString(), credential);
            Response notifications = api("rhea.requester").get("/workspaces/{ws}/me/notifications", PURCHASING);
            assertThat(notifications.status()).isEqualTo(200);
            assertSafe(notifications.toString(), credential);
            JsonNode release = run(PURCHASING, success).path("release");
            Response exported = api("dana.designer").get("/workspaces/{ws}/workflows/{wf}/releases/{release}/export",
                    PURCHASING, workflow, release.path("id").asString());
            assertThat(exported.status()).isEqualTo(200);
            int entries = 0;
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(exported.bytes()))) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                    assertSafe(entry.getName(), credential);
                    assertSafe(new String(zip.readAllBytes(), StandardCharsets.UTF_8), credential);
                    entries++;
                }
            }
            assertThat(entries).isPositive();
            jdbc.sql("SELECT metadata::text FROM audit_event WHERE workspace_id = :ws")
                    .param("ws", PURCHASING).query(String.class).list().forEach(value -> assertSafe(value, credential));
            assertSafe(logs.getAll(), credential);
        } finally {
            bindStub(PURCHASING);
            Files.deleteIfExists(file);
        }
    }

    private static void assertSafe(String text, String credential) {
        assertThat(text).doesNotContain(credential, "Bearer " + credential, "Authorization:", "authorization:");
    }
}
