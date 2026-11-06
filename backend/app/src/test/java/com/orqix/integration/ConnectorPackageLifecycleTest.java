package com.orqix.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.orqix.integration.packages.PackageArchive;
import com.orqix.support.PackageTestBase;
import com.orqix.support.ServiceNowStub;
import com.orqix.support.Stub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Package lifecycle (CG-C03, CG-C04, CG-C10, docs/build/S133) against PostgreSQL through the public API: import with
 * full validation and the audited unsigned override, the review facts, approval of the exact hash by a different
 * person holding the grant-only connector.approve, install as versioned connection assets without bindings, binding
 * limited to the package hosts, connection test, palette search, a workflow from the starter template, upgrade with the
 * compatibility report and where-used, retirement and the internal catalog.
 */
class ConnectorPackageLifecycleTest extends PackageTestBase {

    @Test
    void importValidatesEverythingAndNeedsAnOverrideWhenUnsigned() throws Exception {
        String prefix = prefix();
        Map<String, String> files = variant(prefix, "1.0.0");
        // Not signed: refused without a reason, accepted (unsigned) with one.
        byte[] unsigned = PackageArchive.build(bytes(files), null, null);
        Response refused = importPackage("wade.admin", unsigned, null);
        assertThat(refused.status()).as(refused.toString()).isEqualTo(422);
        assertThat(codes(refused)).contains("PKG_UNSIGNED");
        // Without connector.manage nothing is imported (audited denial).
        assertThat(importPackage("dana.designer", unsigned, "Testing").status()).isEqualTo(403);
        Response accepted = importPackage("wade.admin", unsigned, "Internal package built by the integration team");
        assertThat(accepted.status()).as(accepted.toString()).isEqualTo(201);
        assertThat(accepted.body().path("summary").path("signatureStatus").asString()).isEqualTo("unsigned");
        assertThat(accepted.body().path("unsignedReason").asString()).contains("integration team");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'connector.package-imported'"
                + " AND metadata->>'unsignedOverride' = 'true' AND metadata->>'packageId' = :pkg")
                .param("pkg", "com.orqix." + prefix).query(Integer.class).single()).isEqualTo(1);
        // The same version again is refused: versions are immutable.
        assertThat(importPackage("wade.admin", unsigned, "Again").status()).isEqualTo(409);

        // Broken content is reported per file with stable codes.
        Map<String, String> broken = variant(prefix(), "1.0.0");
        editJson(broken, "manifest.json", m -> ((ObjectNode) m.path("operations")).put("readOnly", 9));
        editJson(broken, "fixtures/get-incident/resolved.json", f -> ((ObjectNode) f.path("expect").path("results"))
                .put("state", "7"));
        editJson(broken, "templates/steps/raise-incident.json", t -> ((ObjectNode) t.path("step").path("config")
                .path("inputMapping")).remove("shortDescription"));
        Response invalid = importPackage("wade.admin", signed(broken), null);
        assertThat(invalid.status()).as(invalid.toString()).isEqualTo(422);
        assertThat(codes(invalid)).contains("PKG_MANIFEST_MISMATCH", "PKG_FIXTURE", "WF_MAPPING_MISSING_REQUIRED");
        assertThat(invalid.body().path("fieldErrors").toString()).contains("/files/fixtures/get-incident/resolved.json");
        // A wildcard over a top-level domain is refused.
        Map<String, String> wide = variant(prefix(), "1.0.0");
        editJson(wide, "manifest.json", m -> ((ArrayNode) m.path("hosts")).removeAll()
                .add(json.object().put("pattern", "https://*.com").put("purpose", "Too wide")));
        Response tooWide = importPackage("wade.admin", signed(wide), null);
        assertThat(tooWide.status()).isEqualTo(422);
        assertThat(tooWide.body().path("fieldErrors").toString()).contains("/files/manifest.json/hosts");

        // A signature that does not match the content is always refused, even with a reason.
        Map<String, byte[]> entries = ConnectorPackageArchiveTest.unzip(signed(variant(prefix(), "1.0.0")));
        byte[] other = signed(variant(prefix(), "1.0.0"));
        entries.put(PackageArchive.SIGNATURE, ConnectorPackageArchiveTest.unzip(other).get(PackageArchive.SIGNATURE));
        Response tampered = importPackage("wade.admin", ConnectorPackageArchiveTest.zip(entries), "Please");
        assertThat(tampered.status()).isEqualTo(422);
        assertThat(codes(tampered)).contains("PKG_SIGNATURE_INVALID");
    }

    @Test
    void reviewApproveInstallBindTestAndUse() throws Exception {
        String prefix = prefix();
        Response imported = importPackage("wade.admin", signed(variant(prefix, "1.0.0")), null);
        assertThat(imported.status()).as(imported.toString()).isEqualTo(201);
        JsonNode detail = imported.body();
        String id = detail.path("summary").path("id").asString();
        assertThat(detail.path("summary").path("signatureStatus").asString()).isEqualTo("verified");
        assertThat(detail.path("summary").path("status").asString()).isEqualTo("pending");
        // The review screen's facts.
        JsonNode review = detail.path("review");
        assertThat(review.path("hosts").get(0).path("pattern").asString()).isEqualTo("https://*.service-now.com");
        assertThat(review.path("operationsByEffect").path("readOnly").asInt()).isEqualTo(6);
        assertThat(review.path("operationsByEffect").path("idempotentByLookup").asInt()).isEqualTo(2);
        assertThat(review.path("operationsByEffect").path("nonIdempotent").asInt()).isEqualTo(2);
        assertThat(review.path("dataLeavingOrqix").toString()).contains("Work notes");
        assertThat(review.path("triggers").toString()).contains("hmacSha256").contains("\"kind\":\"poll\"");
        assertThat(review.path("fixtures").path("passed").asInt()).isEqualTo(5);
        assertThat(review.path("authSchemes").toString()).contains("oauth2ClientCredentials");
        assertThat(detail.path("comparison").path("installedVersion").isNull()).isTrue();
        // Nothing is active before approval.
        assertThat(install("wade.admin", id).status()).isEqualTo(409);
        // The importer cannot approve, even with connector.approve; the approver must name the exact hash.
        Response own = decide("wade.admin", detail, "approve");
        assertThat(own.status()).as(own.toString()).isEqualTo(403);
        ObjectNode wrongHash = (ObjectNode) detail.deepCopy();
        ((ObjectNode) wrongHash.path("summary")).put("sha256", "0".repeat(64));
        assertThat(decide("paul.publisher", wrongHash, "approve").status()).isEqualTo(409);
        assertThat(decide("dana.designer", detail, "approve").status()).isEqualTo(403);
        JsonNode reviewed = api("paul.publisher").get("/workspaces/{ws}/connector-packages/{id}", PURCHASING, id).body();
        assertThat(reviewed.path("canDecide").asBoolean()).isTrue();
        Response approved = decide("paul.publisher", detail, "approve");
        assertThat(approved.status()).as(approved.toString()).isEqualTo(200);
        assertThat(approved.body().path("summary").path("status").asString()).isEqualTo("approved");
        assertThat(decide("paul.publisher", approved.body(), "reject").status()).isEqualTo(409);
        // Installing needs connector.manage; the connections become versioned assets; no binding is created.
        assertThat(install("paul.publisher", id).status()).isEqualTo(403);
        Response installed = install("wade.admin", id);
        assertThat(installed.status()).as(installed.toString()).isEqualTo(201);
        assertThat(installed.body().path("connections").toString()).contains("\"key\":\"" + prefix + "\"");
        assertThat(jdbc.sql("SELECT count(*) FROM connection_binding WHERE workspace_id = :ws AND connection_key = :k")
                .param("ws", PURCHASING).param("k", prefix).query(Integer.class).single()).isZero();
        assertThat(install("wade.admin", id).status()).isEqualTo(409);
        // Bind: only the package hosts (or a deployment development host such as the stub).
        Response outside = api("wade.admin").put(Map.of("baseUrl", "https://public.test.example/api",
                "auth", Map.of("type", "none"), "enabled", true), null,
                "/workspaces/{ws}/connections/{key}/binding", PURCHASING, prefix);
        assertThat(outside.status()).as(outside.toString()).isEqualTo(422);
        assertThat(codes(outside)).contains("PKG_HOST");
        bindToStub(prefix);
        // Test connection on a read-only operation.
        ServiceNowStub.seedUser("ada.lovelace", "ada@example.org");
        Response tested = api("wade.admin").post(Map.of("version", 1, "operation", "lookupUser",
                "input", Map.of("email", "ada@example.org")), "/workspaces/{ws}/connections/{key}/test", PURCHASING, prefix);
        assertThat(tested.status()).as(tested.toString()).isEqualTo(200);
        assertThat(tested.body().path("outcome").asString()).isEqualTo("succeeded");
        // Use: the palette finds operations and step templates by name.
        JsonNode palette = api("dana.designer").get("/workspaces/{ws}/connector-palette?q=incident", PURCHASING).body();
        List<String> labels = new ArrayList<>();
        palette.path("items").forEach(i -> labels.add(i.path("kind").asString() + ":" + i.path("label").asString()));
        assertThat(labels).contains("stepTemplate:Raise an incident · ServiceNow ITSM",
                "operation:Raise an incident · ServiceNow ITSM");
        JsonNode template = null;
        for (JsonNode item : palette.path("items")) {
            if ("raise-incident".equals(item.path("templateKey").asString()) && prefix.equals(item.path("connectionKey").asString())) {
                template = item;
            }
        }
        assertThat(template).isNotNull();
        assertThat(template.path("connectionVersion").asInt()).isEqualTo(1);
        assertThat(template.path("step").path("config").path("operation").asString()).isEqualTo("createIncident");
        // A workflow from the starter template pins the installed connection versions.
        Response created = api("dana.designer").post(Map.of("key", "access-" + prefix, "name", "Access " + prefix),
                "/workspaces/{ws}/connector-packages/{id}/workflow-templates/{key}/workflows", PURCHASING, id,
                "it-access-request");
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        JsonNode draft = api("dana.designer").get("/workspaces/{ws}/workflows/{wf}/draft", PURCHASING,
                created.body().path("id").asString()).body();
        assertThat(draft.path("definition").path("dependencies").path("connections").get(0).path("key").asString())
                .isEqualTo(prefix);
        Response validated = api("dana.designer").post(Map.of("revision", draft.path("revision").asInt()),
                "/workspaces/{ws}/workflows/{wf}/validate", PURCHASING, created.body().path("id").asString());
        assertThat(validated.status()).as(validated.toString()).isEqualTo(200);
        assertThat(validated.body().path("issues").toString()).doesNotContain("\"severity\":\"blocker\"");
        assertThat(api("dana.designer").post(Map.of("key", "x-" + prefix, "name", "X"),
                "/workspaces/{ws}/connector-packages/{id}/workflow-templates/{key}/workflows", PURCHASING, id, "missing")
                .status()).isEqualTo(404);
    }

    @Test
    void upgradeReportsCompatibilityAndWhereUsedRetireBlocksNewInstallsAndTheCatalogShowsOutdatedPins() throws Exception {
        String prefix = prefix();
        String v1 = importApproveInstall(signed(variant(prefix, "1.0.0")));
        bindToStub(prefix);
        Response created = api("dana.designer").post(Map.of("key", "up-" + prefix, "name", "Upgrade " + prefix),
                "/workspaces/{ws}/connector-packages/{id}/workflow-templates/{key}/workflows", PURCHASING, v1,
                "it-access-request");
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        // 1.1.0 removes lookupUser and makes an input of updateIncidentFields required.
        Map<String, String> next = variant(prefix, "1.1.0");
        String conn = "connections/" + prefix + ".connection.json";
        editJson(next, conn, c -> {
            ((ObjectNode) c.path("operations")).remove("lookupUser");
            ((ObjectNode) c.path("operations").path("updateIncidentFields").path("requestSchema"))
                    .set("required", json.mapper().createArrayNode().add("state"));
        });
        editJson(next, "manifest.json", m -> ((ObjectNode) m.path("operations")).put("readOnly", 5));
        Response imported = importPackage("wade.admin", signed(next), null);
        assertThat(imported.status()).as(imported.toString()).isEqualTo(201);
        JsonNode comparison = imported.body().path("comparison");
        assertThat(comparison.path("installedVersion").asString()).isEqualTo("1.0.0");
        assertThat(comparison.path("removedOperations").toString()).contains(prefix + ".lookupUser");
        assertThat(comparison.path("changes").toString()).contains("The input state is now required.");
        assertThat(comparison.path("compatible").asBoolean()).isFalse();
        assertThat(imported.body().path("whereUsed").toString()).contains("up-" + prefix);
        Response approved = decide("paul.publisher", imported.body(), "approve");
        assertThat(approved.status()).isEqualTo(200);
        String v2 = imported.body().path("summary").path("id").asString();
        Response upgraded = install("wade.admin", v2);
        assertThat(upgraded.status()).as(upgraded.toString()).isEqualTo(201);
        assertThat(upgraded.body().path("previousVersion").asString()).isEqualTo("1.0.0");
        assertThat(upgraded.body().path("connections").toString()).contains("\"version\":2");
        // The catering-for-drafts view: the draft pins the 1.0.0 connection version.
        JsonNode after = api("wade.admin").get("/workspaces/{ws}/connector-packages/{id}", PURCHASING, v2).body();
        assertThat(after.path("summary").path("installStatus").asString()).isEqualTo("active");
        assertThat(after.path("whereUsed").toString()).contains("\"outdated\":true");
        JsonNode catalog = api("wade.admin").get("/workspaces/{ws}/connector-catalog", PURCHASING).body();
        JsonNode entry = null;
        for (JsonNode item : catalog.path("items")) {
            if (("com.orqix." + prefix).equals(item.path("packageId").asString())) {
                entry = item;
            }
        }
        assertThat(entry).isNotNull();
        assertThat(entry.path("latestVersion").asString()).isEqualTo("1.1.0");
        assertThat(entry.path("versions").size()).isEqualTo(2);
        assertThat(entry.path("installs").toString()).contains("\"version\":\"1.1.0\"").contains("\"status\":\"active\"");
        assertThat(entry.path("outdatedPins").toString()).contains("\"pinnedVersion\":\"1.0.0\"").contains("\"pinnedIn\":\"draft\"");
        // Retire 1.1.0: it cannot be installed anywhere again, and here its connections take no new use.
        JsonNode current = api("wade.admin").get("/workspaces/{ws}/connector-packages/{id}", PURCHASING, v2).body();
        Response noReason = api("wade.admin").post(Map.of("reason", " ", "expectedRevision",
                current.path("summary").path("revision").asInt()), "/workspaces/{ws}/connector-packages/{id}/retire",
                PURCHASING, v2);
        assertThat(noReason.status()).isEqualTo(422);
        Response retired = api("wade.admin").post(Map.of("reason", "Replaced by the vendor's 2.0 API", "expectedRevision",
                current.path("summary").path("revision").asInt()), "/workspaces/{ws}/connector-packages/{id}/retire",
                PURCHASING, v2);
        assertThat(retired.status()).as(retired.toString()).isEqualTo(200);
        assertThat(retired.body().path("summary").path("status").asString()).isEqualTo("retired");
        assertThat(retired.body().path("retireReason").asString()).contains("2.0");
        assertThat(jdbc.sql("SELECT archived_at IS NOT NULL FROM connection_asset WHERE workspace_id = :ws AND connection_key = :k")
                .param("ws", PURCHASING).param("k", prefix).query(Boolean.class).single()).isTrue();
        assertThat(api("wade.admin").get("/workspaces/{ws}/connector-palette?q=" + prefix, PURCHASING).body()
                .path("items").toString()).doesNotContain("\"connectionKey\":\"" + prefix + "\"");
        // Readers without connector permissions cannot see packages.
        assertThat(api("rhea.requester").get("/workspaces/{ws}/connector-packages", PURCHASING).status()).isEqualTo(403);
        assertThat(Stub.baseUrl()).isNotBlank();
    }
}
