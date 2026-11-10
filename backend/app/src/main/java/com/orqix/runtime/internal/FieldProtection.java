package com.orqix.runtime.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.orqix.catalog.ReleaseQueries;
import com.orqix.compiler.FieldClassification;
import com.orqix.integration.ProtectionKeys;
import com.orqix.runtime.engine.ProcessData;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import jakarta.annotation.PostConstruct;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * CG-I05 (docs/build/S151): encryption at rest of sensitive request answers. A sensitive answer is stored as
 * {@code orqix-enc:v1:<keyId>:<base64url(nonce | ciphertext | tag)>} (AES-256-GCM over the answer's JSON text, bound
 * to the workspace and field name), both in {@code workflow_run.input} and in the engine's request variable. Reads
 * through {@link RunStore} and {@link ProcessData#input} decrypt, so routing and steps see the answer while the
 * database never holds it in plain text. Without a configured key a request holding a sensitive answer is refused.
 */
@Component
public class FieldProtection {

    private static final Logger LOG = LoggerFactory.getLogger(FieldProtection.class);
    public static final String PREFIX = "orqix-enc:v1:";
    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;

    private final ProtectionKeys keys;
    private final ReleaseQueries releases;
    private final Json json;
    private final SecureRandom random = new SecureRandom();

    public FieldProtection(ProtectionKeys keys, ReleaseQueries releases, Json json) {
        this.keys = keys;
        this.releases = releases;
        this.json = json;
    }

    @PostConstruct
    void install() {
        ProcessData.installInputOpener(this::open);
    }

    /** The input with every sensitive answer of the release's request form encrypted (already sealed ones kept). */
    public JsonNode seal(UUID workspaceId, UUID releaseId, JsonNode input) {
        if (input == null || !input.isObject()) {
            return input;
        }
        List<String> sensitive = FieldClassification.sensitiveFields(
                releases.require(workspaceId, releaseId).inputSchema());
        if (sensitive.isEmpty()) {
            return input;
        }
        ObjectNode copy = null;
        for (String field : sensitive) {
            JsonNode value = input.path(field);
            if (value.isMissingNode() || value.isNull() || isSealed(value)) {
                continue;
            }
            ProtectionKeys.FieldKey key = keys.currentFieldKey().orElseThrow(() -> new ApiException(
                    ProblemCode.DEPENDENCY_UNAVAILABLE,
                    "Sensitive answers cannot be stored: no field encryption key is configured."));
            if (copy == null) {
                copy = ((ObjectNode) input).deepCopy();
            }
            copy.set(field, StringNode.valueOf(encrypt(key, workspaceId, field, json.write(value))));
        }
        return copy == null ? input : copy;
    }

    /** The JSON text of {@link #seal}, for the engine's request variable. */
    public String sealedText(UUID workspaceId, UUID releaseId, JsonNode input) {
        return json.write(seal(workspaceId, releaseId, input));
    }

    /** The input with every sealed answer decrypted; an answer whose key is gone stays sealed (and is masked). */
    public JsonNode open(UUID workspaceId, JsonNode stored) {
        if (stored == null || !stored.isObject()) {
            return stored;
        }
        ObjectNode copy = null;
        for (Map.Entry<String, JsonNode> e : stored.properties()) {
            if (!isSealed(e.getValue())) {
                continue;
            }
            Optional<JsonNode> plain = decrypt(workspaceId, e.getKey(), e.getValue().asString());
            if (plain.isPresent()) {
                if (copy == null) {
                    copy = ((ObjectNode) stored).deepCopy();
                }
                copy.set(e.getKey(), plain.get());
            }
        }
        return copy == null ? stored : copy;
    }

    /** Re-encrypts sealed answers that use an older key with the current key (key rotation, S151 runbook). */
    public JsonNode reseal(UUID workspaceId, JsonNode stored) {
        if (stored == null || !stored.isObject()) {
            return stored;
        }
        Optional<ProtectionKeys.FieldKey> current = keys.currentFieldKey();
        if (current.isEmpty()) {
            return stored;
        }
        ObjectNode copy = null;
        for (Map.Entry<String, JsonNode> e : stored.properties()) {
            if (!isSealed(e.getValue()) || keyId(e.getValue().asString()).equals(current.get().id())) {
                continue;
            }
            Optional<JsonNode> plain = decrypt(workspaceId, e.getKey(), e.getValue().asString());
            if (plain.isPresent()) {
                if (copy == null) {
                    copy = ((ObjectNode) stored).deepCopy();
                }
                copy.set(e.getKey(), StringNode.valueOf(encrypt(current.get(), workspaceId, e.getKey(),
                        json.write(plain.get()))));
            }
        }
        return copy == null ? stored : copy;
    }

    public static boolean isSealed(JsonNode value) {
        return value != null && value.isString() && value.asString().startsWith(PREFIX);
    }

    /** Cheap check on stored JSON text before parsing it twice. */
    public static boolean mayHoldSealed(String jsonText) {
        return jsonText != null && jsonText.contains(PREFIX);
    }

    private static String keyId(String envelope) {
        String rest = envelope.substring(PREFIX.length());
        int colon = rest.indexOf(':');
        return colon < 0 ? "" : rest.substring(0, colon);
    }

    private String encrypt(ProtectionKeys.FieldKey key, UUID workspaceId, String field, String plain) {
        try {
            byte[] nonce = new byte[NONCE];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.key(), "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(workspaceId, field));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[NONCE + sealed.length];
            System.arraycopy(nonce, 0, out, 0, NONCE);
            System.arraycopy(sealed, 0, out, NONCE, sealed.length);
            return PREFIX + key.id() + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Field encryption failed", e);
        }
    }

    private Optional<JsonNode> decrypt(UUID workspaceId, String field, String envelope) {
        String rest = envelope.substring(PREFIX.length());
        int colon = rest.indexOf(':');
        if (colon < 1) {
            return Optional.empty();
        }
        String keyId = rest.substring(0, colon);
        Optional<byte[]> key = keys.fieldKey(keyId);
        if (key.isEmpty()) {
            LOG.warn("Field key {} is not available; a sensitive answer stays encrypted.", keyId);
            return Optional.empty();
        }
        try {
            byte[] in = Base64.getUrlDecoder().decode(rest.substring(colon + 1));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.get(), "AES"),
                    new GCMParameterSpec(TAG_BITS, in, 0, NONCE));
            cipher.updateAAD(aad(workspaceId, field));
            byte[] plain = cipher.doFinal(in, NONCE, in.length - NONCE);
            return Optional.of(json.parse(new String(plain, StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            LOG.warn("A sensitive answer encrypted with key {} could not be decrypted ({}).", keyId,
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static byte[] aad(UUID workspaceId, String field) {
        return ("orqix-field|" + workspaceId + "|" + field).getBytes(StandardCharsets.UTF_8);
    }
}
