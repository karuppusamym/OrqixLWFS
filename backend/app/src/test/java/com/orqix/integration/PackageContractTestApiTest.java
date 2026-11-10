package com.orqix.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.orqix.support.ExamplePublisher;
import com.orqix.support.PackageTestBase;

import tools.jackson.databind.JsonNode;

/**
 * CG-Q04 (S148): contract tests of a connector package archive (checks as on import, fixtures replayed through the
 * runtime's response handling) without importing anything.
 */
class PackageContractTestApiTest extends PackageTestBase {

    private Response test(String user, byte[] archive) throws Exception {
        return api(user).post(Map.of("archive", Base64.getEncoder().encodeToString(archive)),
                "/workspaces/{ws}/connector-packages/contract-tests", PURCHASING);
    }

    private long versions() {
        return jdbc.sql("SELECT count(*) FROM connector_package_version").query(Long.class).single();
    }

    @Test
    void theCommittedServiceNowPackagePassesEveryFixtureAndNothingIsImported() throws Exception {
        long before = versions();
        byte[] archive = Files.readAllBytes(ExamplePublisher.repoRoot().resolve("examples/packages/servicenow-itsm-1.0.0.orqpkg"));
        Response r = test("wade.admin", archive);
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        JsonNode body = r.body();
        assertThat(body.path("packageId").asString()).isEqualTo("com.orqix.servicenow-itsm");
        assertThat(body.path("signatureStatus").asString()).isEqualTo("verified");
        assertThat(body.path("passed").asBoolean()).isTrue();
        assertThat(body.path("fixtures").asInt()).isEqualTo(5);
        assertThat(body.path("fixturesPassed").asInt()).isEqualTo(5);
        assertThat(body.path("problems").size()).isZero();
        String xml = body.path("junitXml").asString();
        assertThat(xml).contains("<testsuite name=\"com.orqix.servicenow-itsm@1.0.0\" tests=\"6\" failures=\"0\"");
        assertThat(xml).contains("name=\"fixtures/get-incident/resolved.json (servicenow-itsm.getIncident)\"");
        assertThat(versions()).isEqualTo(before);
    }

    @Test
    void aFixtureThatNoLongerHoldsAndAManifestMismatchAreReportedSeparately() throws Exception {
        Map<String, String> files = files();
        String path = "fixtures/get-incident/in-progress.json";
        files.put(path, files.get(path).replace("\"results\": { \"state\": \"2\" }", "\"results\": { \"state\": \"3\" }"));
        files.put("manifest.json", files.get("manifest.json").replace("\"readOnly\": 6", "\"readOnly\": 7"));
        Response r = test("wade.admin", signed(files));
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        JsonNode body = r.body();
        assertThat(body.path("passed").asBoolean()).isFalse();
        assertThat(body.path("fixturesPassed").asInt()).isEqualTo(4);
        JsonNode failed = null;
        for (JsonNode f : body.path("results")) {
            if (!f.path("passed").asBoolean()) {
                failed = f;
            }
        }
        assertThat(failed).isNotNull();
        assertThat(failed.path("path").asString()).isEqualTo(path);
        assertThat(failed.path("outcome").asString()).isEqualTo("succeeded");
        assertThat(failed.path("message").asString()).contains("state is not the expected value");
        assertThat(body.path("problems").get(0).path("code").asString()).isEqualTo("PKG_MANIFEST_MISMATCH");
        assertThat(body.toString()).doesNotContain("problems\":[{\"file\":\"" + path);
        assertThat(body.path("junitXml").asString()).contains("failures=\"2\"");
    }

    @Test
    void needsConnectorManageAndAReadableArchive() throws Exception {
        byte[] archive = Files.readAllBytes(ExamplePublisher.repoRoot().resolve("examples/packages/servicenow-itsm-1.0.0.orqpkg"));
        assertThat(test("dana.designer", archive).status()).isEqualTo(403);
        assertThat(test("fred.facilities", archive).status()).isEqualTo(404);
        Response broken = test("wade.admin", new byte[] {1, 2, 3});
        assertThat(broken.status()).isEqualTo(422);
    }
}
