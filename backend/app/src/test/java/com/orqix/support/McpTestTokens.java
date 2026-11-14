package com.orqix.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Signed access tokens for the MCP server tests (S136): RS256 with a key pair of this JVM; the MCP chain verifies the
 * signature with the public key ({@link TestJwtDecoder#mcpTokens}), then issuer, expiry and audience with the
 * production code. Users are the dev-realm test users of {@link TestUsers} (same issuer and subject as their browser
 * sign-in, same groups).
 */
public final class McpTestTokens {

    public static final String AUDIENCE = "orqix-mcp";
    private static final KeyPair KEYS = generate();
    private static final KeyPair OTHER_KEYS = generate();

    private McpTestTokens() {
    }

    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) KEYS.getPublic();
    }

    /** A valid five-minute token for the test user, audience {@code orqix-mcp}. */
    public static String token(String username) {
        return token(username, AUDIENCE, Instant.now().plusSeconds(300), TestUsers.ISSUER, false);
    }

    public static String token(String username, List<String> groups) {
        return sign(claims(username, groups, AUDIENCE, Instant.now().plusSeconds(300), TestUsers.ISSUER), KEYS);
    }

    /** A token with the given audience, expiry and issuer; {@code foreignKey} signs it with a key nobody trusts. */
    public static String token(String username, String audience, Instant expiresAt, String issuer, boolean foreignKey) {
        List<String> groups = TestUsers.GROUPS.getOrDefault(username, List.of());
        return sign(claims(username, groups, audience, expiresAt, issuer), foreignKey ? OTHER_KEYS : KEYS);
    }

    private static JWTClaimsSet claims(String username, List<String> groups, String audience, Instant expiresAt,
            String issuer) {
        Instant issued = expiresAt.minusSeconds(300);
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("kc-" + username)
                .audience(audience)
                .issueTime(Date.from(issued))
                .notBeforeTime(Date.from(issued))
                .expirationTime(Date.from(expiresAt))
                .claim("azp", "orqix-mcp-client")
                .claim("preferred_username", username)
                .claim("name", displayName(username))
                .claim("email", username + "@orqix.example")
                .claim("groups", groups)
                .claim("scope", "openid orqix-mcp")
                .build();
    }

    /** Same display name as the browser sign-in of {@link TestUsers} ("Rhea Requester"). */
    private static String displayName(String username) {
        StringBuilder name = new StringBuilder();
        for (String part : username.split("[.]")) {
            if (!part.isEmpty()) {
                name.append(name.isEmpty() ? "" : " ").append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return name.toString();
    }

    private static String sign(JWTClaimsSet claims, KeyPair keys) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
