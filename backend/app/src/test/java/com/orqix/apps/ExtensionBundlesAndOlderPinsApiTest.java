package com.orqix.apps;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * S116: multi-file extension bundles (files hashed at submission, served only to the frame through a short-lived
 * signed path, module imports inside the bundle only) and the read-only listing of older pins in the migration review.
 */
class ExtensionBundlesAndOlderPinsApiTest extends PageTestBase {
    private static final String EXT = "/workspaces/{ws}/ui-extensions";
    private static final String MAIN = "import { label } from './util.js';\norqix.onData(function (data) { document.getElementById('root').textContent = label(data); });\n";
    private static final String UTIL = "export function label(data) { return 'Request: ' + data['task.requestTitle']; }\n";

    private static Map<String, Object> file(String path, String content) {
        return Map.of("path", path, "content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void multiFileBundlesAreHashedApprovedAndServedOnlyThroughTheFramesSignedPath() throws Exception {
        String id = api("dana.designer").post(Map.of("name", unique("bundle")), EXT, PURCHASING).body().path("id").asString();
        Map<String, Object> capabilities = Map.of("bindings", List.of("task.requestTitle"), "actions", List.of());
        String versions = EXT + "/{id}/versions";
        assertThat(api("dana.designer").post(Map.of("source", "", "capabilities", capabilities, "height", 120, "entry", "main.js",
                "files", List.of(file("main.js", MAIN), file("logo.svg", "<svg/>"))), versions, PURCHASING, id).status()).isEqualTo(422);
        assertThat(api("dana.designer").post(Map.of("source", "", "capabilities", capabilities, "height", 120, "entry", "other.js",
                "files", List.of(file("main.js", MAIN))), versions, PURCHASING, id).status()).isEqualTo(422);
        assertThat(api("dana.designer").post(Map.of("source", "", "capabilities", capabilities, "height", 120), versions, PURCHASING, id).status())
                .isEqualTo(422);
        var submitted = api("dana.designer").post(Map.of("source", "", "capabilities", capabilities, "height", 120, "entry", "main.js",
                "files", List.of(file("main.js", MAIN), file("util.js", UTIL), file("theme.css", "#root{font-weight:600}"))), versions, PURCHASING, id);
        assertThat(submitted.status()).as(submitted.toString()).isEqualTo(201);
        assertThat(submitted.body().path("entry").asString()).isEqualTo("main.js");
        assertThat(submitted.body().path("files")).hasSize(3);
        assertThat(submitted.body().path("files").get(0).path("path").asString()).isEqualTo("main.js");
        String mainSha = submitted.body().path("files").get(0).path("sha256").asString();
        assertThat(api("wade.admin").post(Map.of("decision", "approve", "note", "Reviewed all three files", "expectedStatus", "pending"),
                versions + "/1/decision", PURCHASING, id).status()).isEqualTo(200);

        var frame = api("mani.manager").get(versions + "/1/frame", PURCHASING, id);
        assertThat(frame.status()).isEqualTo(200);
        String html = new String(frame.bytes(), StandardCharsets.UTF_8);
        Matcher entry = Pattern.compile("integrity=\"sha256-([^\"]+)\" src=\"([^\"]+)main\\.js\"").matcher(html);
        assertThat(entry.find()).as(html).isTrue();
        assertThat(entry.group(1)).isEqualTo(Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(mainSha)));
        String prefix = entry.group(2);
        String policy = frame.header("Content-Security-Policy");
        assertThat(policy).contains("script-src 'sha256-").contains(" " + prefix).contains("connect-src 'none'").contains("sandbox allow-scripts");
        assertThat(html).contains("type=\"module\"").doesNotContain(UTIL);

        // The frame's own requests carry no session: the signed path alone opens the version's files.
        String path = prefix.substring(prefix.indexOf("/api/v1") + "/api/v1".length());
        var util = api(null).send(MockMvcRequestBuilders.get("/api/v1" + path + "util.js"));
        assertThat(util.status()).as(util.toString()).isEqualTo(200);
        assertThat(util.header("Content-Type")).startsWith("text/javascript");
        assertThat(util.header("Access-Control-Allow-Origin")).isEqualTo("*");
        assertThat(new String(util.bytes(), StandardCharsets.UTF_8)).isEqualTo(UTIL);
        String forged = path.replaceAll("[.][0-9a-f]{64}/", "." + "0".repeat(64) + "/");
        assertThat(api(null).send(MockMvcRequestBuilders.get("/api/v1" + forged + "util.js")).status()).isEqualTo(404);
        assertThat(api(null).send(MockMvcRequestBuilders.get("/api/v1" + path + "missing.js")).status()).isEqualTo(404);

        assertThat(api("wade.admin").post(Map.of("decision", "revoke", "note", "Withdrawn", "expectedStatus", "approved"),
                versions + "/1/decision", PURCHASING, id).status()).isEqualTo(200);
        assertThat(api(null).send(MockMvcRequestBuilders.get("/api/v1" + path + "util.js")).status()).isEqualTo(404);
    }

