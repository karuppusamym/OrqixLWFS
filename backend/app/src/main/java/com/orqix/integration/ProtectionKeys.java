package com.orqix.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.orqix.integration.secrets.SecretResolver;
import com.orqix.shared.config.ProtectionSettings;

/**
 * CG-I05 and CG-I06 (docs/build/S151): key material for field encryption and signed audit exports, read through the
 * S132 secret resolver (mounted files or an external store). Decoded keys are kept for one minute so a rotated secret
 * is picked up quickly; logs name key IDs and references only.
 */
@Component
public class ProtectionKeys {

    private static final Logger LOG = LoggerFactory.getLogger(ProtectionKeys.class);
    private static final Duration KEEP = Duration.ofMinutes(1);
    public static final String DEV_KEY_ID = "dev";

    /** An AES-256 field key. */
    public record FieldKey(String id, byte[] key) {
        @Override
        public String toString() {
            return "FieldKey[" + id + "]";
        }
    }

    private record Kept<T>(T value, Instant until) {
        @Override
        public String toString() {
            return "Kept[until=" + until + "]";
        }
    }

    private final SecretResolver secrets;
    private final ProtectionSettings settings;
    private final Clock clock;
    private final Map<String, Kept<byte[]>> fieldKeys = new ConcurrentHashMap<>();
    private volatile Kept<ECKey> signing;
    private volatile ECKey ephemeral;

    public ProtectionKeys(SecretResolver secrets, ProtectionSettings settings, Clock clock) {
        this.secrets = secrets;
        this.settings = settings;
        this.clock = clock;
    }

    /** The key new sensitive values are encrypted with, or empty when none is configured (fail closed). */
    public Optional<FieldKey> currentFieldKey() {
        String id = settings.fieldKeys().current();
        if (id == null && settings.fieldKeys().refs().isEmpty() && settings.fieldKeys().devKey() != null) {
            id = DEV_KEY_ID;
        }
        if (id == null) {
            return Optional.empty();
        }
        String keyId = id;
        return fieldKey(keyId).map(k -> new FieldKey(keyId, k));
    }

    /** A field key by ID (current or a previous one still listed in {@code refs}). */
    public Optional<byte[]> fieldKey(String id) {
        Instant now = clock.instant();
        Kept<byte[]> kept = fieldKeys.get(id);
        if (kept != null && now.isBefore(kept.until())) {
            return Optional.of(kept.value());
        }
        Optional<String> encoded;
        String ref = settings.fieldKeys().refs().get(id);
        if (ref != null) {
            encoded = secrets.resolve(ref, null);
        } else if (DEV_KEY_ID.equals(id) && settings.fieldKeys().refs().isEmpty()) {
            encoded = Optional.ofNullable(settings.fieldKeys().devKey());
        } else {
            encoded = Optional.empty();
        }
        Optional<byte[]> key = encoded.flatMap(value -> decode(id, value));
        key.ifPresent(k -> fieldKeys.put(id, new Kept<>(k, now.plus(KEEP))));
        return key;
    }

    private static Optional<byte[]> decode(String id, String value) {
        try {
            byte[] key = Base64.getDecoder().decode(value.strip());
            if (key.length != 32) {
                LOG.warn("Field key {} is not 32 bytes and is ignored.", id);
                return Optional.empty();
            }
            return Optional.of(key);
        } catch (IllegalArgumentException e) {
            LOG.warn("Field key {} is not base64 and is ignored.", id);
            return Optional.empty();
        }
    }

    /** The EC P-256 key that signs audit exports (private part included), or empty when none is configured. */
    public Optional<ECKey> auditSigningKey() {
        Instant now = clock.instant();
        Kept<ECKey> kept = signing;
        if (kept != null && now.isBefore(kept.until())) {
            return Optional.of(kept.value());
        }
        String ref = settings.auditSigning().keyRef();
        if (ref != null) {
            Optional<ECKey> key = secrets.resolve(ref, null).flatMap(ProtectionKeys::parseSigningKey);
            key.ifPresent(k -> signing = new Kept<>(k, now.plus(KEEP)));
            return key;
        }
        if (settings.auditSigning().devEphemeral()) {
            return Optional.of(ephemeral());
        }
        return Optional.empty();
    }

    private synchronized ECKey ephemeral() {
        if (ephemeral == null) {
            try {
                ephemeral = new ECKeyGenerator(Curve.P_256).keyID("dev-ephemeral").generate();
                LOG.warn("Audit exports are signed with a key generated at start-up (development only).");
            } catch (com.nimbusds.jose.JOSEException e) {
                throw new IllegalStateException("Could not generate a development signing key", e);
            }
        }
        return ephemeral;
    }

    private static Optional<ECKey> parseSigningKey(String value) {
        try {
            ECKey key = ECKey.parse(value.strip());
            if (!key.isPrivate() || !Curve.P_256.equals(key.getCurve()) || key.getKeyID() == null) {
                LOG.warn("The audit signing key must be a private EC P-256 JWK with a kid; it is ignored.");
                return Optional.empty();
            }
            return Optional.of(key);
        } catch (java.text.ParseException e) {
            LOG.warn("The audit signing key is not a JWK and is ignored.");
            return Optional.empty();
        }
    }
}
