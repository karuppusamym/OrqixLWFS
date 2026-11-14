package com.orqix.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;

import tools.jackson.databind.JsonNode;

/**
 * Helpers for connector package tests (docs/build/S133). wade.admin (purchasing administrators) manages connectors and
 * can also approve; paul.publisher approves. Package versions are organization assets in the shared test database, so
 * lifecycle tests use variants of the ServiceNow package with their own package id and connection keys.
 */
public abstract class PackageTestBase extends RuntimeTestBase {

    public static final Path SOURCE = ExamplePublisher.repoRoot().resolve("examples/packages/servicenow-itsm");
    public static final String ADMINS = "orqix-purchasing-admins";
    public static final String PUBLISHERS = "orqix-purchasing-publishers";

    @BeforeEach
    void packageGrants() throws Exception {
        grant("connector.manage", ADMINS);
        grant("connector.approve", ADMINS);
        grant("connector.approve", PUBLISHERS);
        grant("message.send", ADMINS);
        grant("workflow.start", ADMINS);
    }

    /** Grants this test created; removed afterwards so workspace-wide grants never leak into other tests. */
    private final java.util.List<String> createdGrants = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void revokePackageGrants() throws Exception {
        for (String id : createdGrants) {
            api("wade.admin").delete("/workspaces/{ws}/access/grants/{id}", PURCHASING, id);
        }
        createdGrants.clear();
    }

    protected void grant(String permission, String group) throws Exception {
        Response r = api("wade.admin").post(Map.of("permission", permission, "principal",
                Map.of("type", "idpGroup", "value", group)), "/workspaces/{ws}/access/grants", PURCHASING);
        assertThat(r.status()).as(r.toString()).isIn(201, 409);
        if (r.status() == 201) {
            createdGrants.add(r.body().path("id").asString());
        }
    }

    /** The ServiceNow package's files, optionally renamed and edited (paths to UTF-8 text). */
    protected Map<String, String> files() throws Exception {
        Map<String, String> files = new TreeMap<>();
        ExamplePublisher.sources(SOURCE).forEach((path, bytes) -> files.put(path, new String(bytes, StandardCharsets.UTF_8)));
        return files;
    }

    /**
     * A variant with its own package id and connection keys: {@code servicenow-itsm} becomes {@code <prefix>} and
     * {@code servicenow-catalog} becomes {@code <prefix>-cat} everywhere (manifest id, connection keys, references).
     */
    protected Map<String, String> variant(String prefix, String version) throws Exception {
        Map<String, String> files = new TreeMap<>();
        files().forEach((path, text) -> files.put(path.replace("servicenow-catalog", prefix + "-cat")
                .replace("servicenow-itsm", prefix), text.replace("servicenow-catalog", prefix + "-cat")
                .replace("servicenow-itsm", prefix).replace("\"version\": \"1.0.0\"", "\"version\": \"" + version + "\"")));
        return files;
    }

    protected static String prefix() {
        return "sn" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    protected byte[] signed(Map<String, String> files) {
        return ExamplePublisher.build(bytes(files));
    }

    protected static Map<String, byte[]> bytes(Map<String, String> files) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        files.forEach((path, text) -> out.put(path, text.getBytes(StandardCharsets.UTF_8)));
        return out;
    }

    protected Response importPackage(String user, byte[] archive, String unsignedReason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("archive", Base64.getEncoder().encodeToString(archive));
        if (unsignedReason != null) {
            body.put("unsignedReason", unsignedReason);
        }
        return api(user).post(body, "/workspaces/{ws}/connector-packages", PURCHASING);
    }

    protected Response decide(String user, JsonNode detail, String decision) throws Exception {
        JsonNode summary = detail.path("summary");
        return api(user).post(Map.of("decision", decision, "sha256", summary.path("sha256").asString(), "note",
                "Reviewed hosts, operations and triggers", "expectedRevision", summary.path("revision").asInt()),
                "/workspaces/{ws}/connector-packages/{id}/decision", PURCHASING, summary.path("id").asString());
    }

    protected Response install(String user, String id) throws Exception {
        return api(user).post(Map.of(), "/workspaces/{ws}/connector-packages/{id}/install", PURCHASING, id);
    }

    /** Imports (wade), approves (paul) and installs (wade) a package; returns its version id. */
    protected String importApproveInstall(byte[] archive) throws Exception {
        Response imported = importPackage("wade.admin", archive, null);
        assertThat(imported.status()).as(imported.toString()).isEqualTo(201);
        Response approved = decide("paul.publisher", imported.body(), "approve");
        assertThat(approved.status()).as(approved.toString()).isEqualTo(200);
        String id = imported.body().path("summary").path("id").asString();
        Response installed = install("wade.admin", id);
        assertThat(installed.status()).as(installed.toString()).isEqualTo(201);
        return id;
    }

    /**
     * The ServiceNow package itself (com.orqix.servicenow-itsm 1.0.0, the committed build), imported, approved and
     * installed in purchasing once per database.
     */
    protected String serviceNowInstalled() throws Exception {
        String id = jdbc.sql("SELECT id::text FROM connector_package_version WHERE package_id = 'com.orqix.servicenow-itsm'"
                + " AND version = '1.0.0'").query(String.class).optional().orElse(null);
        if (id == null) {
            byte[] archive = java.nio.file.Files.readAllBytes(ExamplePublisher.repoRoot()
                    .resolve("examples/packages/servicenow-itsm-1.0.0.orqpkg"));
            return importApproveInstall(archive);
        }
        boolean installed = jdbc.sql("SELECT count(*) FROM connector_package_install WHERE workspace_id = :ws"
                + " AND package_version_id = CAST(:id AS uuid) AND status = 'active'").param("ws", PURCHASING)
                .param("id", id).query(Integer.class).single() > 0;
        if (!installed) {
            Response r = install("wade.admin", id);
            assertThat(r.status()).as(r.toString()).isEqualTo(201);
        }
        return id;
    }

    /** Binds a package connection to the ServiceNow stub (a deployment development host). */
    protected void bindToStub(String key) throws Exception {
        Response bound = api("wade.admin").put(Map.of("baseUrl", Stub.baseUrl() + "/servicenow",
                "auth", Map.of("type", "none"), "enabled", true), null,
                "/workspaces/{ws}/connections/{key}/binding", PURCHASING, key);
        if (bound.status() == 412 || bound.status() == 428) {
            String etag = "\"r" + jdbc.sql("SELECT revision FROM connection_binding WHERE workspace_id = :ws"
                    + " AND connection_key = :key").param("ws", PURCHASING).param("key", key).query(Integer.class).single() + "\"";
            bound = api("wade.admin").put(Map.of("baseUrl", Stub.baseUrl() + "/servicenow",
                    "auth", Map.of("type", "none"), "enabled", true), etag,
                    "/workspaces/{ws}/connections/{key}/binding", PURCHASING, key);
        }
        assertThat(bound.status()).as(bound.toString()).isEqualTo(200);
    }

    /** Edits one JSON file of a variant. */
    protected void editJson(Map<String, String> files, String path, Consumer<tools.jackson.databind.node.ObjectNode> edit) {
        tools.jackson.databind.node.ObjectNode node = (tools.jackson.databind.node.ObjectNode) json.parse(files.get(path));
        edit.accept(node);
        files.put(path, json.write(node));
    }

    protected static List<String> codes(Response r) {
        List<String> codes = new java.util.ArrayList<>();
        r.body().path("fieldErrors").forEach(e -> codes.add(e.path("code").asString()));
        return codes;
    }
}
