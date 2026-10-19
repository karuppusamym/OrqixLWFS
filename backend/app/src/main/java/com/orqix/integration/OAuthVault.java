package com.orqix.integration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/** Mounted keyring; authenticated encryption binds each ciphertext to its workspace and purpose. */
@Component
public class OAuthVault {
    public record Sealed(String keyId, String ciphertext) {}
    private final OrqixProperties properties;
    private final Json json;
    private final SecureRandom random = new SecureRandom();

    public OAuthVault(OrqixProperties properties, Json json) { this.properties = properties; this.json = json; }

    public Optional<String> readSecret(String reference) {
        if (reference == null || !reference.matches("[a-z0-9][a-z0-9-]{0,62}")) return Optional.empty();
        try {
            var root = properties.secrets().dir().toAbsolutePath().normalize();
            var path = root.resolve(reference).normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path) || Files.size(path) > 16384) return Optional.empty();
            String value = Files.readString(path, StandardCharsets.UTF_8).strip();
            return value.isEmpty() || value.length() > 16384 ? Optional.empty() : Optional.of(value);
        } catch (Exception ex) { return Optional.empty(); }
    }

    public Sealed seal(String context, String plaintext) {
        try {
            var ring = ring();
            String active = ring.path("active").asString();
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(ring, active), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, envelope, nonce.length, encrypted.length);
            return new Sealed(active, Base64.getEncoder().encodeToString(envelope));
        } catch (Exception ex) { throw unavailable(); }
    }

    public String open(String context, String keyId, String ciphertext) {
        try {
            byte[] envelope = Base64.getDecoder().decode(ciphertext);
            if (envelope.length < 29 || envelope.length > 32768) throw unavailable();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(ring(), keyId), new GCMParameterSpec(128, Arrays.copyOf(envelope, 12)));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(envelope, 12, envelope.length - 12), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw unavailable(); }
    }

    public String activeKeyId() { return ring().path("active").asString(); }

    private tools.jackson.databind.JsonNode ring() {
        try {
            var ring = json.parse(readSecret("oauth-token-keyring").orElseThrow(OAuthVault::unavailable));
            if (!ring.isObject() || !java.util.Set.of("active", "keys").equals(ring.propertyNames())
                    || !ring.path("active").isString() || !ring.path("keys").isObject()
                    || ring.path("keys").size() < 1 || ring.path("keys").size() > 10) throw unavailable();
            key(ring, ring.path("active").asString());
            return ring;
        } catch (Exception ex) { throw unavailable(); }
    }

    private static SecretKeySpec key(tools.jackson.databind.JsonNode ring, String id) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,64}") || !ring.path("keys").path(id).isString()) throw unavailable();
        byte[] bytes = Base64.getDecoder().decode(ring.path("keys").path(id).asString());
        if (bytes.length != 32) throw unavailable();
        return new SecretKeySpec(bytes, "AES");
    }

    static ApiException unavailable() {
        return new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE, "OAuth authorization is unavailable. Check the mounted configuration and consent status.");
    }
}
