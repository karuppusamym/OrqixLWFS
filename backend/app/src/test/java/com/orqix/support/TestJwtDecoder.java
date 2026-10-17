package com.orqix.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Machine-client tokens for tests: {@code Bearer machine.<sub>} decodes to a JWT issued by the test realm with audience
 * {@code orqix-api}; anything else is rejected. Replaces only the signature check; scoping, subject mapping and
 * authorization are the production code paths.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwtDecoder {

    @Bean
    JwtDecoder jwtDecoder() {
        return token -> {
            if (!token.startsWith("machine.")) {
                throw new BadJwtException("invalid test token");
            }
            String subject = token.substring("machine.".length());
            Instant now = Instant.now();
            return new Jwt(token, now, now.plusSeconds(300), Map.of("alg", "none"),
                    Map.of("iss", TestUsers.ISSUER, "sub", subject, "aud", List.of("orqix-api"),
                            "azp", "orqix-machine-demo", "iat", now, "exp", now.plusSeconds(300)));
        };
    }
}
