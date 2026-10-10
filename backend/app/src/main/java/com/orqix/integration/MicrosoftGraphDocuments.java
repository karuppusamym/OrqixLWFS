package com.orqix.integration;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.orqix.integration.HttpExecutor.Observation;
import com.orqix.integration.HttpExecutor.Target;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Read-only, version-pinned SharePoint/OneDrive documents through approved Graph connection bindings. */
@Component
public final class MicrosoftGraphDocuments {
    public static final int MAX_TEXT_BYTES = 64 * 1024;
    private final Json json;
    private final EgressPolicy egress;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final SchemaRegistry schemas = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    public MicrosoftGraphDocuments(Json json, EgressPolicy egress) {
        this.json = json;
        this.egress = egress;
    }

    public static final class Failure extends RuntimeException {
        private final String code;
        private final Integer status;
        private final Observation observation;
        Failure(String code, Integer status, Observation observation) {
            super(code, null, false, false);
            this.code = code;
            this.status = status;
            this.observation = observation;
        }
        public String code() { return code; }
        public Integer status() { return status; }
        public Observation observation() { return observation; }
    }

    public ObjectNode read(Target target, ConnectionDefinition.Operation operation, JsonNode input,
            String token, int timeoutSeconds) {
        if (!"bearerToken".equals(target.authType()) || token == null || token.isBlank()) {
            throw fail("DOCUMENT_AUTH_REQUIRED");
        }
        if (input == null || operation.requestSchema() == null
                || !schemas.getSchema(operation.requestSchema()).validate(input).isEmpty()) {
            throw fail("DOCUMENT_INPUT_INVALID");
        }
        String drive = segment(input.path("driveId").asString(""));
        String item = segment(input.path("itemId").asString(""));
        String version = input.path("versionId").asString("");
        if (!version.isEmpty()) {
            version = segment(version);
        }
        boolean text = "/text".equals(operation.path());
        if ((!text && !"/metadata".equals(operation.path()))
                || operation.effectClass() != ConnectionDefinition.EffectClass.READ_ONLY) {
            throw fail("DOCUMENT_OPERATION_INVALID");
        }
        if (text && version.isEmpty()) {
            throw fail("DOCUMENT_VERSION_REQUIRED");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
        String base = target.baseUrl().replaceAll("/+$", "");
        URI itemUrl = URI.create(base + "/drives/" + drive + "/items/" + item);
        check(itemUrl, false);
        JsonNode metadata = parse(send(itemUrl, token, deadline, "application/json"));
        if (!item.equals(metadata.path("id").asString()) || !metadata.path("file").isObject()) {
            throw fail("DOCUMENT_NOT_A_FILE");
        }
        JsonNode versionMetadata = version.isEmpty() ? metadata
                : parse(send(URI.create(itemUrl + "/versions/" + version), token, deadline, "application/json"));
        if (!version.isEmpty() && !version.equals(versionMetadata.path("id").asString())) {
            throw fail("DOCUMENT_VERSION_INVALID");
        }
        ObjectNode result = json.object().put("driveId", drive).put("itemId", item).put("versionId", version)
                .put("name", metadata.path("name").asString(""))
                .put("webUrl", metadata.path("webUrl").asString(""))
                .put("eTag", metadata.path("eTag").asString(""))
                .put("size", versionMetadata.path("size").asLong(0))
                .put("lastModifiedDateTime", versionMetadata.path("lastModifiedDateTime").asString(""));
        if (!text) {
            return result;
        }
        String mime = metadata.path("file").path("mimeType").asString("").toLowerCase(Locale.ROOT);
        if (!(mime.startsWith("text/") || "application/json".equals(mime))) {
            throw fail("DOCUMENT_CONTENT_TYPE_UNSUPPORTED");
        }
        if (versionMetadata.path("size").asLong(-1) < 0 || versionMetadata.path("size").asLong() > MAX_TEXT_BYTES) {
            throw fail("DOCUMENT_CONTENT_TOO_LARGE");
        }
        HttpResponse<byte[]> download = sendRaw(URI.create(itemUrl + "/versions/" + version + "/content"),
                token, deadline, "text/plain, application/json");
        if (download.statusCode() == 302) {
            URI redirect;
            try {
                redirect = URI.create(download.headers().firstValue("Location").orElseThrow());
                check(redirect, true);
            } catch (IllegalArgumentException | java.util.NoSuchElementException e) {
                throw fail("DOCUMENT_REDIRECT_REJECTED");
            }
            // Preauthenticated Graph URLs are credentials themselves: never persist them or forward the Graph token.
            download = sendRaw(redirect, null, deadline, "text/plain, application/json");
        }
        success(download);
        byte[] bytes = download.body();
        if (bytes == null || bytes.length > MAX_TEXT_BYTES) {
            throw fail("DOCUMENT_CONTENT_TOO_LARGE");
        }
        String contentType = download.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (!(contentType.startsWith("text/") || contentType.startsWith("application/json"))
                || (contentType.contains("charset=") && !contentType.matches(".*charset=\"?utf-8\"?(?:;.*)?"))) {
            throw fail("DOCUMENT_CONTENT_TYPE_UNSUPPORTED");
        }
        try {
            String content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (content.indexOf('\0') >= 0) {
                throw fail("DOCUMENT_CONTENT_INVALID");
            }
            return result.put("text", content).put("contentSha256", Json.sha256Hex(bytes));
        } catch (java.nio.charset.CharacterCodingException e) {
            throw fail("DOCUMENT_CONTENT_INVALID");
        }
    }

    private byte[] send(URI uri, String token, long deadline, String accept) {
        HttpResponse<byte[]> response = sendRaw(uri, token, deadline, accept);
        success(response);
        return response.body();
    }

    private HttpResponse<byte[]> sendRaw(URI uri, String token, long deadline, String accept) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new Failure("DOCUMENT_TIMEOUT", null, Observation.MAYBE_APPLIED);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining))
                .header("Accept", accept).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        var future = client.sendAsync(request.build(), info -> new HttpExecutor.LimitedSubscriber(MAX_TEXT_BYTES));
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("DOCUMENT_INTERRUPTED", null, Observation.MAYBE_APPLIED);
        } catch (ExecutionException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpExecutor.ResponseTooLargeException) {
                    throw fail("DOCUMENT_CONTENT_TOO_LARGE");
                }
            }
            throw new Failure("DOCUMENT_UNAVAILABLE", null, Observation.MAYBE_APPLIED);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new Failure("DOCUMENT_TIMEOUT", null, Observation.MAYBE_APPLIED);
        } finally {
            if (!future.isDone()) { future.cancel(true); }
        }
    }

    private void check(URI uri, boolean download) {
        try {
            if (download && uri.getRawFragment() != null) {
                throw fail("DOCUMENT_REDIRECT_REJECTED");
            }
            // Only the authority participates in egress approval; signed download query strings stay private.
            URI authority = new URI(uri.getScheme(), uri.getRawUserInfo(), uri.getHost(), uri.getPort(), "/", null, null);
            egress.check(authority.toString());
        } catch (EgressPolicy.EgressDeniedException | URISyntaxException e) {
            throw fail(download ? "DOCUMENT_REDIRECT_REJECTED" : "EGRESS_NOT_ALLOWED");
        }
    }

    private JsonNode parse(byte[] bytes) {
        try {
            return json.parse(bytes);
        } catch (IllegalArgumentException e) {
            throw fail("DOCUMENT_RESPONSE_INVALID");
        }
    }

    private static void success(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status == 200) { return; }
        if (status == 429) { throw new Failure("DOCUMENT_RATE_LIMITED", status, Observation.DECLINED_429); }
        if (status == 408 || status >= 500) { throw new Failure("DOCUMENT_UNAVAILABLE", status, Observation.MAYBE_APPLIED); }
        throw new Failure(status == 404 ? "DOCUMENT_NOT_FOUND" : "DOCUMENT_ACCESS_REJECTED", status,
                status >= 300 && status < 400 ? Observation.REDIRECT : Observation.CLIENT_ERROR);
    }

    private static String segment(String value) {
        if (!value.matches("[A-Za-z0-9!._~-]{1,200}") || value.equals(".") || value.equals("..")) {
            throw fail("DOCUMENT_INPUT_INVALID");
        }
        return value;
    }

    private static Failure fail(String code) {
        return new Failure(code, null, Observation.CLIENT_ERROR);
    }
}
