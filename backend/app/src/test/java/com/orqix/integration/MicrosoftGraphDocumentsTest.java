package com.orqix.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.orqix.integration.HttpExecutor.Target;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real HTTP contract including preauthenticated redirects, credentials, version pins, and bounded text. */
class MicrosoftGraphDocumentsTest {
    private final Json json = new Json(JsonMapper.builder().build());
    private WireMockServer server;
    private MicrosoftGraphDocuments documents;
    private ConnectionDefinition definition;
    private Target target;

    @Test void browseListsOnlyDirectFilesFromExplicitFolderGrantWithoutSignedUrls() throws Exception {
        var v2 = ConnectionDefinition.parse(json.parse(Files.readString(Path.of("../../examples/connections/microsoft-documents-v4.json"))), "test-v2");
        var input = json.object().put("driveId", "approved-drive").put("folderId", "approved-folder");
        server.stubFor(get(urlPathEqualTo("/v1.0/drives/approved-drive/items/approved-folder/children"))
                .willReturn(okJson("""
                    {"value":[{"id":"item","name":"Policy.docx","size":100,"file":{"mimeType":"application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
                      "parentReference":{"id":"approved-folder","driveId":"approved-drive"},"@microsoft.graph.downloadUrl":"secret-download-url"},
                      {"id":"subfolder","name":"Nested","folder":{}}]}
                    """)));
        var result = documents.read(target, v2.operation("browseFolder"), input, "token", 5);
        assertThat(result.path("fileCount").asInt()).isEqualTo(1);
        assertThat(result.path("hasMore").asBoolean()).isFalse();
        assertThat(result.toString()).contains("Policy.docx").doesNotContain("secret-download-url", "Nested");
        assertThatThrownBy(() -> documents.read(target, v2.operation("browseFolder"), input.deepCopy().put("folderId", "unapproved"), "token", 5))
                .isInstanceOf(MicrosoftGraphDocuments.Failure.class);
        server.verify(1, getRequestedFor(urlPathEqualTo("/v1.0/drives/approved-drive/items/approved-folder/children")));
    }

    @Test void browseRejectsCrossFolderPagingWithoutFollowingIt() throws Exception {
        var v2 = ConnectionDefinition.parse(json.parse(Files.readString(Path.of("../../examples/connections/microsoft-documents-v4.json"))), "test-v2");
        server.stubFor(get(urlPathEqualTo("/v1.0/drives/approved-drive/items/approved-folder/children"))
                .willReturn(okJson("{\"value\":[],\"@odata.nextLink\":\"" + server.baseUrl() + "/v1.0/drives/approved-drive/items/private/children\"}")));
        assertThatThrownBy(() -> documents.read(target, v2.operation("browseFolder"), json.object().put("driveId", "approved-drive").put("folderId", "approved-folder"), "token", 5))
                .isInstanceOf(MicrosoftGraphDocuments.Failure.class).satisfies(e -> assertThat(((MicrosoftGraphDocuments.Failure) e).code()).isEqualTo("DOCUMENT_FOLDER_PAGING_REJECTED"));
        server.verify(0, getRequestedFor(urlPathEqualTo("/v1.0/drives/approved-drive/items/private/children")));
    }

