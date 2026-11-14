package com.orqix.mcp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stateless MCP sessions (S136 section 1): the {@code Mcp-Session-Id} returned by initialize is a signed record of the
 * person, the negotiated protocol version and whether the client can answer elicitations, so any pod can serve any
 * request and nothing is stored. It is bound to the person: a session ID used with another person's token is unknown
 * (404, the client initializes again). It expires after a day.
 */
@Component
public class McpSessions {

    static final Duration TTL = Duration.ofHours(24);

    /** The negotiated session. */
    public record Session(UUID subjectId, String protocolVersion, boolean elicitation, Instant issuedAt) {
    }

    private final byte[] key;
    private final Json json;
    private final Clock clock;

    public McpSessions(OrqixProperties properties, Json json, Clock clock) {
        this.key = ("orqix-mcp-session|" + properties.pagination().cursorSecret()).getBytes(StandardCharsets.UTF_8);
        this.json = json;
        this.clock = clock;
    }

    public String issue(Session session) {
        ObjectNode payload = json.object();
        payload.put("s", session.subjectId().toString());
        payload.put("v", session.protocolVersion());
        payload.put("e", session.elicitation());
        payload.put("t", session.issuedAt().getEpochSecond());
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(json.write(payload)
                .getBytes(StandardCharsets.UTF_8));
        return body + "." + sign(body);
    }

    /** The session of a valid ID for this person, else empty. */
    public Optional<Session> read(String id, UUID subjectId) {
        if (id == null || id.length() > 512) {
            return Optional.empty();
        }
        int dot = id.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String body = id.substring(0, dot);
        if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.US_ASCII),
                id.substring(dot + 1).getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        try {
            JsonNode payload = json.parse(Base64.getUrlDecoder().decode(body));
            Session session = new Session(UUID.fromString(payload.path("s").asString()), payload.path("v").asString(),
                    payload.path("e").asBoolean(false), Instant.ofEpochSecond(payload.path("t").asLong()));
            if (!session.subjectId().equals(subjectId) || session.issuedAt().plus(TTL).isBefore(clock.instant())) {
                return Optional.empty();
            }
            return Optional.of(session);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
