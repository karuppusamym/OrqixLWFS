package com.orqix.sdk.spring;

import com.orqix.sdk.OrqixClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Auto-configuration of the Orqix SDK: an {@link OrqixClient} once {@code orqix.base-url} is set, and a worker loop
 * for the {@link OrqixWorker} methods unless {@code orqix.worker.enabled=false}.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "orqix", name = "base-url")
@EnableConfigurationProperties(OrqixProperties.class)
public class OrqixAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "orqix", name = "token-file")
    OrqixTokenProvider orqixTokenFile(OrqixProperties properties) {
        Path file = properties.getTokenFile();
        return () -> {
            try {
                return Files.readString(file).strip();
            } catch (IOException e) {
                throw new UncheckedIOException("The Orqix token file cannot be read", e);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    OrqixClient orqixClient(OrqixProperties properties, ObjectProvider<OrqixTokenProvider> tokens) {
        OrqixTokenProvider token = tokens.getIfAvailable();
        if (token == null) {
            throw new IllegalStateException("Orqix needs a token: define an OrqixTokenProvider bean or set "
                    + "orqix.token-file");
        }
        return OrqixClient.builder()
                .baseUrl(properties.getBaseUrl())
                .workspaceId(properties.getWorkspaceId())
                .token(token)
                .timeout(properties.getTimeout())
                .build();
    }

    @Bean
    @ConditionalOnBooleanProperty(name = "orqix.worker.enabled", matchIfMissing = true)
    static OrqixWorkerMethods orqixWorkerMethods() {
        return new OrqixWorkerMethods();
    }

    @Bean
    @ConditionalOnBooleanProperty(name = "orqix.worker.enabled", matchIfMissing = true)
    OrqixWorkerContainer orqixWorkerContainer(OrqixClient client, OrqixWorkerMethods methods,
            OrqixProperties properties, Environment environment) {
        String id = properties.getWorker().getId();
        if (id == null || id.isBlank()) {
            String name = environment.getProperty("spring.application.name", "orqix-worker");
            id = name + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return new OrqixWorkerContainer(client, methods, properties.getWorker(), id);
    }
}
