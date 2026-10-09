package com.orqix.shared;

import java.util.UUID;

import com.github.f4b6a3.uuid.UuidCreator;

/** Application-generated identifiers: UUIDv7 (time-ordered) for index locality (S1 section 1). */
public final class Ids {

    private Ids() {
    }

    public static UUID newId() {
        return UuidCreator.getTimeOrderedEpoch();
    }

    /** Parses a UUID path/body value; returns null when it is not a well-formed UUID. */
    public static UUID parseOrNull(String value) {
        if (value == null || value.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
