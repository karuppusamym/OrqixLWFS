package com.orqix.integration.secrets;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

/** Keeps one access token until a minute before it expires; providers fetch a new one through the supplier. */
final class BearerTokens {

    record Token(String value, long expiresInSeconds) {
        @Override
        public String toString() {
            return "Token[expiresIn=" + expiresInSeconds + "]";
        }
    }

    private final Clock clock;
    private final Supplier<Token> fetch;
    private volatile String value;
    private volatile Instant renewAt = Instant.EPOCH;

    BearerTokens(Clock clock, Supplier<Token> fetch) {
        this.clock = clock;
        this.fetch = fetch;
    }

    synchronized String get() {
        if (value == null || !clock.instant().isBefore(renewAt)) {
            Token token = fetch.get();
            value = token.value();
            renewAt = clock.instant().plusSeconds(Math.max(token.expiresInSeconds() - 60, 10));
        }
        return value;
    }

    synchronized void clear() {
        value = null;
    }

    /** {@code expires_in} is a number in some token answers and a string in others. */
    static long seconds(tools.jackson.databind.JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 300;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        try {
            return Long.parseLong(node.asString().strip());
        } catch (NumberFormatException e) {
            return 300;
        }
    }
}
