package com.orqix.identity.cli;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies CLI access tokens (S148): signature by the issuer's keys, issuer, expiry (required) and not-before with a
 * small clock skew, and an audience from {@link CliSettings#audiences()}. A token minted for the REST API's machine
 * clients ({@code orqix-api}) or for the MCP server ({@code orqix-mcp}) never takes this path.
 */
@Component
public class CliTokens {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final CliSettings settings;
    private final Supplier<NimbusJwtDecoder> signature;
    private final Clock clock;
    private final String issuer;
    private volatile NimbusJwtDecoder decoder;

    @Autowired
    public CliTokens(CliSettings settings, Clock clock, Environment environment) {
        this(settings, clock, () -> {
            String uri = settings.jwkSetUri().isBlank()
                    ? environment.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", "")
                    : settings.jwkSetUri();
            if (uri.isBlank()) {
                throw new JwtException("No token issuer is configured for the command line tool.");
            }
            return NimbusJwtDecoder.withJwkSetUri(uri).build();
        }, settings.issuer().isBlank()
                ? environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "")
                : settings.issuer());
    }

    /** Tests supply a decoder that checks signatures with their own key; the validation is the same. */
    public CliTokens(CliSettings settings, Clock clock, Supplier<NimbusJwtDecoder> signature, String issuer) {
        this.settings = settings;
        this.clock = clock;
        this.signature = signature;
        this.issuer = issuer == null ? "" : issuer;
    }

    /**
     * Routing only: true when the unverified token names a CLI audience. The token is verified afterwards by
     * {@link #verify}; a forged audience only reaches a chain that then refuses the token.
     */
    public boolean claimsCliAudience(String token) {
        if (!settings.enabled() || token == null) {
            return false;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3 || parts[1].length() > 16_384) {
            return false;
        }
        try {
            JsonNode aud = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1])).path("aud");
            if (aud.isString()) {
                return settings.audiences().contains(aud.asString());
            }
            for (JsonNode a : aud) {
                if (a.isString() && settings.audiences().contains(a.asString())) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** @return the verified token, or null when it cannot be used (signature, issuer, time or audience) */
    public Jwt verify(String token) {
        try {
            Jwt jwt = decoder().decode(token);
            List<String> audience = jwt.getAudience() == null ? List.of() : jwt.getAudience();
            return audience.stream().anyMatch(settings.audiences()::contains) ? jwt : null;
        } catch (JwtException e) {
            return null;
        }
    }

    private NimbusJwtDecoder decoder() {
        NimbusJwtDecoder current = decoder;
        if (current == null) {
            synchronized (this) {
                if (decoder == null) {
                    NimbusJwtDecoder built = signature.get();
                    List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
                    JwtTimestampValidator timestamps = new JwtTimestampValidator(settings.clockSkew());
                    timestamps.setClock(clock);
                    validators.add(timestamps);
                    // A token without an expiry would be valid forever.
                    validators.add(jwt -> jwt.getExpiresAt() != null ? OAuth2TokenValidatorResult.success()
                            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "exp is required", null)));
                    if (!issuer.isBlank()) {
                        validators.add(new JwtIssuerValidator(issuer));
                    }
                    built.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
                    decoder = built;
                }
                current = decoder;
            }
        }
        return current;
    }
}
