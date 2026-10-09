package com.orqix.shared.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * All Orqix settings (S1 section 5), validated at startup. The prod profile adds safety checks on top
 * ({@code ProdConfigurationGuard}).
 */
@Validated
@ConfigurationProperties("orqix")
public record OrqixProperties(
        @Valid @NotNull Identity identity,
        @Valid @NotNull Policy policy,
        @Valid @NotNull Idempotency idempotency,
        @Valid @NotNull Egress egress,
        @Valid @NotNull Secrets secrets,
        @Valid @NotNull Limits limits,
        @Valid @NotNull Pagination pagination,
        @Valid @NotNull Runtime runtime,
        @Valid @NotNull Migration migration) {

    public record Identity(
            @NotBlank String registrationId,
            @NotBlank String groupsClaim,
            List<@NotBlank String> bootstrapPlatformAdminGroups,
            @NotNull Duration sessionIdleTimeout,
            @NotNull Duration sessionAbsoluteTimeout,
            String endSessionUri) {

        public Identity {
            bootstrapPlatformAdminGroups = bootstrapPlatformAdminGroups == null ? List.of() : List.copyOf(bootstrapPlatformAdminGroups);
            endSessionUri = endSessionUri == null || endSessionUri.isBlank() ? null : endSessionUri.trim();
        }
    }

    public record Policy(@NotNull Duration cacheTtl) {
    }

    public record Idempotency(@NotNull Duration retention) {
    }

    /**
     * Egress (SSRF) policy, S6 section 6. Entries are {@code host:port}; {@code allowedPrivateHosts} also accepts a
     * bare host.
     */
    public record Egress(List<String> allowedHosts, List<String> allowedPrivateHosts, List<String> plainHttpHosts) {

        public Egress {
            allowedHosts = normalize(allowedHosts);
            allowedPrivateHosts = normalize(allowedPrivateHosts);
            plainHttpHosts = normalize(plainHttpHosts);
        }

        private static List<String> normalize(List<String> values) {
            if (values == null) {
                return List.of();
            }
            return values.stream().filter(v -> v != null && !v.isBlank())
                    .map(v -> v.trim().toLowerCase(Locale.ROOT)).distinct().toList();
        }

        public Set<String> allowedHostSet() {
            return Set.copyOf(allowedHosts);
        }

        public Set<String> privateHostSet() {
            return allowedPrivateHosts.stream().collect(Collectors.toUnmodifiableSet());
        }

        public Set<String> plainHttpHostSet() {
            return Set.copyOf(plainHttpHosts);
        }
    }

    public record Secrets(@NotNull Path dir) {
    }

    public record Limits(
            @Min(1024) int draftMaxBytes,
            @Min(1024) int requestMaxBytes,
            @Min(4) @Max(256) int jsonMaxDepth) {
    }

    public record Pagination(@NotBlank @Size(min = 32) String cursorSecret) {
    }

    public record Runtime(
            @NotBlank String lockOwner,
            @NotNull Duration asyncJobLockTime,
            @NotNull Duration resetExpiredJobsInterval,
            @Min(1) int executorCorePoolSize,
            @Min(1) int executorMaxPoolSize,
            @Min(1) int executorQueueSize) {
    }

    public record Migration(@NotBlank @Pattern(regexp = "^[a-z_][a-z0-9_]{0,62}$") String appRole) {
    }
}
