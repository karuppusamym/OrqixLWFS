package com.orqix.ai.ocr;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.orqix.integration.EgressPolicy;
import com.orqix.integration.OcrException;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * The one HTTP path of the cloud OCR adapters: JDK client, redirects never followed, every address checked against
 * the deployment's egress rules before it is called (the Azure polling address included), an 8 MiB answer limit, and
 * failures classified by status without bodies.
 */
final class OcrHttp {

    private static final Logger log = LoggerFactory.getLogger(OcrHttp.class);
    static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    /** A JSON answer with the headers the adapters read. */
    record Answer(int status, JsonNode body, Map<String, java.util.List<String>> headers) {
        Optional<String> header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst()
                    .flatMap(e -> e.getValue().stream().findFirst());
        }
    }

    private final Json json;
    private final EgressPolicy egress;
    private final HttpClient http;

    OcrHttp(Json json, EgressPolicy egress) {
        this.json = json;
        this.egress = egress;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    Json json() {
        return json;
    }

    Answer send(String what, String method, URI uri, Map<String, String> headers, byte[] body, Duration timeout) {
        try {
            egress.check(uri.getScheme() + "://" + uri.getRawAuthority());
        } catch (EgressPolicy.EgressDeniedException e) {
            throw new OcrException(OcrException.Kind.EGRESS_DENIED, 0, what + ": egress " + e.denial());
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new OcrException(OcrException.Kind.UNAVAILABLE, 0, what + ": deadline");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json");
        headers.forEach(builder::header);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<InputStream> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new OcrException(OcrException.Kind.UNAVAILABLE, 0, what + ": timeout");
        } catch (IOException e) {
            throw new OcrException(OcrException.Kind.UNAVAILABLE, 0, what + ": " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException(OcrException.Kind.UNAVAILABLE, 0, what + ": interrupted");
        }
        int status = response.statusCode();
        byte[] bytes;
        try (InputStream in = response.body()) {
            bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
        } catch (IOException e) {
            throw new OcrException(OcrException.Kind.UNAVAILABLE, status, what + ": unreadable response");
        }
        if (status < 200 || status > 299) {
            OcrException failure = new OcrException(OcrException.classify(status), status, what + ": HTTP " + status);
            log.warn("OCR provider call failed: {} (kind {})", failure.getMessage(), failure.kind());
            throw failure;
        }
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new OcrException(OcrException.Kind.UNAVAILABLE, status, what + ": response too large");
        }
        JsonNode parsed;
        if (bytes.length == 0) {
            parsed = json.object();
        } else {
            try {
                parsed = json.parse(bytes);
            } catch (IllegalArgumentException e) {
                throw new OcrException(OcrException.Kind.UNAVAILABLE, status, what + ": unreadable response");
            }
        }
        return new Answer(status, parsed, response.headers().map());
    }
}
