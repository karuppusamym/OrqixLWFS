package com.orqix.identity.cli;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * CG-K01 (S148): the {@code orqix} command line tool signs in as the person (OAuth device code with the identity
 * provider's {@code orqix-cli} client) and sends that access token. Only tokens whose audience names one of
 * {@code audiences} take this path; they are checked against {@code issuer} and {@code jwkSetUri} (by default the REST
 * API's resource server settings). {@code enabled=false} turns the path off: such tokens are then refused.
 */
@ConfigurationProperties(prefix = "orqix.cli")
public record CliSettings(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("orqix-cli") List<String> audiences,
        @DefaultValue("") String issuer,
        @DefaultValue("") String jwkSetUri,
        @DefaultValue("30s") Duration clockSkew) {
}
