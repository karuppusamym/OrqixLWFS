package com.orqix.ai.tasks;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.orqix.integration.ModelProviderSecrets;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * Encrypted, short-lived state of AI tasks (CG-A05): the input of a waiting job and the completed turns of a running
 * one. AES-256-GCM with the job id and purpose as associated data, so a ciphertext cannot be moved to another job.
 * The key is an S132 secret ({@code orqix.ai.tasks.turn-state-key-ref}); without one a random per-pod key is used and
 * logged once, so a crash then restarts the task from its first turn instead of resuming. Turn state is deleted when
 * the step ends and swept at most {@code turn-state-ttl} (24 h) after it was written.
 */
@Component
public class TurnVault {

    private static final Logger log = LoggerFactory.getLogger(TurnVault.class);
    static final String EPHEMERAL = "ephemeral";

    private final ModelProviderSecrets secrets;
    private final AiTaskProperties properties;
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final byte[] ephemeral = new byte[32];
    private volatile boolean warned;

    public TurnVault(ModelProviderSecrets secrets, AiTaskProperties properties, JdbcClient jdbc, Json json,
            Clock clock) {
        this.secrets = secrets;
        this.properties = properties;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        random.nextBytes(ephemeral);
    }

    /** @return {@code keyId:ciphertext} */
    public String seal(UUID workspaceId, UUID jobId, String purpose, String plaintext) {
        Key key = key(workspaceId);
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.bytes(), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD((jobId + "/" + purpose).getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, envelope, nonce.length, encrypted.length);
            return key.id() + ":" + Base64.getEncoder().encodeToString(envelope);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("AI state could not be sealed");
        }
    }

    /** @throws IllegalStateException when the key is gone (another pod's ephemeral key) or the text was changed */
    public String open(UUID workspaceId, UUID jobId, String purpose, String sealed) {
        int colon = sealed.indexOf(':');
        if (colon < 0) {
            throw new IllegalStateException("AI state is not sealed");
        }
        Key key = key(workspaceId);
        if (!key.id().equals(sealed.substring(0, colon))) {
            throw new IllegalStateException("AI state was sealed with another key");
        }
        try {
            byte[] envelope = Base64.getDecoder().decode(sealed.substring(colon + 1));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.bytes(), "AES"),
                    new GCMParameterSpec(128, Arrays.copyOf(envelope, 12)));
            cipher.updateAAD((jobId + "/" + purpose).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(envelope, 12, envelope.length - 12), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("AI state could not be opened");
        }
    }

    // ---------------------------------------------------------------- turn state

    /** The completed turns of a job (empty when none, expired, or sealed with a key this pod does not have). */
    public ArrayNode turns(UUID workspaceId, UUID jobId) {
        Optional<String> sealed = jdbc.sql("SELECT cipher FROM ai_turn_state WHERE job_id = :id AND expires_at > :now")
                .param("id", jobId).param("now", Sql.ts(clock.instant())).query(String.class).optional();
        if (sealed.isEmpty()) {
            return json.mapper().createArrayNode();
        }
        try {
            JsonNode turns = json.parse(open(workspaceId, jobId, "turns", sealed.get()));
            return turns.isArray() ? (ArrayNode) turns : json.mapper().createArrayNode();
        } catch (IllegalStateException e) {
            return json.mapper().createArrayNode();
        }
    }

    /** Saves the completed turns (own short statement; the pipeline runs outside transactions). */
    public void saveTurns(UUID workspaceId, UUID jobId, ArrayNode turns) {
        Instant now = clock.instant();
        String sealed = seal(workspaceId, jobId, "turns", json.write(turns));
        String keyId = sealed.substring(0, sealed.indexOf(':'));
        jdbc.sql("""
                INSERT INTO ai_turn_state (job_id, workspace_id, key_id, cipher, turns, expires_at, updated_at)
                VALUES (:id, :ws, :key, :cipher, :turns, :expires, :now)
                ON CONFLICT (job_id) DO UPDATE SET key_id = EXCLUDED.key_id, cipher = EXCLUDED.cipher,
                    turns = EXCLUDED.turns, updated_at = EXCLUDED.updated_at
                """).param("id", jobId).param("ws", workspaceId).param("key", keyId).param("cipher", sealed)
                .param("turns", turns.size()).param("expires", Sql.ts(now.plus(properties.turnStateTtl())))
                .param("now", Sql.ts(now)).update();
    }

    public void delete(UUID jobId) {
        jdbc.sql("DELETE FROM ai_turn_state WHERE job_id = :id").param("id", jobId).update();
    }

    /** Deletes expired turn state (at most 24 hours old). */
    @Scheduled(fixedDelayString = "${orqix.ai.tasks.turn-state-sweep:PT10M}", initialDelayString = "PT1M")
    public void sweep() {
        try {
            jdbc.sql("DELETE FROM ai_turn_state WHERE expires_at <= :now").param("now", Sql.ts(clock.instant())).update();
        } catch (RuntimeException e) {
            log.warn("AI turn state sweep failed: {}", e.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- key

    private record Key(String id, byte[] bytes) {
        @Override
        public String toString() {
            return "Key[" + id + "]";
        }
    }

    private Key key(UUID workspaceId) {
        Optional<String> configured = secrets.resolve(properties.turnStateKeyRef(), workspaceId);
        if (configured.isPresent()) {
            try {
                byte[] bytes = Base64.getDecoder().decode(configured.get().strip());
                if (bytes.length == 32) {
                    String id = Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(Arrays.copyOf(java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(bytes), 6));
                    return new Key("k" + id, bytes);
                }
            } catch (IllegalArgumentException | java.security.NoSuchAlgorithmException e) {
                // fall through to the ephemeral key
            }
        }
        if (!warned) {
            warned = true;
            log.warn("No AI turn-state key ({}): AI task input and turns are sealed with a per-pod key; a pod crash"
                    + " restarts a task from its first turn.", properties.turnStateKeyRef());
        }
        return new Key(EPHEMERAL, ephemeral);
    }
}
