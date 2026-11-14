package com.orqix.shared.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CG-I05 and CG-I06 (docs/build/S151): keys for field encryption and signed audit exports, and the audit chain check.
 * Values here are key IDs and {@code secretRef}s (S132), never key material, except the development-only
 * {@code fieldKeys.devKey}.
 *
 * @param fieldKeys    AES-256 keys for sensitive request values: {@code current} names the key new values use;
 *                     {@code refs} maps every key ID still needed to read old values to its secretRef (the secret
 *                     holds 32 random bytes, base64); {@code devKey} is a base64 key used as key ID {@code dev} when
 *                     no refs are configured (local development and tests only, never production)
 * @param auditSigning {@code keyRef} names a secret holding an EC P-256 private key as a JWK (JSON with {@code d});
 *                     {@code devEphemeral} signs with a key generated at start-up when no key is configured (local
 *                     development and tests only)
 * @param auditChain   {@code scheduled} turns on the periodic chain verification; {@code interval} between runs
 */
@ConfigurationProperties("orqix.protection")
public record ProtectionSettings(FieldKeys fieldKeys, AuditSigning auditSigning, AuditChain auditChain) {

    public record FieldKeys(String current, Map<String, String> refs, String devKey) {
        public FieldKeys {
            refs = refs == null ? Map.of() : Map.copyOf(refs);
            current = current == null || current.isBlank() ? null : current.strip();
            devKey = devKey == null || devKey.isBlank() ? null : devKey.strip();
        }
    }

    public record AuditSigning(String keyRef, boolean devEphemeral) {
        public AuditSigning {
            keyRef = keyRef == null || keyRef.isBlank() ? null : keyRef.strip();
        }
    }

    public record AuditChain(boolean scheduled, Duration interval) {
        public AuditChain {
            interval = interval == null ? Duration.ofHours(6) : interval;
        }
    }

    public ProtectionSettings {
        fieldKeys = fieldKeys == null ? new FieldKeys(null, null, null) : fieldKeys;
        auditSigning = auditSigning == null ? new AuditSigning(null, false) : auditSigning;
        auditChain = auditChain == null ? new AuditChain(true, null) : auditChain;
    }
}
