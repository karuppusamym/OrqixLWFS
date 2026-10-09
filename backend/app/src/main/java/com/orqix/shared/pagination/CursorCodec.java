package com.orqix.shared.pagination;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/**
 * Opaque keyset cursors (S3 section 7): base64url of the last sort key plus a hash of the list filters, HMAC-signed
 * with the shared instance key so a cursor cannot be forged or replayed with different filters.
 */
@Component
public class CursorCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] key;

    public CursorCodec(OrqixProperties properties) {
        this.key = properties.pagination().cursorSecret().getBytes(StandardCharsets.UTF_8);
    }

    /** Sort position: timestamp + tie-breaking id. */
    public record Position(Instant at, String id) {
    }

    public String encode(Position position, String filterKey) {
        String payload = position.at().toString() + "|" + position.id() + "|" + Json.sha256Hex(filterKey).substring(0, 16);
        String body = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + ENCODER.encodeToString(hmac(body));
    }

    /** @return the position, or null when {@code cursor} is null/blank; 400 INVALID_CURSOR when tampered or mismatched */
    public Position decode(String cursor, String filterKey) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            int dot = cursor.indexOf('.');
            if (dot <= 0 || cursor.length() > 512) {
                throw invalid();
            }
            String body = cursor.substring(0, dot);
            byte[] signature = DECODER.decode(cursor.substring(dot + 1));
            if (!MessageDigest.isEqual(signature, hmac(body))) {
                throw invalid();
            }
            String[] parts = new String(DECODER.decode(body), StandardCharsets.UTF_8).split(java.util.regex.Pattern.quote("|"), -1);
            if (parts.length != 3 || !parts[2].equals(Json.sha256Hex(filterKey).substring(0, 16))) {
                throw invalid();
            }
            return new Position(Instant.parse(parts[0]), parts[1]);
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw invalid();
        }
    }

    private byte[] hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException invalid() {
        return new ApiException(ProblemCode.INVALID_CURSOR, "The cursor is not valid for this list.");
    }
}