    @Test
    void olderCaseVersionsAndInactiveReleasesThatStillPinAViewAreListedReadOnly() throws Exception {
        // Workflow: release 1 pins view version 2 (through a reviewed migration), release 2 pins version 3.
        UUID workflow = publishPurchase(PURCHASING, null);
        Pin form = publishedView(PinMigrationsApiTest.formView("Purchase"));
        ObjectNode draft = (ObjectNode) api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflow).body().path("definition");
        ((ObjectNode) draft.path("inputUi")).putObject("viewRef").put("id", form.id().toString()).put("version", 1);
        assertThat(api("dana.designer").put(Map.of("definition", draft), "\"r2\"", "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, workflow).status()).isEqualTo(200);
        assertThat(republish(form.id(), PinMigrationsApiTest.formView("Purchase details"), 1)).isEqualTo(2);
        migrate(form.id(), 2, "workflow:" + workflow);
        publishWorkflow(workflow, 4);
        assertThat(republish(form.id(), PinMigrationsApiTest.formView("Purchase lines"), 2)).isEqualTo(3);
        migrate(form.id(), 3, "workflow:" + workflow);
        publishWorkflow(workflow, 5);
        JsonNode plan = api("dana.designer").get(VIEWS + "/{id}/pins?targetVersion=3", PURCHASING, form.id()).body();
        JsonNode old = null;
        for (JsonNode item : plan.path("items")) if (item.path("key").asString().startsWith("oldRelease:")) old = item;
        assertThat(old).as(plan.toString()).isNotNull();
        assertThat(old.path("status").asString()).isEqualTo("readOnly");
        assertThat(old.path("location").asString()).contains("not active");
        assertThat(old.path("pinnedVersions").get(0).asInt()).isEqualTo(2);

        // Case: version 1 of a definition pins the view; version 2 (newest) too. Version 1 is listed read-only.
        List<Map<String, Object>> fields = List.of(Map.of("key", "summary", "label", "Summary", "type", "text", "required", true, "maxLength", 80));
        var created = api("dana.designer").post(Map.of("name", unique("case-view"), "definition", PinMigrationsApiTest.caseView("Details", true)), VIEWS, SANDBOX);
        UUID view = UUID.fromString(created.body().path("id").asString());
        assertThat(api("dana.designer").post(Map.of("expectedRevision", 1), VIEWS + "/{id}/publish", SANDBOX, view).status()).isEqualTo(200);
        String key = "oldpins" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String xml;
        try (var input = getClass().getResourceAsStream("/cases/manual-required.cmmn")) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("lifecycleCase", key);
        }
        String first = null;
        for (int i = 0; i < 2; i++) {
            var definition = api("dana.designer").post(Map.of("key", key, "name", "Old pins", "sourceXml", xml, "dataFields", fields,
                    "viewRef", Map.of("id", view.toString(), "version", 1)), "/workspaces/{ws}/cases/definitions", SANDBOX);
            assertThat(definition.status()).as(definition.toString()).isEqualTo(201);
            if (first == null) first = definition.body().path("id").asString();
        }
        JsonNode cases = api("dana.designer").get(VIEWS + "/{id}/pins", SANDBOX, view).body();
        JsonNode older = null;
        for (JsonNode item : cases.path("items")) if (item.path("key").asString().equals("caseVersion:" + first)) older = item;
        assertThat(older).as(cases.toString()).isNotNull();
        assertThat(older.path("holderType").asString()).isEqualTo("caseVersion");
        assertThat(older.path("status").asString()).isEqualTo("readOnly");
    }

    private void migrate(UUID view, int target, String key) throws Exception {
        JsonNode plan = api("dana.designer").get(VIEWS + "/{id}/pins?targetVersion=" + target, PURCHASING, view).body();
        var applied = api("dana.designer").post(Map.of("targetVersion", target, "planHash", plan.path("planHash").asString(),
                "items", List.of(key), "reason", "Move to " + target), VIEWS + "/{id}/pin-migrations", PURCHASING, view);
        assertThat(applied.status()).as(applied.toString()).isEqualTo(200);
    }

    private void publishWorkflow(UUID workflow, int revision) throws Exception {
        var released = api("paul.publisher").post(Map.of("draftRevision", revision, "acknowledgeWarnings", true), UUID.randomUUID().toString(),
                "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, workflow);
        assertThat(released.status()).as(released.toString()).isEqualTo(201);
    }

    private int republish(UUID view, Map<String, Object> definition, int revision) throws Exception {
        var saved = api("dana.designer").put(Map.of("expectedRevision", revision, "name", unique("view"), "definition", definition),
                null, VIEWS + "/{id}/draft", PURCHASING, view);
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        return publish(view, revision + 1);
    }
}
