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
import org.springframework.beans.factory.annotation.Autowired;
import com.orqix.integration.internal.DocumentSnapshots;

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
    private final DocumentSnapshots snapshots;
    private final DocumentExtraction extraction;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final SchemaRegistry schemas = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    @Autowired
    public MicrosoftGraphDocuments(Json json, EgressPolicy egress, DocumentSnapshots snapshots, DocumentExtraction extraction) {
        this.json = json; this.egress = egress; this.snapshots = snapshots; this.extraction = extraction;
    }
    public MicrosoftGraphDocuments(Json json, EgressPolicy egress) {
        this(json, egress, null, new DocumentExtraction());
    }

    public static final class Failure extends RuntimeException {
        private final String code;
        private final Integer status;
        private final Observation observation;
        public Failure(String code, Integer status, Observation observation) {
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
        return read(target, "microsoft-documents", operation, input, token, timeoutSeconds);
    }

    public ObjectNode read(Target target, String connectionKey, ConnectionDefinition.Operation operation, JsonNode input,
            String token, int timeoutSeconds) {
        if (!"bearerToken".equals(target.authType()) || token == null || token.isBlank()) {
            throw fail("DOCUMENT_AUTH_REQUIRED");
        }
        if (input == null || operation.requestSchema() == null
                || !schemas.getSchema(operation.requestSchema()).validate(input).isEmpty()) {
            throw fail("DOCUMENT_INPUT_INVALID");
        }
        String path = operation.path();
        if (!java.util.Set.of("/metadata", "/text", "/extract", "/pinLatest", "/extractSnapshot", "/resolveLink", "/versions", "/browseFolder").contains(path)
                || operation.effectClass() != ConnectionDefinition.EffectClass.READ_ONLY) {
            throw fail("DOCUMENT_OPERATION_INVALID");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
        if ("/resolveLink".equals(path)) { return resolveLink(target, operation, input, token, deadline); }
        String drive = segment(input.path("driveId").asString(""));
        if ("/browseFolder".equals(path)) return browseFolder(target, operation, input, drive, token, deadline);
        String item = segment(input.path("itemId").asString(""));
        String version = input.path("versionId").asString("");
        if (!version.isEmpty()) { version = segment(version); }
        if ("/versions".equals(path)) { return versions(target, drive, item, token, deadline); }
        if ("/extractSnapshot".equals(path)) {
            requireSnapshots(target);
            java.util.UUID id;
            try { id = java.util.UUID.fromString(input.path("snapshotId").asString()); }
            catch (RuntimeException e) { throw fail("DOCUMENT_INPUT_INVALID"); }
            var snapshot = snapshots.load(target.workspaceId(), connectionKey, id, drive, item);
            ObjectNode result = snapshot.metadata().deepCopy();
            return extracted(result, snapshot.content(), snapshot.contentType(), deadline);
        }
        boolean latest = "/pinLatest".equals(path), extract = "/extract".equals(path), text = "/text".equals(path);
        if ((text || extract) && version.isEmpty()) { throw fail("DOCUMENT_VERSION_REQUIRED"); }
        if (latest) {
            requireSnapshots(target);
            if (!version.isEmpty()) { throw fail("DOCUMENT_INPUT_INVALID"); }
        }
        String base = target.baseUrl().replaceAll("/+$", "");
        URI itemUrl = URI.create(base + "/drives/" + drive + "/items/" + item);
        check(itemUrl, false);
        JsonNode metadata = parse(send(itemUrl, token, deadline, "application/json"));
        if (!item.equals(metadata.path("id").asString()) || !metadata.path("file").isObject()) {
            throw fail("DOCUMENT_NOT_A_FILE");
        }
        JsonNode versionMetadata = version.isEmpty() ? metadata
                : parse(send(URI.create(itemUrl + "/versions/" + version), token, deadline, "application/json"));
        if (!version.isEmpty() && !version.equals(versionMetadata.path("id").asString())) { throw fail("DOCUMENT_VERSION_INVALID"); }
        ObjectNode result = json.object().put("driveId", drive).put("itemId", item).put("versionId", version)
                .put("name", metadata.path("name").asString(""))
                .put("webUrl", metadata.path("webUrl").asString(""))
                .put("eTag", metadata.path("eTag").asString(""))
                .put("size", versionMetadata.path("size").asLong(0))
                .put("lastModifiedDateTime", versionMetadata.path("lastModifiedDateTime").asString(""));
        if (!(text || extract || latest)) { return result; }
        String mime = metadata.path("file").path("mimeType").asString("").toLowerCase(Locale.ROOT);
        boolean plain = mime.startsWith("text/") || "application/json".equals(mime);
        if (text && !plain) { throw fail("DOCUMENT_CONTENT_TYPE_UNSUPPORTED"); }
        if (!plain) { DocumentExtractionWorker.type(mime); }
        int limit = text ? MAX_TEXT_BYTES : DocumentExtraction.MAX_FILE_BYTES;
        if (versionMetadata.path("size").asLong(-1) < 0 || versionMetadata.path("size").asLong() > limit) {
            throw fail("DOCUMENT_CONTENT_TOO_LARGE");
        }
        URI content = URI.create(itemUrl + (latest ? "/content" : "/versions/" + version + "/content"));
        HttpResponse<byte[]> download = download(content, token, deadline, limit);
        byte[] bytes = download.body();
        if (bytes == null || bytes.length > limit) { throw fail("DOCUMENT_CONTENT_TOO_LARGE"); }
        String contentType = download.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (plain) {
            if (!(contentType.startsWith("text/") || contentType.startsWith("application/json"))
                    || (contentType.contains("charset=") && !contentType.matches(".*charset=\"?utf-8\"?(?:;.*)?"))) {
                throw fail("DOCUMENT_CONTENT_TYPE_UNSUPPORTED");
            }
        } else if (!contentType.split(";", 2)[0].trim().equals(mime)
                && !contentType.startsWith("application/octet-stream")) { throw fail("DOCUMENT_CONTENT_TYPE_UNSUPPORTED"); }
        result.put("contentSha256", Json.sha256Hex(bytes));
        if (latest) {
            String etag = metadata.path("eTag").asString("");
            JsonNode after = parse(send(itemUrl, token, deadline, "application/json"));
            if (etag.isBlank() || !etag.equals(after.path("eTag").asString())
                    || !item.equals(after.path("id").asString()) || metadata.path("size").asLong(-1) != bytes.length) {
                throw fail("DOCUMENT_CHANGED_DURING_CAPTURE");
            }
            return snapshots.capture(target.workspaceId(), connectionKey, result, bytes, mime).metadata();
        }
        if (text) { return result.put("text", decodeText(bytes)); }
        return extracted(result, bytes, mime, deadline);
    }

    private ObjectNode resolveLink(Target target, ConnectionDefinition.Operation operation, JsonNode input,
            String token, long deadline) {
        JsonNode linkPolicy = operation.requestSchema().path("properties").path("shareUrl");
        JsonNode approvedDrives = operation.requestSchema().path("properties").path("driveId").path("enum");
        if (!(linkPolicy.has("pattern") || linkPolicy.path("enum").isArray())
                || !approvedDrives.isArray() || approvedDrives.isEmpty()) { throw fail("DOCUMENT_LINK_POLICY_REQUIRED"); }
        String url = input.path("shareUrl").asString("");
        URI share = sharingUrl(url);
        String encoded = "u!" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(share.toString().getBytes(StandardCharsets.UTF_8));
        String base = target.baseUrl().replaceAll("/+$", "");
        URI api = URI.create(base + "/shares/" + encoded
                + "/driveItem?$select=id,name,webUrl,parentReference,file,eTag,size");
        check(api, false);
        // Do not use Prefer:redeemSharingLink: link resolution must not grant durable permission to the service.
        JsonNode response = parse(send(api, token, deadline, "application/json"));
        JsonNode file = response.path("remoteItem").isObject() ? response.path("remoteItem") : response;
        String drive = segment(file.path("parentReference").path("driveId").asString(""));
        String item = segment(file.path("id").asString(""));
        boolean approved = false;
        for (JsonNode candidate : approvedDrives) { approved |= drive.equals(candidate.asString()); }
        if (!approved || (input.hasNonNull("driveId") && !drive.equals(input.path("driveId").asString()))) {
            throw fail("DOCUMENT_DRIVE_NOT_APPROVED");
        }
        if (!file.path("file").isObject()) { throw fail("DOCUMENT_NOT_A_FILE"); }
        String canonical = file.path("webUrl").asString("");
        // A returned canonical URL is only provenance; strip its query/fragment so signed links are never outputs.
        URI web = sharingUrl(canonical);
        try { canonical = new URI(web.getScheme(), null, web.getHost(), -1, web.getPath(), null, null).toString(); }
        catch (URISyntaxException e) { throw fail("DOCUMENT_RESPONSE_INVALID"); }
        return json.object().put("driveId", drive).put("itemId", item).put("name", file.path("name").asString(""))
                .put("webUrl", canonical).put("eTag", file.path("eTag").asString(""))
                .put("size", file.path("size").asLong(0));
    }

    private static URI sharingUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean microsoft = host.equals("onedrive.live.com") || host.equals("1drv.ms")
                    || host.matches("[a-z0-9][a-z0-9-]*\\.sharepoint\\.(?:com|us|de|cn)");
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !microsoft || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawFragment() != null
                    || uri.getRawPath() == null || uri.getRawPath().isBlank() || value.length() > 2000) {
                throw fail("DOCUMENT_LINK_INVALID");
            }
            return uri;
        } catch (IllegalArgumentException e) { throw fail("DOCUMENT_LINK_INVALID"); }
    }

    private ObjectNode versions(Target target, String drive, String item, String token, long deadline) {
        String base = target.baseUrl().replaceAll("/+$", "");
        URI origin = URI.create(base);
        String path = origin.getPath().replaceAll("/+$", "") + "/drives/" + drive + "/items/" + item + "/versions";
        URI next = URI.create(base + "/drives/" + drive + "/items/" + item
                + "/versions?$select=id,size,lastModifiedDateTime&$top=100");
        var entries = json.object().putArray("versions");
        java.util.Set<String> visited = new java.util.HashSet<>(), ids = new java.util.HashSet<>();
        int pages = 0;
        while (next != null) {
            if (++pages > 10 || !visited.add(next.toString())) { throw fail("DOCUMENT_VERSION_LIST_LIMIT_EXCEEDED"); }
            if (!origin.getScheme().equalsIgnoreCase(next.getScheme()) || !origin.getHost().equalsIgnoreCase(next.getHost())
                    || origin.getPort() != next.getPort() || !path.equals(next.getPath()) || next.getRawUserInfo() != null
                    || next.getRawFragment() != null) { throw fail("DOCUMENT_VERSION_PAGING_REJECTED"); }
            check(next, false);
            JsonNode page = parse(send(next, token, deadline, "application/json"));
            if (!page.path("value").isArray()) { throw fail("DOCUMENT_RESPONSE_INVALID"); }
            for (JsonNode entry : page.path("value")) {
                String id = segment(entry.path("id").asString(""));
                if (!ids.add(id)) { throw fail("DOCUMENT_RESPONSE_INVALID"); }
                if (entries.size() >= 100) { throw fail("DOCUMENT_VERSION_LIST_LIMIT_EXCEEDED"); }
                entries.addObject().put("versionId", id).put("size", entry.path("size").asLong(0))
                        .put("lastModifiedDateTime", entry.path("lastModifiedDateTime").asString(""))
                        .put("newestListed", entries.size() == 1);
            }
            JsonNode cursor = page.get("@odata.nextLink");
            if (cursor == null || cursor.isNull()) { next = null; }
            else {
                if (!cursor.isString() || cursor.asString().length() > 4000) { throw fail("DOCUMENT_VERSION_PAGING_REJECTED"); }
                try { next = URI.create(cursor.asString()); }
                catch (IllegalArgumentException e) { throw fail("DOCUMENT_VERSION_PAGING_REJECTED"); }
            }
        }
        String listing = json.write(entries);
        if (listing.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) { throw fail("DOCUMENT_VERSION_LIST_LIMIT_EXCEEDED"); }
        return json.object().put("driveId", drive).put("itemId", item).put("versionsJson", listing)
                .put("versionCount", entries.size()).put("hasMore", false);
    }

    /** Only direct files of explicitly granted folders; no search, recursive descent or caller-provided cursor. */
    private ObjectNode browseFolder(Target target, ConnectionDefinition.Operation operation, JsonNode input,
            String drive, String token, long deadline) {
        String folder = segment(input.path("folderId").asString(""));
        for (String key : java.util.List.of("driveId", "folderId")) {
            JsonNode allowed = operation.requestSchema().path("properties").path(key).path("enum");
            String selected = input.path(key).asString();
            boolean approved = false;
            if (allowed.isArray() && allowed.size() <= 100) for (JsonNode grant : allowed) approved |= selected.equals(grant.asString());
            if (!approved) throw fail("DOCUMENT_FOLDER_NOT_APPROVED");
        }
        String base = target.baseUrl().replaceAll("/+$", ""); URI origin = URI.create(base);
        String path = origin.getPath().replaceAll("/+$", "") + "/drives/" + drive + "/items/" + folder + "/children";
        URI next = URI.create(base + "/drives/" + drive + "/items/" + folder + "/children?$select=id,name,size,file,parentReference&$top=100");
        var files = json.object().putArray("files");
        java.util.Set<String> visited = new java.util.HashSet<>(), ids = new java.util.HashSet<>(); int pages = 0, count = 0;
        while (next != null) {
            if (++pages > 10 || !visited.add(next.toString())) throw fail("DOCUMENT_FOLDER_LIST_LIMIT_EXCEEDED");
            if (!origin.getScheme().equalsIgnoreCase(next.getScheme()) || !origin.getHost().equalsIgnoreCase(next.getHost())
                    || origin.getPort() != next.getPort() || !path.equals(next.getPath()) || next.getRawUserInfo() != null || next.getRawFragment() != null)
                throw fail("DOCUMENT_FOLDER_PAGING_REJECTED");
            check(next, false); JsonNode page = parse(send(next, token, deadline, "application/json"));
            if (!page.path("value").isArray()) throw fail("DOCUMENT_RESPONSE_INVALID");
            for (JsonNode entry : page.path("value")) {
                if (++count > 100) throw fail("DOCUMENT_FOLDER_LIST_LIMIT_EXCEEDED");
                String id = segment(entry.path("id").asString(""));
                if (!ids.add(id)) throw fail("DOCUMENT_RESPONSE_INVALID");
                if (!entry.path("file").isObject()) continue;
                if (!folder.equals(entry.path("parentReference").path("id").asString())
                        || !drive.equals(entry.path("parentReference").path("driveId").asString())) throw fail("DOCUMENT_RESPONSE_INVALID");
                String name = entry.path("name").asString(""); long size = entry.path("size").asLong(-1);
                if (name.isBlank() || name.length() > 256 || size < 0) throw fail("DOCUMENT_RESPONSE_INVALID");
                files.addObject().put("itemId", id).put("name", name).put("size", size)
                        .put("contentType", entry.path("file").path("mimeType").asString(""));
            }
            JsonNode cursor = page.get("@odata.nextLink");
            if (cursor == null || cursor.isNull()) next = null;
            else {
                if (!cursor.isString() || cursor.asString().length() > 4000) throw fail("DOCUMENT_FOLDER_PAGING_REJECTED");
                try { next = URI.create(cursor.asString()); } catch (IllegalArgumentException e) { throw fail("DOCUMENT_FOLDER_PAGING_REJECTED"); }
            }
        }
        String listing = json.write(files);
        if (listing.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) throw fail("DOCUMENT_FOLDER_LIST_LIMIT_EXCEEDED");
        return json.object().put("driveId", drive).put("folderId", folder).put("filesJson", listing).put("fileCount", files.size()).put("hasMore", false);
    }

    private void requireSnapshots(Target target) {
        if (snapshots == null || target.workspaceId() == null) { throw fail("DOCUMENT_WORKSPACE_REQUIRED"); }
    }

    private ObjectNode extracted(ObjectNode result, byte[] bytes, String mime, long deadline) {
        String text = mime.startsWith("text/") || mime.equals("application/json") ? decodeText(bytes)
                : extraction.extract(bytes, mime, deadline);
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) { throw fail("DOCUMENT_EXTRACTION_LIMIT_EXCEEDED"); }
        return result.put("text", text).put("contentSha256", Json.sha256Hex(bytes))
                .put("extractionFormat", mime).put("extractedTextSha256", Json.sha256Hex(text));
    }

    private String decodeText(byte[] bytes) {
        if (bytes.length > MAX_TEXT_BYTES) { throw fail("DOCUMENT_CONTENT_TOO_LARGE"); }
        try {
            String content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (content.indexOf('\0') >= 0) { throw fail("DOCUMENT_CONTENT_INVALID"); }
            return content;
        } catch (java.nio.charset.CharacterCodingException e) { throw fail("DOCUMENT_CONTENT_INVALID"); }
    }

    private HttpResponse<byte[]> download(URI uri, String token, long deadline, int limit) {
        HttpResponse<byte[]> response = sendRaw(uri, token, deadline, "*/*", limit);
        if (response.statusCode() == 302) {
            URI redirect;
            try {
                redirect = URI.create(response.headers().firstValue("Location").orElseThrow());
                check(redirect, true);
            } catch (IllegalArgumentException | java.util.NoSuchElementException e) { throw fail("DOCUMENT_REDIRECT_REJECTED"); }
            response = sendRaw(redirect, null, deadline, "*/*", limit);
        }
        success(response);
        return response;
    }

    private byte[] send(URI uri, String token, long deadline, String accept) {
        HttpResponse<byte[]> response = sendRaw(uri, token, deadline, accept);
        success(response);
        return response.body();
    }

    private HttpResponse<byte[]> sendRaw(URI uri, String token, long deadline, String accept) {
        return sendRaw(uri, token, deadline, accept, MAX_TEXT_BYTES);
    }

    private HttpResponse<byte[]> sendRaw(URI uri, String token, long deadline, String accept, int limit) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new Failure("DOCUMENT_TIMEOUT", null, Observation.MAYBE_APPLIED);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining))
                .header("Accept", accept).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        var future = client.sendAsync(request.build(), info -> new HttpExecutor.LimitedSubscriber(limit));
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
