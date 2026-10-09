package com.orqix.ai.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.orqix.ai.AiProperties;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/**
 * Cost-controlled model policy (S10 section 1): profiles, excluded-model globs, optional allowlist and price caps
 * checked against the provider model catalog (cached {@code orqix.ai.models-cache-ttl}). A violating profile is
 * disabled with a logged reason; the others keep working. Variable-price routers are allowed only as explicit profiles
 * and are bounded by the budgets. Clients choose profiles, never raw model ids.
 */
@Component
public class ModelPolicy {

    private static final Logger log = LoggerFactory.getLogger(ModelPolicy.class);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final Duration CATALOG_RETRY = Duration.ofMinutes(1);
    private static final Duration CREDENTIALS_RECHECK = Duration.ofMinutes(15);

    /** Reasons reported by getAiStatus. */
    public static final String REASON_NOT_CONFIGURED = "AI is not configured: no provider key is set.";
    public static final String REASON_CREDENTIALS = "The AI provider rejected the configured credentials.";
    public static final String REASON_NO_PROFILE = "Every configured model profile is excluded or over the price caps.";

    /** An usable profile. */
    public record ResolvedProfile(String id, String label, String model, boolean variablePrice, boolean structuredOutputs) {
    }

    private record Catalog(Map<String, OpenRouterClient.ModelInfo> models, Instant fetchedAt) {
    }

    /**
     * A profile paused by the circuit breaker: its router chose a model the policy refuses.
     *
     * @param model the routed model that tripped the breaker
     * @param until the next UTC midnight
     */
    public record Tripped(String profileId, String label, String model, String reason, Instant until) {
    }

    private record CachedTrip(Optional<Tripped> trip, Instant checkedAt) {
    }

    private static final Duration TRIP_CACHE = Duration.ofSeconds(10);

    private final AiProperties properties;
    private final OpenRouterClient client;
    private final AiLedger ledger;
    private final Clock clock;
    private final Map<String, Tripped> localTrips = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, CachedTrip> storedTrips = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Catalog catalog;
    private volatile Instant catalogFailedAt;
    private volatile Instant credentialsRejectedAt;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final Map<String, String> loggedViolations = new java.util.concurrent.ConcurrentHashMap<>();

    public ModelPolicy(AiProperties properties, OpenRouterClient client, AiLedger ledger, Clock clock) {
        this.properties = properties;
        this.client = client;
        this.ledger = ledger;
        this.clock = clock;
    }

    /** Startup validation of all profiles; the catalog and the key are checked in the background (no startup delay). */
    @EventListener(ApplicationReadyEvent.class)
    public void validateAtStartup() {
        if (!properties.keyConfigured()) {
            log.info("AI assistance disabled: no provider key configured (rules-only mode).");
            return;
        }
        properties.profiles().keySet().forEach(id -> staticViolation(id).ifPresent(reason -> logViolation(id, reason)));
        Thread.ofVirtual().name("orqix-ai-startup").start(() -> {
            try {
                client.verifyKey();
            } catch (ProviderException e) {
                if (e.kind() == ProviderException.Kind.CREDENTIALS_REJECTED) {
                    credentialsRejected();
                }
            } catch (RuntimeException e) {
                log.warn("AI key check failed: {}", e.getClass().getSimpleName());
            }
            catalog();
            properties.profiles().keySet().forEach(id -> violation(id).ifPresent(reason -> logViolation(id, reason)));
        });
    }

    /** Null when AI can be used, else the reason shown in getAiStatus. */
    public String disabledReason() {
        if (!properties.keyConfigured()) {
            return REASON_NOT_CONFIGURED;
        }
        Instant rejected = credentialsRejectedAt;
        if (rejected != null && clock.instant().isBefore(rejected.plus(CREDENTIALS_RECHECK))) {
            return REASON_CREDENTIALS;
        }
        return enabledProfiles().isEmpty() ? REASON_NO_PROFILE : null;
    }

    /** Called on 401/403 from the provider. */
    public void credentialsRejected() {
        if (credentialsRejectedAt == null) {
            log.warn("AI provider rejected the credentials; AI assistance is reported as disabled.");
        }
        credentialsRejectedAt = clock.instant();
    }

    /** Usable profiles in configuration order. */
    public List<ResolvedProfile> enabledProfiles() {
        List<ResolvedProfile> result = new ArrayList<>();
        for (String id : properties.profiles().keySet()) {
            if (violation(id).isEmpty()) {
                result.add(resolved(id));
            }
        }
        return result;
    }

    /** The default profile when usable, else the first usable profile, else null. */
    public String defaultProfileId() {
        List<ResolvedProfile> enabled = enabledProfiles();
        return enabled.stream().map(ResolvedProfile::id).filter(properties.defaultProfile()::equals).findFirst()
                .orElse(enabled.isEmpty() ? null : enabled.get(0).id());
    }

