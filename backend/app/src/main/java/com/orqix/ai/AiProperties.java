package com.orqix.ai;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI settings (S10 section 1). Deployment-owned: the UI shows them read-only. An empty {@code apiKey} disables AI
 * (rules-only product). The key is never logged: {@link #toString()} masks it.
 *
 * @param apiKey              OpenRouter key, read unprefixed from {@code OPENROUTER_API_KEY}
 * @param baseUrl             OpenAI-compatible base URL ({@code POST {baseUrl}/chat/completions})
 * @param profiles            model profiles by id ({@code economy}, {@code auto}, ...)
 * @param defaultProfile      profile used when a request names none
 * @param escalationProfile   one extra attempt when the default profile cannot produce a valid draft; empty disables
 * @param judgeModel          LLM-as-judge model used by tools/ai-eval (reported in status only)
 * @param visionJudgeModel    design-review model used by tools/ai-eval (reported in status only)
 * @param excludedModels      glob list; a configured or requested model matching any entry is refused
 * @param allowedModels       optional allowlist (globs); empty means every model that is not excluded
 * @param maxPrice            caps for fixed-price models, also sent as OpenRouter {@code provider.max_price}
 * @param budget              monthly and per-request spend limits (sum of provider-reported {@code usage.cost})
 * @param timeout             per provider call
 * @param maxRepairAttempts   repair turns after the first attempt
 * @param maxOutputTokens     {@code max_tokens} of draft generation calls
 * @param modelsCacheTtl      cache lifetime of the provider model catalog (pricing, capabilities)
 * @param appTitle            {@code X-Title} attribution header sent to OpenRouter
 * @param generationDeadline  total wall-clock budget of one generation (all attempts); no attempt is started that
 *                            cannot finish before it
 * @param providerSort        OpenRouter {@code provider.sort} (throughput, latency or price); empty sends none
 */
@ConfigurationProperties(prefix = "orqix.ai")
public record AiProperties(
        @DefaultValue("") String apiKey,
        @DefaultValue("https://openrouter.ai/api/v1") URI baseUrl,
        Map<String, Profile> profiles,
        @DefaultValue("economy") String defaultProfile,
        @DefaultValue("auto") String escalationProfile,
        @DefaultValue("anthropic/claude-haiku-5.5") String judgeModel,
        @DefaultValue("anthropic/claude-haiku-5.5") String visionJudgeModel,
        List<String> excludedModels,
        List<String> allowedModels,
        @DefaultValue MaxPrice maxPrice,
        @DefaultValue Budget budget,
        @DefaultValue("120s") Duration timeout,
        @DefaultValue("2") int maxRepairAttempts,
        @DefaultValue("8000") int maxOutputTokens,
        @DefaultValue("1h") Duration modelsCacheTtl,
        @DefaultValue("Orqix") String appTitle,
        @DefaultValue("150s") Duration generationDeadline,
        @DefaultValue("throughput") String providerSort) {

    /** Default exclusion list (costly model families and batch variants). */
    public static final List<String> DEFAULT_EXCLUDED = List.of("anthropic/claude-opus-*", "anthropic/claude-fable-*",
            "anthropic/claude-mythos-*", "openai/*-pro*", "*:batch");

    /** @param label shown in the UI; {@code model} is an OpenRouter model id */
    public record Profile(String model, String label) {
    }

    /** USD per million tokens. */
    public record MaxPrice(@DefaultValue("1.00") BigDecimal promptPerMillion,
            @DefaultValue("3.00") BigDecimal completionPerMillion) {
    }

    /** USD. */
    public record Budget(@DefaultValue("10.00") BigDecimal monthlyUsd, @DefaultValue("0.50") BigDecimal perRequestMaxUsd) {
    }

    public AiProperties {
        apiKey = apiKey == null ? "" : apiKey.trim();
        if (profiles == null || profiles.isEmpty()) {
            profiles = new LinkedHashMap<>();
            profiles.put("economy", new Profile("deepseek/deepseek-v3.2", "Economy"));
            profiles.put("auto", new Profile("typesafe/jev-router", "Auto (Jev Router)"));
        }
        profiles = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
        defaultProfile = defaultProfile == null ? "" : defaultProfile.trim();
        escalationProfile = escalationProfile == null ? "" : escalationProfile.trim();
        excludedModels = excludedModels == null ? DEFAULT_EXCLUDED
                : excludedModels.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        allowedModels = allowedModels == null ? List.of()
                : allowedModels.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (maxPrice == null) {
            maxPrice = new MaxPrice(new BigDecimal("1.00"), new BigDecimal("3.00"));
        }
        if (budget == null) {
            budget = new Budget(new BigDecimal("10.00"), new BigDecimal("0.50"));
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            timeout = Duration.ofSeconds(120);
        }
        maxRepairAttempts = Math.max(0, Math.min(5, maxRepairAttempts));
        maxOutputTokens = Math.max(1000, maxOutputTokens);
        if (modelsCacheTtl == null || modelsCacheTtl.isNegative()) {
            modelsCacheTtl = Duration.ofHours(1);
        }
        appTitle = appTitle == null || appTitle.isBlank() ? "Orqix" : appTitle;
        if (generationDeadline == null || generationDeadline.isNegative() || generationDeadline.isZero()) {
            generationDeadline = Duration.ofSeconds(150);
        }
        providerSort = providerSort == null ? "" : providerSort.trim();
    }

    /** True when a provider key is configured. */
    public boolean keyConfigured() {
        return !apiKey.isEmpty();
    }

    @Override
    public String toString() {
        return "AiProperties[apiKey=" + (keyConfigured() ? "***" : "") + ", baseUrl=" + baseUrl + ", profiles=" + profiles
                + ", defaultProfile=" + defaultProfile + ", escalationProfile=" + escalationProfile + ", excludedModels="
                + excludedModels + ", allowedModels=" + allowedModels + ", maxPrice=" + maxPrice + ", budget=" + budget
                + ", timeout=" + timeout + ", generationDeadline=" + generationDeadline + ", maxRepairAttempts="
                + maxRepairAttempts + ", providerSort=" + providerSort + "]";
    }
}
