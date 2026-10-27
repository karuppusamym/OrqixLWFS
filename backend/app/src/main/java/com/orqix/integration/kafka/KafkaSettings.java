package com.orqix.integration.kafka;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Kafka consumer triggers (docs/build/S99 K2, K9, K11), deployment-owned. A workspace can only name one of these
 * clusters and one of its allowed topics; it can never type a broker address.
 *
 * @param mode       {@code off} (default: triggers can be configured, nothing consumes), {@code stub} (no broker; test
 *                   messages go through the same handler) or {@code live} (real consumers)
 * @param instanceId this replica's name in status rows; empty means host name plus a random suffix
 * @param clusters   named clusters
 */
@ConfigurationProperties(prefix = "orqix.kafka")
public record KafkaSettings(@DefaultValue("off") String mode, @DefaultValue("") String instanceId,
        Map<String, Cluster> clusters) {

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{0,39}$");
    private static final Pattern TOPIC = Pattern.compile("^[A-Za-z0-9._-]{1,249}$");
    private static final Set<String> PROTOCOLS = Set.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL");
    private static final Set<String> MECHANISMS = Set.of("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512");

    /**
     * @param bootstrapServers comma-separated host:port list
     * @param securityProtocol PLAINTEXT, SSL, SASL_PLAINTEXT or SASL_SSL
     * @param saslMechanism    PLAIN, SCRAM-SHA-256 or SCRAM-SHA-512 (with a SASL protocol; the trigger's secretRef file
     *                         holds {@code {"username": ..., "password": ...}})
     * @param allowedTopics    exact topic names triggers of any workspace may consume
     */
    public record Cluster(String bootstrapServers, @DefaultValue("SASL_SSL") String securityProtocol,
            @DefaultValue("SCRAM-SHA-512") String saslMechanism, List<String> allowedTopics) {
    }

    public KafkaSettings {
        if (!Set.of("off", "stub", "live").contains(mode)) {
            throw new IllegalArgumentException("orqix.kafka.mode must be off, stub or live");
        }
        clusters = clusters == null ? Map.of() : Map.copyOf(clusters);
        clusters.forEach((name, c) -> {
            if (!NAME.matcher(name).matches() || c.bootstrapServers() == null || c.bootstrapServers().isBlank()
                    || !PROTOCOLS.contains(c.securityProtocol()) || !MECHANISMS.contains(c.saslMechanism())
                    || (c.allowedTopics() != null && c.allowedTopics().stream().anyMatch(t -> !TOPIC.matcher(t).matches()))) {
                throw new IllegalArgumentException("orqix.kafka.clusters." + name + " is not a valid cluster");
            }
        });
    }

    public boolean live() {
        return "live".equals(mode);
    }

    public boolean stub() {
        return "stub".equals(mode);
    }

    public List<String> topics(String cluster) {
        Cluster c = clusters.get(cluster);
        return c == null || c.allowedTopics() == null ? List.of() : c.allowedTopics();
    }
}
