package com.orqix.integration.secrets;

import java.util.Optional;

/**
 * One secret store behind {@code secretRef} (docs/build/S132). Implementations never log, return in errors or store
 * the value; they may throw {@link SecretProviderException} with a message that names only the reference, the
 * provider and an HTTP status.
 */
public interface SecretProvider {

    /** @return the reference scheme this provider serves ({@link SecretRef#FILE}, {@link SecretRef#VAULT}, ...) */
    String scheme();

    /** @return false when the provider is not configured; references to it then resolve to nothing */
    boolean enabled();

    /** @return true when values may be kept in the short resolution cache (remote stores) */
    default boolean cacheable() {
        return true;
    }

    /** @return the value, or empty when the store has no such secret or field */
    Optional<String> resolve(SecretRef ref);

    /** A store could not be reached or refused the request; the message never contains a secret value. */
    final class SecretProviderException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SecretProviderException(String message) {
            super(message);
        }

        public SecretProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
