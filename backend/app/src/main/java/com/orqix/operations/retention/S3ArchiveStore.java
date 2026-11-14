package com.orqix.operations.retention;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Archives in an S3-compatible bucket (AWS S3, MinIO, Ceph RGW, OpenShift Data Foundation) over plain HTTPS with AWS
 * Signature Version 4 and path-style addressing ({@code <endpoint>/<bucket>/<key>}). No SDK is linked. The endpoint,
 * region, bucket and keys are platform configuration; the secret key is only used to sign and is never logged.
 * Redirects are not followed.
 */
public class S3ArchiveStore implements ArchiveStore {

    static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final URI endpoint;
    private final String region;
    private final String bucket;
    private final String prefix;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final HttpClient http;
    private final Clock clock;

    public S3ArchiveStore(URI endpoint, String region, String bucket, String prefix, String accessKeyId,
            String secretAccessKey, Duration timeout, Clock clock) {
        if (endpoint == null || region == null || region.isBlank() || bucket == null || bucket.isBlank()
                || accessKeyId == null || accessKeyId.isBlank() || secretAccessKey == null || secretAccessKey.isBlank()) {
            throw new IllegalStateException("orqix.retention.s3 needs endpoint, region, bucket, access-key-id and"
                    + " secret-access-key");
        }
        String base = endpoint.toString();
        this.endpoint = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
        this.region = region;
        this.bucket = bucket;
        this.prefix = prefix == null || prefix.isBlank() ? "" : prefix.replaceAll("^/+|/+$", "") + "/";
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        this.clock = clock;
    }

    @Override
    public String kind() {
        return "s3";
    }

    @Override
    public void put(String key, byte[] bytes, String sha256) {
        HttpRequest request = signed("PUT", key, sha256).header("Content-Type", "application/zip")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        HttpResponse<Void> response = send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 != 2) {
            throw new ArchiveStoreException("The object store refused the archive (HTTP " + response.statusCode() + ")");
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        HttpRequest request = signed("GET", key, EMPTY_SHA256).GET().build();
        HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() / 100 != 2) {
            throw new ArchiveStoreException("The object store could not return the archive (HTTP "
                    + response.statusCode() + ")");
        }
        return Optional.of(response.body());
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ArchiveStoreException("Interrupted while contacting the object store", e);
        } catch (java.io.IOException e) {
            throw new ArchiveStoreException("The object store could not be reached", e);
        }
    }

    /** Builds a request with SigV4 headers for an unsigned-query, single-chunk payload. */
    HttpRequest.Builder signed(String method, String key, String payloadSha256) {
        String path = endpoint.getRawPath() + "/" + encode(bucket) + "/" + encodePath(prefix + ArchiveStore.checkKey(key));
        URI uri = endpoint.resolve(path);
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String amzDate = AMZ_DATE.format(now);
        String date = DATE.format(now);
        String host = uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        String authorization = authorization(method, path, host, amzDate, date, payloadSha256);
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).header("x-amz-date", amzDate)
                .header("x-amz-content-sha256", payloadSha256).header("Authorization", authorization);
    }

    String authorization(String method, String canonicalPath, String host, String amzDate, String date,
            String payloadSha256) {
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = method + "\n" + canonicalPath + "\n" + "\n" + "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadSha256 + "\n" + "x-amz-date:" + amzDate + "\n" + "\n"
                + signedHeaders + "\n" + payloadSha256;
        String scope = date + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest);
        byte[] key = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(key, stringToSign));
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + scope + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
    }

    private static String encodePath(String key) {
        StringBuilder out = new StringBuilder();
        for (String segment : key.split("/", -1)) {
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(encode(segment));
        }
        return out.toString();
    }

    /** RFC 3986 encoding as SigV4 requires (space as %20, '~' kept). */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A")
                .replace("%7E", "~");
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