    @BeforeEach
    void setup() throws Exception {
        server = new WireMockServer(0);
        server.start();
        String host = "localhost:" + server.port();
        var properties = new OrqixProperties(null, null, null,
                new OrqixProperties.Egress(List.of(host), List.of(host), List.of(host)),
                null, null, null, null, null);
        documents = new MicrosoftGraphDocuments(json, new EgressPolicy(properties));
        definition = ConnectionDefinition.parse(json.parse(Files.readString(
                Path.of("../../examples/connections/microsoft-documents-v1.json"))), "test");
        target = new Target(server.baseUrl() + "/v1.0", "bearerToken", null, "graph-token");
        server.stubFor(get(urlEqualTo("/v1.0/drives/drive/items/item")).willReturn(okJson("""
                {"id":"item","name":"policy.txt","webUrl":"https://tenant.sharepoint.com/policy.txt",
                 "eTag":"current-tag","size":100,"file":{"mimeType":"text/plain"},
                 "@microsoft.graph.downloadUrl":"secret-url-must-not-be-returned"}
                """)));
        server.stubFor(get(urlEqualTo("/v1.0/drives/drive/items/item/versions/1.0"))
                .willReturn(okJson("{\"id\":\"1.0\",\"size\":14,\"lastModifiedDateTime\":\"2026-01-01T00:00:00Z\"}")));
        server.stubFor(get(urlEqualTo("/v1.0/drives/drive/items/item/versions/1.0/content"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", server.baseUrl() + "/download?sig=secret")));
        server.stubFor(get(urlEqualTo("/download?sig=secret"))
                .willReturn(ok("Approved text.").withHeader("Content-Type", "text/plain; charset=utf-8")));
    }

    @AfterEach
    void stop() { server.stop(); }

    private ObjectNode input() { return json.object().put("driveId", "drive").put("itemId", "item").put("versionId", "1.0"); }
    private ObjectNode read() { return documents.read(target, definition.operation("readText"), input(), "graph-token", 5); }
    private void failure(String code) {
        assertThatThrownBy(this::read).isInstanceOfSatisfying(MicrosoftGraphDocuments.Failure.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void readsPinnedTextAndDropsAuthorizationAtTheDownloadRedirect() {
        ObjectNode result = read();
        assertThat(result.path("text").asString()).isEqualTo("Approved text.");
        assertThat(result.path("versionId").asString()).isEqualTo("1.0");
        assertThat(result.path("contentSha256").asString()).isEqualTo(Json.sha256Hex("Approved text."));
        assertThat(json.write(result)).doesNotContain("sig=", "secret-url", "graph-token");
        server.verify(3, getRequestedFor(urlMatching("/v1.0/.*")).withHeader("Authorization", equalTo("Bearer graph-token")));
        server.verify(getRequestedFor(urlEqualTo("/download?sig=secret")).withoutHeader("Authorization"));
        server.verify(0, getRequestedFor(urlEqualTo("/v1.0/drives/drive/items/item/content")));
    }

    @Test
    void validatesAndMapsTheAdapterResultThroughTheExistingEffectExecutor() {
        var executor = new HttpExecutor(json, null, documents);
        var result = executor.call(target, definition, definition.operation("readText"), input(),
                Map.of("documentText", "/text", "documentVersion", "/versionId", "documentHash", "/contentSha256"),
                "effect-test", 5, "graph-token");
        assertThat(result.outcome()).isEqualTo(HttpExecutor.Outcome.SUCCEEDED);
        assertThat(result.result().path("documentText").asString()).isEqualTo("Approved text.");
        assertThat(result.result().path("documentVersion").asString()).isEqualTo("1.0");
    }

    @Test
    void metadataDoesNotLeakEphemeralDownloadUrls() {
        var result = documents.read(target, definition.operation("getMetadata"), input(), "graph-token", 5);
        assertThat(result.path("name").asString()).isEqualTo("policy.txt");
        assertThat(result.path("size").asInt()).isEqualTo(14);
        assertThat(result.has("text")).isFalse();
        assertThat(json.write(result)).doesNotContain("downloadUrl", "secret-url");
    }

    @Test
    void missingVersionDoesNotFallBackToLatest() {
        server.stubFor(get(urlEqualTo("/v1.0/drives/drive/items/item/versions/1.0")).willReturn(notFound()));
        failure("DOCUMENT_NOT_FOUND");
        server.verify(0, getRequestedFor(urlMatching(".*content")));
    }

    @Test
    void rejectsMissingPinAndPathTraversalBeforeSending() {
        for (ObjectNode invalid : List.of(input().put("versionId", ""), input().put("itemId", "../secret"))) {
            assertThatThrownBy(() -> documents.read(target, definition.operation("readText"), invalid, "graph-token", 5))
                    .isInstanceOf(MicrosoftGraphDocuments.Failure.class);
        }
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    void rejectsUnapprovedRedirectHostsAndCredentialsInUrls() {
        for (String url : List.of("https://evil.example/file?sig=secret", "http://user:secret@localhost:" + server.port() + "/file")) {
            server.stubFor(get(urlEqualTo("/v1.0/drives/drive/items/item/versions/1.0/content"))
                    .willReturn(aResponse().withStatus(302).withHeader("Location", url)));
            failure("DOCUMENT_REDIRECT_REJECTED");
        }
    }

    @Test
    void secondRedirectIsNotFollowed() {
        server.stubFor(get(urlEqualTo("/download?sig=secret"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", server.baseUrl() + "/another")));
        failure("DOCUMENT_ACCESS_REJECTED");
        server.verify(0, getRequestedFor(urlEqualTo("/another")));
    }

    @Test
    void officeAndBinaryFilesAreExplicitlyUnsupported() {
        server.stubFor(get(urlEqualTo("/v1.0/drives/drive/items/item"))
                .willReturn(okJson("{\"id\":\"item\",\"file\":{\"mimeType\":\"application/pdf\"}}")));
        failure("DOCUMENT_CONTENT_TYPE_UNSUPPORTED");
        server.verify(0, getRequestedFor(urlMatching(".*content")));
    }

    @Test
    void refusesUnsupportedCharsetAndMalformedUtf8() {
        server.stubFor(get(urlEqualTo("/download?sig=secret"))
                .willReturn(ok("text").withHeader("Content-Type", "text/plain; charset=iso-8859-1")));
        failure("DOCUMENT_CONTENT_TYPE_UNSUPPORTED");
        server.stubFor(get(urlEqualTo("/download?sig=secret"))
                .willReturn(aResponse().withBody(new byte[] {(byte) 0xc3, 0x28}).withHeader("Content-Type", "text/plain")));
        failure("DOCUMENT_CONTENT_INVALID");
    }

    @Test
    void enforcesContentSizeAndPropagatesPermissionDenialWithoutBodies() {
        server.stubFor(get(urlEqualTo("/v1.0/drives/drive/items/item/versions/1.0"))
                .willReturn(okJson("{\"id\":\"1.0\",\"size\":65537}")));
        failure("DOCUMENT_CONTENT_TOO_LARGE");
        server.stubFor(get(urlEqualTo("/v1.0/drives/drive/items/item"))
                .willReturn(aResponse().withStatus(403).withBody("tenant-secret-diagnostic")));
        failure("DOCUMENT_ACCESS_REJECTED");
    }

    @Test
    void capsStreamingContentEvenWhenMetadataUnderstatesItsSize() {
        server.stubFor(get(urlEqualTo("/download?sig=secret"))
                .willReturn(ok("x".repeat(65537)).withHeader("Content-Type", "text/plain")));
        failure("DOCUMENT_CONTENT_TOO_LARGE");
    }

    @Test
    void bodyLimitClassificationSurvivesASynchronousCancellationCallback() {
        var subscriber = new HttpExecutor.LimitedSubscriber(1);
        subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            @Override public void request(long count) { }
            @Override public void cancel() { subscriber.onError(new java.io.IOException("subscription cancelled")); }
        });
        subscriber.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[] {1, 2})));
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
                .hasCauseInstanceOf(HttpExecutor.ResponseTooLargeException.class);
    }

    @Test
    void honorsAnAdministrativelyRestrictedDriveEnumBeforeSending() {
        ObjectNode restricted = (ObjectNode) definition.document().deepCopy();
        ((ObjectNode) restricted.path("operations").path("readText").path("requestSchema")
                .path("properties").path("driveId")).set("enum", json.parse("[\"approved-drive\"]"));
        var operation = ConnectionDefinition.parse(restricted, "restricted").operation("readText");
        assertThatThrownBy(() -> documents.read(target, operation, input(), "graph-token", 5))
                .isInstanceOfSatisfying(MicrosoftGraphDocuments.Failure.class,
                        e -> assertThat(e.code()).isEqualTo("DOCUMENT_INPUT_INVALID"));
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    void rateLimitIsRetryableAndPermissionDenialIsPermanentInTheEffectJournal() {
        var executor = new HttpExecutor(json, null, documents);
        for (int status : List.of(429, 403)) {
            server.stubFor(get(urlEqualTo("/v1.0/drives/drive/items/item"))
                    .willReturn(aResponse().withStatus(status).withBody("private response")));
            var result = executor.call(target, definition, definition.operation("readText"), input(), Map.of(),
                    "effect-test", 5, "graph-token");
            assertThat(result.outcome()).isEqualTo(status == 429 ? HttpExecutor.Outcome.RETRYABLE : HttpExecutor.Outcome.PERMANENT);
            assertThat(result.detail()).doesNotContain("private response", "graph-token");
        }
    }
}
