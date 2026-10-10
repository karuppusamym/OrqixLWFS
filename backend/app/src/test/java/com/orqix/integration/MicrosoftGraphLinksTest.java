package com.orqix.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.orqix.integration.HttpExecutor.Target;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Approved links are resolved through Graph only; version history never silently truncates or follows foreign cursors. */
class MicrosoftGraphLinksTest {
    private static final String URL = "https://tenant.sharepoint.com/sites/Team/policy.txt?e=sharing-token";
    private final Json json = new Json(JsonMapper.builder().build());
    private WireMockServer server;
    private MicrosoftGraphDocuments documents;
    private ConnectionDefinition definition;
    private Target target;
    private String encoded;

    @BeforeEach
    void setup() throws Exception {
        server = new WireMockServer(0); server.start();
        String host = "localhost:" + server.port();
        var properties = new OrqixProperties(null, null, null,
                new OrqixProperties.Egress(List.of(host), List.of(host), List.of(host)), null, null, null, null, null);
        documents = new MicrosoftGraphDocuments(json, new EgressPolicy(properties));
        definition = ConnectionDefinition.parse(json.parse(Files.readString(Path.of("../../examples/connections/microsoft-documents-v3.json"))), "test");
        target = new Target(server.baseUrl() + "/v1.0", "bearerToken", null, "graph-token");
        encoded = "u!" + Base64.getUrlEncoder().withoutPadding().encodeToString(URL.getBytes(StandardCharsets.UTF_8));
        server.stubFor(get(urlPathEqualTo("/v1.0/shares/" + encoded + "/driveItem")).willReturn(okJson("""
                {"id":"item","name":"policy.txt","parentReference":{"driveId":"drive"},
                 "file":{"mimeType":"text/plain"},"eTag":"etag","size":14,
                 "webUrl":"https://tenant.sharepoint.com/sites/Team/policy.txt?sig=must-not-leak",
                 "@microsoft.graph.downloadUrl":"must-not-leak"}
                """)));
    }

