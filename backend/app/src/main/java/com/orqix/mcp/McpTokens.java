package com.orqix.mcp;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * Verifies MCP access tokens (S136 section 2): signature by the issuer's keys, issuer, expiry and not-before with a
 * small clock skew. The audience is checked by {@link #audienceMatches} against the resource URI of the request and the
 * configured audiences, so a token minted for the Orqix REST API (audience {@code orqix-api}) or for another resource
 * is refused here. The token is only read, never forwarded anywhere (no token passthrough).
 */
@Component
public class McpTokens {

    /** A token that cannot be used; {@code description} is safe to return in WWW-Authenticate. */
    public static final class InvalidToken extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public InvalidToken(String description) {
            super(description);
        }
    }

    private final McpSettings settings;
    private final Supplier<NimbusJwtDecoder> signature;
    private final Clock clock;
    private volatile NimbusJwtDecoder decoder;

    @Autowired
    public McpTokens(McpSettings settings, Clock clock) {
        this(settings, clock, () -> {
            if (settings.jwkSetUri().isBlank()) {
                throw new InvalidToken("The MCP server has no token issuer configured.");
            }
            return NimbusJwtDecoder.withJwkSetUri(settings.jwkSetUri()).build();
        });
    }

    /** Tests supply a decoder that checks signatures with their own key; the validation below is the same. */
    public McpTokens(McpSettings settings, Clock clock, Supplier<NimbusJwtDecoder> signature) {
        this.settings = settings;
        this.clock = clock;
        this.signature = signature;
    }

    /** @throws InvalidToken when the signature, issuer or validity period is wrong */
    public Jwt verify(String token) {
        try {
            return decoder().decode(token);
        } catch (JwtException e) {
            throw new InvalidToken(e.getMessage() != null && e.getMessage().toLowerCase().contains("expired")
                    ? "The access token expired." : "The access token is not valid.");
        }
    }

    /** True when the token was issued for this resource: its audience names the resource URI or a configured audience. */
    public boolean audienceMatches(Jwt jwt, String resourceUri) {
        List<String> audience = jwt.getAudience() == null ? List.of() : jwt.getAudience();
        return audience.contains(resourceUri) || audience.stream().anyMatch(settings.audiences()::contains);
    }

    public Instant now() {
        return clock.instant();
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
                    if (!settings.issuer().isBlank()) {
                        validators.add(new JwtIssuerValidator(settings.issuer()));
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
