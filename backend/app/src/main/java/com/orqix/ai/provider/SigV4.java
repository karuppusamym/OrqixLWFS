package com.orqix.ai.provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 (header signing) for Bedrock calls. The S132 signer is internal to the integration module,
 * so the AI module keeps its own copy; both are checked against the AWS {@code get-vanilla} test vector.
 */
final class SigV4 {

    static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    record Credentials(String accessKeyId, String secretAccessKey, String sessionToken) {
        @Override
        public String toString() {
            return "Credentials[" + accessKeyId + "]";
        }
    }

    private SigV4() {
    }

    /**
     * @param canonicalPath the path exactly as it appears in the canonical request (already URI-encoded as AWS requires)
     * @param headers       headers to sign, including {@code host} and {@code x-amz-date}
     */
    static String authorization(Credentials credentials, String region, String service, String method,
            String canonicalPath, String query, Map<String, String> headers, byte[] body, Instant now) {
        TreeMap<String, String> signed = new TreeMap<>();
        headers.forEach((k, v) -> signed.put(k.toLowerCase(Locale.ROOT), v.strip().replaceAll("\\s+", " ")));
        StringBuilder canonicalHeaders = new StringBuilder();
        signed.forEach((k, v) -> canonicalHeaders.append(k).append(':').append(v).append('\n'));
        String signedHeaders = String.join(";", signed.keySet());
        String canonical = method + "\n" + canonicalPath + "\n" + (query == null ? "" : query) + "\n" + canonicalHeaders
                + "\n" + signedHeaders + "\n" + hex(sha256(body));
        String date = AMZ_DATE.format(now).substring(0, 8);
        String scope = date + "/" + region + "/" + service + "/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + AMZ_DATE.format(now) + "\n" + scope + "\n"
                + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        byte[] key = hmac(("AWS4" + credentials.secretAccessKey()).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, service);
        key = hmac(key, "aws4_request");
        return "AWS4-HMAC-SHA256 Credential=" + credentials.accessKeyId() + "/" + scope + ", SignedHeaders="
                + signedHeaders + ", Signature=" + hex(hmac(key, toSign));
    }

    /** RFC 3986 encoding of one path segment as AWS uses it (unreserved characters kept). */
    static String encode(String segment) {
        StringBuilder out = new StringBuilder();
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (Character.isLetterOrDigit(c) && c < 128 || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
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