    @AfterEach void stop() { server.stop(); }
    private ObjectNode resolve(ConnectionDefinition definition, String url) {
        return documents.read(target, definition.operation("resolveLink"), json.object().put("shareUrl", url), "graph-token", 5);
    }
    private ObjectNode versions() {
        return documents.read(target, definition.operation("listVersions"), json.object().put("driveId", "drive").put("itemId", "item"), "graph-token", 5);
    }
    private void versionFailure(String code) {
        assertThatThrownBy(this::versions).isInstanceOfSatisfying(MicrosoftGraphDocuments.Failure.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void resolvesThroughEncodedGraphRequestWithoutFetchingOrReturningSharingCredentials() {
        var result = resolve(definition, URL);
        assertThat(result.path("driveId").asString()).isEqualTo("drive");
        assertThat(result.path("itemId").asString()).isEqualTo("item");
        assertThat(result.path("webUrl").asString()).isEqualTo("https://tenant.sharepoint.com/sites/Team/policy.txt");
        assertThat(json.write(result)).doesNotContain("sharing-token", "must-not-leak", "graph-token", "downloadUrl");
        server.verify(getRequestedFor(urlPathEqualTo("/v1.0/shares/" + encoded + "/driveItem"))
                .withHeader("Authorization", equalTo("Bearer graph-token")).withoutHeader("Prefer"));
        assertThat(server.getAllServeEvents()).hasSize(1);
    }

    @Test
    void administratorUrlPolicyAndResolvedDrivePolicyBothApply() {
        assertThatThrownBy(() -> resolve(definition, "https://another.sharepoint.com/sites/Team/policy.txt"))
                .isInstanceOfSatisfying(MicrosoftGraphDocuments.Failure.class,
                        e -> assertThat(e.code()).isEqualTo("DOCUMENT_INPUT_INVALID"));
        server.stubFor(get(urlPathEqualTo("/v1.0/shares/" + encoded + "/driveItem"))
                .willReturn(okJson("{\"id\":\"item\",\"parentReference\":{\"driveId\":\"unapproved\"},\"file\":{}}")));
        assertThatThrownBy(() -> resolve(definition, URL)).isInstanceOfSatisfying(MicrosoftGraphDocuments.Failure.class,
                e -> assertThat(e.code()).isEqualTo("DOCUMENT_DRIVE_NOT_APPROVED"));
        server.verify(0, getRequestedFor(urlMatching(".*content.*")));
    }

    @Test
    void missingPolicyAndUnsafeUrlFormsFailBeforeGraphRequests() {
        ObjectNode permissive = (ObjectNode) definition.document().deepCopy();
        ObjectNode field = (ObjectNode) permissive.path("operations").path("resolveLink").path("requestSchema").path("properties").path("shareUrl");
        field.put("pattern", ".*");
        var approved = ConnectionDefinition.parse(permissive, "permissive");
        for (String url : List.of("https://evil.example/file", "http://tenant.sharepoint.com/sites/a",
                "https://user:secret@tenant.sharepoint.com/sites/a", "https://tenant.sharepoint.com.evil.example/sites/a",
                "https://tenant.sharepoint.com/sites/a#fragment", "https://tenant.sharepoint.com:444/sites/a")) {
            assertThatThrownBy(() -> resolve(approved, url)).isInstanceOfSatisfying(MicrosoftGraphDocuments.Failure.class,
                    e -> assertThat(e.code()).isEqualTo("DOCUMENT_LINK_INVALID"));
        }
        field.remove("pattern");
        assertThatThrownBy(() -> resolve(ConnectionDefinition.parse(permissive, "missing-policy"), URL))
                .isInstanceOfSatisfying(MicrosoftGraphDocuments.Failure.class,
                        e -> assertThat(e.code()).isEqualTo("DOCUMENT_LINK_POLICY_REQUIRED"));
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    void listsBoundedPagesAndDropsDownloadUrlsAndOtherVersionFields() {
        server.stubFor(get(urlPathEqualTo("/v1.0/drives/drive/items/item/versions"))
                .withQueryParam("$top", equalTo("100")).willReturn(okJson("""
                    {"value":[{"id":"2.0","size":20,"lastModifiedDateTime":"2026-02-01T00:00:00Z","@microsoft.graph.downloadUrl":"secret"}],
                     "@odata.nextLink":"%s/v1.0/drives/drive/items/item/versions?$skiptoken=page2"}
                    """.formatted(server.baseUrl()))));
        server.stubFor(get(urlPathEqualTo("/v1.0/drives/drive/items/item/versions"))
                .withQueryParam("$skiptoken", equalTo("page2")).willReturn(okJson("{\"value\":[{\"id\":\"1.0\",\"size\":14}]}")));
        ObjectNode result = versions();
        assertThat(result.path("versionCount").asInt()).isEqualTo(2);
        assertThat(result.path("hasMore").asBoolean()).isFalse();
        var entries = json.parse(result.path("versionsJson").asString());
        assertThat(entries.get(0).path("versionId").asString()).isEqualTo("2.0");
        assertThat(entries.get(0).path("newestListed").asBoolean()).isTrue();
        assertThat(entries.get(1).path("newestListed").asBoolean()).isFalse();
        assertThat(json.write(result)).doesNotContain("downloadUrl", "secret");
        server.verify(2, getRequestedFor(urlPathEqualTo("/v1.0/drives/drive/items/item/versions"))
                .withHeader("Authorization", equalTo("Bearer graph-token")));
    }

    @Test
    void excessVersionsAndLoopingPagingFailInsteadOfReturningPartialHistory() {
        var entries = json.object().putArray("value");
        for (int i = 0; i < 101; i++) { entries.addObject().put("id", i + ".0"); }
        server.stubFor(get(urlPathEqualTo("/v1.0/drives/drive/items/item/versions"))
                .willReturn(okJson(json.write(json.object().set("value", entries)))));
        versionFailure("DOCUMENT_VERSION_LIST_LIMIT_EXCEEDED");
        server.stubFor(get(urlPathEqualTo("/v1.0/drives/drive/items/item/versions"))
                .willReturn(okJson("{\"value\":[],\"@odata.nextLink\":\"" + server.baseUrl()
                        + "/v1.0/drives/drive/items/item/versions?$skiptoken=loop\"}")));
        versionFailure("DOCUMENT_VERSION_LIST_LIMIT_EXCEEDED");
    }

    @Test
    void foreignAuthorityWrongResourceAndDuplicateVersionIdsAreRejected() {
        for (String link : List.of("https://evil.example/versions", server.baseUrl() + "/v1.0/drives/drive/items/other/versions")) {
            server.stubFor(get(urlPathEqualTo("/v1.0/drives/drive/items/item/versions"))
                    .willReturn(okJson("{\"value\":[],\"@odata.nextLink\":\"" + link + "\"}")));
            versionFailure("DOCUMENT_VERSION_PAGING_REJECTED");
        }
        server.stubFor(get(urlPathEqualTo("/v1.0/drives/drive/items/item/versions"))
                .willReturn(okJson("{\"value\":[{\"id\":\"1.0\"},{\"id\":\"1.0\"}]}")));
        versionFailure("DOCUMENT_RESPONSE_INVALID");
    }
}
