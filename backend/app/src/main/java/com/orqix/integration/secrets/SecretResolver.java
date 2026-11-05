package com.orqix.integration.secrets;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves a {@code secretRef} through the provider its scheme names (docs/build/S132). Remote values are cached for
 * {@code orqix.secrets.cache-ttl} (default 30 s; 0 turns the cache off) so a rotation is picked up within that time;
 * mounted files are read on every use. Missing secrets and provider failures are not cached and resolve to empty,
 * which callers report as an unavailable connection. Every resolution is counted in {@link SecretAccessAudit}. Logs
 * name the reference and provider only.
 */
@Component
public class SecretResolver {

    private static final Logger LOG = LoggerFactory.getLogger(SecretResolver.class);
    private static final int MAX_CACHED = 2000;

    private record Cached(String value, Instant until) {
        @Override
        public String toString() {
            return "Cached[until=" + until + "]";
        }
    }

    private final Map<String, SecretProvider> providers;
    private final SecretAccessAudit audit;
    private final Clock clock;
    private final Duration ttl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public SecretResolver(List<SecretProvider> providers, SecretProviderSettings settings, SecretAccessAudit audit,
            Clock clock) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(SecretProvider::scheme,
                Function.identity()));
        this.audit = audit;
        this.clock = clock;
        this.ttl = settings.cacheTtl();
    }

    /** @return the value, or empty when the reference is malformed, its provider is off, or the store has no value */
    public Optional<String> resolve(String secretRef, UUID workspaceId) {
        Optional<SecretRef> parsed = SecretRef.parse(secretRef);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        SecretRef ref = parsed.get();
        SecretProvider provider = providers.get(ref.scheme());
        if (provider == null || !provider.enabled()) {
            LOG.warn("Secret reference {} names the {} provider, which is not enabled.", ref.raw(), ref.scheme());
            audit.record(workspaceId, ref, SecretAccessAudit.Outcome.FAILED);
            return Optional.empty();
        }
        boolean cacheable = provider.cacheable() && !ttl.isZero() && !ttl.isNegative();
        Instant now = clock.instant();
        if (cacheable) {
            Cached hit = cache.get(ref.raw());
            if (hit != null && now.isBefore(hit.until())) {
                audit.record(workspaceId, ref, SecretAccessAudit.Outcome.CACHED);
                return Optional.of(hit.value());
            }
        }
        Optional<String> value;
        try {
            value = provider.resolve(ref);
        } catch (RuntimeException e) {
            // The provider's message names the store and status only; never log a cause chain that could hold data.
            LOG.warn("Secret reference {} could not be resolved by the {} provider: {}", ref.raw(), ref.scheme(),
                    e instanceof SecretProvider.SecretProviderException ? e.getMessage() : e.getClass().getSimpleName());
            audit.record(workspaceId, ref, SecretAccessAudit.Outcome.FAILED);
            return Optional.empty();
        }
        if (value.isEmpty()) {
            cache.remove(ref.raw());
            audit.record(workspaceId, ref, SecretAccessAudit.Outcome.NOT_FOUND);
            return value;
        }
        if (cacheable) {
            if (cache.size() >= MAX_CACHED) {
                cache.clear();
            }
            cache.put(ref.raw(), new Cached(value.get(), now.plus(ttl)));
        }
        audit.record(workspaceId, ref, SecretAccessAudit.Outcome.RESOLVED);
        return value;
    }

    /** Drops a cached value, for example after the remote system refused it. */
    public void invalidate(String secretRef) {
        if (secretRef != null) {
            cache.remove(secretRef);
        }
    }

    /** @return the provider scheme of a reference, or null when it is malformed (shown next to a binding) */
    public static String providerOf(String secretRef) {
        return SecretRef.parse(secretRef).map(SecretRef::scheme).orElse(null);
    }
}
