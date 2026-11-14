package com.orqix.sdk.spring;

import java.util.function.Supplier;

/**
 * Supplies the current access token of the application's Orqix machine identity; called for every request. Define a
 * bean of this type (for example backed by your OAuth2 client) or set {@code orqix.token-file} to a mounted file that
 * your identity service keeps current.
 */
@FunctionalInterface
public interface OrqixTokenProvider extends Supplier<String> {
}
