package com.orqix.runtime.explain;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * "Explain this request" (CG-A07), prefix {@code orqix.ai.operator-explain}.
 *
 * @param enabled         false answers every request from the rules (no model is called)
 * @param profile         AI profile resolved through the provider SPI; empty uses the deployment default
 * @param maxOutputTokens answer limit of one call
 * @param timeout         one provider call
 * @param cacheTtl        an AI answer is reused for the same caller and the same request state this long
 * @param eventWindow     timeline entries read (the newest ones)
 */
@ConfigurationProperties(prefix = "orqix.ai.operator-explain")
public record ExplainProperties(@DefaultValue("true") boolean enabled, @DefaultValue("") String profile,
        @DefaultValue("800") int maxOutputTokens, @DefaultValue("30s") Duration timeout,
        @DefaultValue("120s") Duration cacheTtl, @DefaultValue("200") int eventWindow) {

    public ExplainProperties {
        maxOutputTokens = Math.max(200, Math.min(4000, maxOutputTokens));
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(30)
                : timeout.compareTo(Duration.ofSeconds(120)) > 0 ? Duration.ofSeconds(120) : timeout;
        cacheTtl = cacheTtl == null || cacheTtl.isNegative() ? Duration.ZERO
                : cacheTtl.compareTo(Duration.ofMinutes(10)) > 0 ? Duration.ofMinutes(10) : cacheTtl;
        eventWindow = Math.max(20, Math.min(1000, eventWindow));
    }
}
