package com.orqix.mcp;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * MCP server settings (docs/build/S136), deployment-owned; the per-workspace switch is data (Settings, MCP server).
 *
 * @param resourceUri          canonical URI of the protected resource ({@code https://orqix.example.com/mcp}); empty
 *                             derives it from the request (forwarded headers are honoured)
 * @param audiences            token audiences accepted besides the resource URI (Keycloak audience mapper value)
 * @param issuer               issuer of accepted tokens; also the authorization server announced in the metadata
 * @param jwkSetUri            keys of that issuer
 * @param authorizationServers authorization servers announced in the protected resource metadata (default: issuer)
 * @param scopes               scopes announced in the metadata (informative; access is decided by Orqix permissions)
 * @param allowedOrigins       browser origins allowed to call {@code /mcp} (DNS rebinding protection); a request with
 *                             another Origin header is refused, a request without one is accepted
 * @param callsPerMinute       tool calls one person may make per minute on each pod
 * @param confirmationTtl      how long a prepared write can be confirmed
 * @param elicitationWait      how long a tools/call stream waits for the client's elicitation answer before it tells
 *                             the assistant to call {@code confirm} instead
 * @param clockSkew            accepted clock difference for token expiry and not-before
 */
@ConfigurationProperties(prefix = "orqix.mcp")
public record McpSettings(
        @DefaultValue("") String resourceUri,
        @DefaultValue("orqix-mcp") List<String> audiences,
        @DefaultValue("") String issuer,
        @DefaultValue("") String jwkSetUri,
        @DefaultValue List<String> authorizationServers,
        @DefaultValue("orqix-mcp") List<String> scopes,
        @DefaultValue List<String> allowedOrigins,
        @DefaultValue("60") int callsPerMinute,
        @DefaultValue("5m") Duration confirmationTtl,
        @DefaultValue("60s") Duration elicitationWait,
        @DefaultValue("30s") Duration clockSkew) {

    public McpSettings {
        audiences = audiences == null ? List.of() : List.copyOf(audiences.stream().filter(a -> !a.isBlank()).toList());
        authorizationServers = authorizationServers == null ? List.of()
                : List.copyOf(authorizationServers.stream().filter(a -> !a.isBlank()).toList());
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        if (callsPerMinute < 1 || callsPerMinute > 6000) {
            throw new IllegalArgumentException("orqix.mcp.calls-per-minute must be 1..6000");
        }
        if (confirmationTtl == null || confirmationTtl.compareTo(Duration.ofSeconds(30)) < 0
                || confirmationTtl.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("orqix.mcp.confirmation-ttl must be 30s..30m");
        }
        if (elicitationWait == null || elicitationWait.isNegative() || elicitationWait.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("orqix.mcp.elicitation-wait must be 0s..5m");
        }
    }

    /** Announced authorization servers: the configured list, else the issuer. */
    public List<String> announcedAuthorizationServers() {
        return !authorizationServers.isEmpty() ? authorizationServers : issuer.isBlank() ? List.of() : List.of(issuer);
    }
}
