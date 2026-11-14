package com.orqix.operations.retention;

import java.util.Optional;

/**
 * Object storage for run archives (CG-O07, docs/build/S144). A key is a relative path of lower-case segments; writing
 * the same key again replaces the object, so a retried archive step is idempotent. Implementations never log keys'
 * contents or credentials.
 */
public interface ArchiveStore {

    /** {@code filesystem} or {@code s3} (stored on each archive record). */
    String kind();

    /** Stores the object; throws {@link ArchiveStoreException} when the store refuses or cannot be reached. */
    void put(String key, byte[] bytes, String sha256);

    /** Reads the object back, or empty when it does not exist. */
    Optional<byte[]> get(String key);

    final class ArchiveStoreException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ArchiveStoreException(String message) {
            super(message);
        }

        public ArchiveStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Rejects keys that could leave the archive root. */
    static String checkKey(String key) {
        if (key == null || key.isBlank() || key.length() > 500 || key.startsWith("/") || key.contains("..")
                || key.contains("\\") || !key.matches("[a-z0-9][a-z0-9._/-]*")) {
            throw new IllegalArgumentException("Invalid archive key");
        }
        return key;
    }
}
