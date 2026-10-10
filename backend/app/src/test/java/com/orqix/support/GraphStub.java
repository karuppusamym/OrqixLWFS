package com.orqix.support;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;

/** Fixed document contract on the shared, approved WireMock origin; never contacts Microsoft. */
public final class GraphStub {
    private static volatile String currentText = "Approved text.";
    private static volatile int currentVersion = 1;
    private static volatile boolean changeAfterDownload;
    private static final java.util.concurrent.atomic.AtomicInteger REQUESTS = new java.util.concurrent.atomic.AtomicInteger();
    private GraphStub() { }
    public static void reset() { currentText = "Approved text."; currentVersion = 1; changeAfterDownload = false; REQUESTS.set(0); }
    public static void current(String text) { currentText = text; currentVersion++; }
    public static void race() { changeAfterDownload = true; }
    public static int requests() { return REQUESTS.get(); }
    public static ResponseDefinition handle(Request request) {
        REQUESTS.incrementAndGet();
        String path = request.getUrl();
        if (path.equals("/graph/downloadCurrent?sig=stub-only")) {
            if (request.getHeader("Authorization") != null) { return response(403, "text/plain", "Token leaked"); }
            String content = currentText;
            if (changeAfterDownload) { current("Changed during capture."); changeAfterDownload = false; }
            return response(200, "text/plain; charset=utf-8", content);
        }
        if (path.equals("/graph/download?sig=stub-only")) {
            if (request.getHeader("Authorization") != null) {
                return response(403, "text/plain", "Token leaked");
            }
            return response(200, "text/plain; charset=utf-8", "Approved text.");
        }
        if (!"Bearer graph-stub-only".equals(request.getHeader("Authorization"))) {
            return response(401, "application/json", "{}");
        }
        return switch (path) {
            case "/graph/drives/drive/items/item" -> response(200, "application/json", """
                    {"id":"item","name":"policy.txt","file":{"mimeType":"text/plain"},
                     "webUrl":"https://tenant.sharepoint.com/policy.txt","eTag":"current-%s","size":%s}
                    """.formatted(currentVersion, currentText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
            case "/graph/drives/drive/items/item/content" -> new ResponseDefinitionBuilder().withStatus(302)
                    .withHeader("Location", Stub.baseUrl() + "/graph/downloadCurrent?sig=stub-only").build();
            case "/graph/drives/drive/items/item/versions/1.0" -> response(200, "application/json",
                    "{\"id\":\"1.0\",\"size\":14,\"lastModifiedDateTime\":\"2026-01-01T00:00:00Z\"}");
            case "/graph/drives/drive/items/item/versions/1.0/content" -> new ResponseDefinitionBuilder()
                    .withStatus(302).withHeader("Location", Stub.baseUrl() + "/graph/download?sig=stub-only").build();
            default -> response(404, "application/json", "{}");
        };
    }
    private static ResponseDefinition response(int status, String type, String body) {
        return new ResponseDefinitionBuilder().withStatus(status).withHeader("Content-Type", type).withBody(body).build();
    }
}
