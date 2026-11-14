package com.orqix.integration.packages;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connector packages (CG-C03, docs/build/S133-connector-packages.md), deployment-owned.
 *
 * @param trustedKeys      publisher key id to its Ed25519 public key (base64 of the X.509 SubjectPublicKeyInfo). Only
 *                         signatures by these keys verify. Empty by default: every package then needs the audited
 *                         unsigned override. The example publisher key of the repository must never be trusted in
 *                         production.
 * @param developmentHosts {@code host:port} entries a package binding may also point at although they match none of the
 *                         package's host patterns (WireMock, a sandbox proxy). Empty by default; the egress policy still
 *                         applies to them.
 * @param pollTick         how often the polling-trigger scheduler looks for due triggers
 * @param pollLease        how long a pod owns a polling trigger while it polls
 * @param recordRetention  how long handled trigger records (the dedupe memory) are kept
 */
@ConfigurationProperties(prefix = "orqix.connector-packages")
public record PackageSettings(Map<String, String> trustedKeys, List<String> developmentHosts,
        @DefaultValue("2s") java.time.Duration pollTick, @DefaultValue("60s") java.time.Duration pollLease,
        @DefaultValue("7d") java.time.Duration recordRetention) {

    private static final Pattern KEY_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final Pattern HOST = Pattern.compile("^[a-z0-9.-]{1,253}:[0-9]{1,5}$");

    public PackageSettings {
        Map<String, String> keys = new LinkedHashMap<>();
        if (trustedKeys != null) {
            trustedKeys.forEach((id, key) -> {
                if (!KEY_ID.matcher(id).matches() || key == null || key.isBlank()) {
                    throw new IllegalArgumentException("orqix.connector-packages.trusted-keys." + id + " is not valid");
                }
                publicKey(key); // fail at start-up, not at the first import
                keys.put(id, key.strip());
            });
        }
        trustedKeys = Map.copyOf(keys);
        developmentHosts = developmentHosts == null ? List.of() : List.copyOf(developmentHosts.stream()
                .map(String::strip).filter(h -> !h.isEmpty()).toList());
        for (String host : developmentHosts) {
            if (!HOST.matcher(host).matches()) {
                throw new IllegalArgumentException("orqix.connector-packages.development-hosts entry " + host
                        + " must be host:port");
            }
        }
        if (pollTick == null) {
            pollTick = java.time.Duration.ofSeconds(2);
        }
        if (pollLease == null || pollLease.toSeconds() < 10) {
            pollLease = java.time.Duration.ofSeconds(60);
        }
        if (recordRetention == null) {
            recordRetention = java.time.Duration.ofDays(7);
        }
    }

    /** @return the trusted public key for the key id, or null */
    public PublicKey trusted(String keyId) {
        String key = trustedKeys.get(keyId);
        return key == null ? null : publicKey(key);
    }

    static PublicKey publicKey(String base64) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder()
                    .decode(base64.strip())));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("A trusted package key is not an Ed25519 public key.", e);
        }
    }
}
