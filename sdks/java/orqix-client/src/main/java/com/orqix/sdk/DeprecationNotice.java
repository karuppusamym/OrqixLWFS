package com.orqix.sdk;

import java.util.Optional;

/**
 * The server answered a call to a deprecated operation (docs/api-compatibility.md). {@code deprecation} is the raw
 * {@code Deprecation} header (RFC 9745), {@code sunset} the raw {@code Sunset} header (RFC 8594) when present and
 * {@code link} the {@code Link} header that points to the replacement.
 */
public record DeprecationNotice(OrqixOperation operation, String deprecation, String sunset, String link) {

    public Optional<String> sunsetHeader() {
        return Optional.ofNullable(sunset);
    }
}
