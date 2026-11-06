package com.orqix.integration.secrets;

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

/** AWS Signature Version 4 for one request (header signing, no query auth). */
final class AwsSigV4 {

    static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    record Credentials(String accessKeyId, String secretAccessKey, String sessionToken) {
        @Override
        public String toString() {
            return "Credentials[" + accessKeyId + "]";
        }
    }

    private AwsSigV4() {
    }

    /**
     * @param headers the headers to sign, including {@code host} and {@code x-amz-date}; names are lower-cased here
     * @return the {@code Authorization} header value
     */
    static String authorization(Credentials credentials, String region, String service, String method, String path,
            String query, Map<String, String> headers, byte[] body, Instant now) {
        TreeMap<String, String> signed = new TreeMap<>();
        headers.forEach((k, v) -> signed.put(k.toLowerCase(Locale.ROOT), v.strip().replaceAll("\\s+", " ")));
        StringBuilder canonicalHeaders = new StringBuilder();
        signed.forEach((k, v) -> canonicalHeaders.append(k).append(':').append(v).append('\n'));
        String signedHeaders = String.join(";", signed.keySet());
        String canonical = method + "\n" + path + "\n" + (query == null ? "" : query) + "\n" + canonicalHeaders + "\n"
                + signedHeaders + "\n" + hex(sha256(body));
        String date = AMZ_DATE.format(now).substring(0, 8);
        String scope = date + "/" + region + "/" + service + "/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + AMZ_DATE.format(now) + "\n" + scope + "\n"
                + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        byte[] key = hmac(("AWS4" + credentials.secretAccessKey()).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, service);
        key = hmac(key, "aws4_request");
        String signature = hex(hmac(key, toSign));
        return "AWS4-HMAC-SHA256 Credential=" + credentials.accessKeyId() + "/" + scope + ", SignedHeaders="
                + signedHeaders + ", Signature=" + signature;
    }

    static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }
}