    /**
     * Resolves a requested profile (null = default).
     *
     * @throws ApiException AI_MODEL_NOT_ALLOWED for unknown, excluded, not-allowlisted or over-cap profiles
     */
    public ResolvedProfile resolve(String requested) {
        String id = requested == null || requested.isBlank() ? defaultProfileId() : requested;
        if (id == null || !properties.profiles().containsKey(id)) {
            throw new ApiException(ProblemCode.AI_MODEL_NOT_ALLOWED, "Unknown AI profile.");
        }
        Optional<String> violation = violation(id);
        if (violation.isPresent()) {
            throw new ApiException(ProblemCode.AI_MODEL_NOT_ALLOWED, violation.get());
        }
        return resolved(id);
    }

    /** The escalation profile when configured, different from {@code current} and usable. */
    public Optional<ResolvedProfile> escalation(String current) {
        String id = properties.escalationProfile();
        if (id.isEmpty() || id.equals(current) || !properties.profiles().containsKey(id) || violation(id).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(resolved(id));
    }

    /** Why a profile cannot be used (excluded, not allowlisted, over the price caps, unknown model), if so. */
    public Optional<String> violation(String profileId) {
        Optional<String> fixed = staticViolation(profileId);
        if (fixed.isPresent()) {
            return fixed;
        }
        Optional<Tripped> tripped = tripped(profileId);
        if (tripped.isPresent()) {
            return Optional.of(tripped.get().reason());
        }
        String model = properties.profiles().get(profileId).model();
        Catalog current = catalog();
        if (current == null) {
            // Catalog unavailable: provider.max_price on every request still enforces the caps.
            return Optional.empty();
        }
        OpenRouterClient.ModelInfo info = current.models().get(normalize(model));
        if (info == null) {
            return Optional.of("The model " + model + " is not offered by the provider.");
        }
        if (info.variablePrice()) {
            return Optional.empty();
        }
        BigDecimal prompt = info.promptPrice().multiply(MILLION);
        BigDecimal completion = info.completionPrice().multiply(MILLION);
        if (prompt.compareTo(properties.maxPrice().promptPerMillion()) > 0
                || completion.compareTo(properties.maxPrice().completionPerMillion()) > 0) {
            return Optional.of("The model " + model + " costs more than the configured price caps.");
        }
        return Optional.empty();
    }

    /**
     * The model a provider reported for a call is checked against the policy: a router (variable-price profile) can
     * pick any model its own catalog has, and its candidates cannot be restricted by an allowlist (S10 section 1). A
     * violation is a model matching {@code excluded-models}, outside the {@code allowed-models} list, or a fixed price
     * above the caps. Returns the reason, or empty when the routed model is acceptable (or unknown to the catalog).
     */
    public Optional<String> routedViolation(String requestedModel, String routedModel) {
        if (routedModel == null || routedModel.isBlank() || normalize(routedModel).equals(normalize(requestedModel))) {
            return Optional.empty();
        }
        String id = normalize(routedModel);
        if (excluded(id)) {
            return Optional.of("The router chose the excluded model " + id + ".");
        }
        if (!allowlisted(id)) {
            return Optional.of("The router chose " + id + ", which is not in the model allowlist.");
        }
        Catalog current = catalog;
        OpenRouterClient.ModelInfo info = current == null ? null : current.models().get(id);
        if (info != null && !info.variablePrice()) {
            BigDecimal prompt = info.promptPrice().multiply(MILLION);
            BigDecimal completion = info.completionPrice().multiply(MILLION);
            if (prompt.compareTo(properties.maxPrice().promptPerMillion()) > 0
                    || completion.compareTo(properties.maxPrice().completionPerMillion()) > 0) {
                return Optional.of("The router chose " + id + ", which costs more than the configured price caps.");
            }
        }
        return Optional.empty();
    }

    /**
     * Circuit breaker: pauses the profile for the rest of the UTC day after its router chose a refused model. The
     * generation that revealed it keeps the violation on its {@code ai_generation} row, which is how every other pod
     * learns about the pause (see {@link #tripped}).
     */
    public void tripBreaker(String profileId, String routedModel, String reason) {
        Instant until = nextMidnight();
        Tripped trip = new Tripped(profileId, labelOf(profileId), routedModel,
                reason + " The profile is paused until 00:00 UTC.", until);
        if (localTrips.put(profileId, trip) == null) {
            log.error("AI profile '{}' paused until {}: {}", profileId, until, reason);
        }
    }

    /** The active pause of a profile (this pod's trips, or a violation recorded today by any pod). */
    public Optional<Tripped> tripped(String profileId) {
        Instant now = clock.instant();
        Tripped local = localTrips.get(profileId);
        if (local != null) {
            if (local.until().isAfter(now)) {
                return Optional.of(local);
            }
            localTrips.remove(profileId, local);
        }
        CachedTrip cached = storedTrips.get(profileId);
        if (cached == null || cached.checkedAt().plus(TRIP_CACHE).isBefore(now)) {
            Optional<Tripped> found;
            try {
                found = ledger.violationToday(profileId).map(v -> new Tripped(profileId, labelOf(profileId), v.model(),
                        v.violation() + " The profile is paused until 00:00 UTC.", nextMidnight()));
            } catch (RuntimeException e) {
                found = Optional.empty();
            }
            cached = new CachedTrip(found, now);
            storedTrips.put(profileId, cached);
        }
        return cached.trip();
    }

    /** Every profile that is paused right now (Settings > AI, getAiStatus). */
    public List<Tripped> trippedProfiles() {
        List<Tripped> result = new ArrayList<>();
        for (String id : properties.profiles().keySet()) {
            tripped(id).ifPresent(result::add);
        }
        return result;
    }

    private String labelOf(String profileId) {
        AiProperties.Profile profile = properties.profiles().get(profileId);
        return profile == null || profile.label() == null || profile.label().isBlank() ? profileId : profile.label();
    }

    private Instant nextMidnight() {
        return clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate().plusDays(1)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    }

    /** True when the model id matches an excluded glob (a leading "~" alias marker is ignored). */
    public boolean excluded(String model) {
        String id = normalize(model);
        return properties.excludedModels().stream().anyMatch(glob -> matches(glob, id));
    }

    /** True when there is no allowlist or the model matches one of its globs. */
    public boolean allowlisted(String model) {
        String id = normalize(model);
        return properties.allowedModels().isEmpty() || properties.allowedModels().stream().anyMatch(g -> matches(g, id));
    }

    /** Glob with {@code *} as "any characters"; case-insensitive. */
    static boolean matches(String glob, String value) {
        StringBuilder regex = new StringBuilder();
        for (String part : glob.toLowerCase(Locale.ROOT).split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.matches(regex.toString(), value.toLowerCase(Locale.ROOT));
    }

    static String normalize(String model) {
        String id = model == null ? "" : model.trim();
        return id.startsWith("~") ? id.substring(1) : id;
    }

    /** Price facts for the settings page: null when unknown. */
    public Map<String, OpenRouterClient.ModelInfo> knownModels() {
        Catalog current = catalog;
        return current == null ? Map.of() : current.models();
    }

    private Optional<String> staticViolation(String profileId) {
        AiProperties.Profile profile = properties.profiles().get(profileId);
        if (profile == null || profile.model() == null || profile.model().isBlank()) {
            return Optional.of("The profile has no model.");
        }
        if (excluded(profile.model())) {
            return Optional.of("The model " + profile.model() + " is excluded by policy.");
        }
        if (!allowlisted(profile.model())) {
            return Optional.of("The model " + profile.model() + " is not in the allowlist.");
        }
        return Optional.empty();
    }

    private ResolvedProfile resolved(String id) {
        AiProperties.Profile profile = properties.profiles().get(id);
        Catalog current = catalog;
        OpenRouterClient.ModelInfo info = current == null ? null : current.models().get(normalize(profile.model()));
        boolean variable = info == null ? profile.model().contains("router") || profile.model().endsWith("/auto")
                : info.variablePrice();
        boolean structured = info != null && info.supportedParameters().contains("structured_outputs");
        String label = profile.label() == null || profile.label().isBlank() ? id : profile.label();
        return new ResolvedProfile(id, label, profile.model(), variable, structured);
    }

    /** Current catalog: loaded synchronously the first time, refreshed in the background when stale. */
    private Catalog catalog() {
        if (!properties.keyConfigured()) {
            return null;
        }
        Catalog current = catalog;
        Instant now = clock.instant();
        if (current == null) {
            Instant failed = catalogFailedAt;
            if (failed != null && now.isBefore(failed.plus(CATALOG_RETRY))) {
                return null;
            }
            return load();
        }
        if (now.isAfter(current.fetchedAt().plus(properties.modelsCacheTtl())) && refreshing.compareAndSet(false, true)) {
            Thread.ofVirtual().name("orqix-ai-models").start(() -> {
                try {
                    load();
                } finally {
                    refreshing.set(false);
                }
            });
        }
        return current;
    }

    private synchronized Catalog load() {
        try {
            Map<String, OpenRouterClient.ModelInfo> models = new LinkedHashMap<>();
            client.models().forEach(m -> models.put(normalize(m.id()), m));
            Catalog loaded = new Catalog(Map.copyOf(models), clock.instant());
            catalog = loaded;
            catalogFailedAt = null;
            return loaded;
        } catch (RuntimeException e) {
            catalogFailedAt = clock.instant();
            log.warn("AI model catalog unavailable ({}); price caps are enforced by provider.max_price only.",
                    e.getMessage());
            return catalog;
        }
    }

    private void logViolation(String profileId, String reason) {
        if (!reason.equals(loggedViolations.put(profileId, reason))) {
            log.warn("AI profile '{}' disabled: {}", profileId, reason);
        }
    }
}
