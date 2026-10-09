package com.orqix.compiler;

import com.orqix.compiler.internal.json.Jcs;
import com.orqix.compiler.internal.json.StrictJson;

/**
 * RFC 8785 (JCS) canonicalization and hashing, shared by the compiler (source/semantic hashes) and the application
 * (idempotency request hashes, effect request hashes).
 * <p>
 * Input is parsed strictly: duplicate member names, trailing content and numbers that are not finite IEEE 754 doubles
 * are rejected with {@link IllegalArgumentException}. Numbers are serialized with the ECMAScript algorithm required by
 * RFC 8785, so {@code 5000}, {@code 5000.00} and {@code 5E3} all canonicalize to {@code 5000}.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    /**
     * @return the JCS form of the given JSON text
     * @throws IllegalArgumentException when the text is not acceptable JSON
     */
    public static String canonicalize(String json) {
        return Jcs.canonicalize(StrictJson.parse(json));
    }

    /**
     * @return lower-case hex SHA-256 of the UTF-8 bytes of the JCS form
     * @throws IllegalArgumentException when the text is not acceptable JSON
     */
    public static String sha256Hex(String json) {
        return Jcs.sha256Hex(canonicalize(json));
    }
}
