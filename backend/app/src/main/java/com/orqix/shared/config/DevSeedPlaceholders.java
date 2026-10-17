package com.orqix.shared.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.orqix.shared.json.Json;

/**
 * Placeholders for {@code db/dev-seed/R__dev_seed.sql}: the seed takes the purchase template and the procurement
 * connection definition from the files packaged from {@code /examples} (single source, never copied into SQL).
 * The placeholders are only referenced when the dev seed location is enabled (profile dev).
 */
@Component
public class DevSeedPlaceholders implements FlywayConfigurationCustomizer {

    static final String TEMPLATE = "templates/purchase-approval.json";
    static final String CONNECTION = "dev-seed/connections/procurement.connection.json";
    static final String VENDOR_AGENT = "dev-seed/connections/vendor-risk-assistant.connection.json";
    static final String VENDOR_TEMPLATE = "templates/vendor-onboarding-check.json";

    private final Environment environment;

    public DevSeedPlaceholders(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void customize(org.flywaydb.core.api.configuration.FluentConfiguration configuration) {
        Map<String, String> placeholders = new HashMap<>(configuration.getPlaceholders());
        String template = read(TEMPLATE);
        String connection = read(CONNECTION);
        placeholders.put("dev_purchase_template", template);
        placeholders.put("dev_procurement_definition", connection);
        placeholders.put("dev_procurement_definition_hash", Json.canonicalSha256(connection));
        String vendorAgent = read(VENDOR_AGENT);
        placeholders.put("dev_vendor_agent_definition", vendorAgent);
        placeholders.put("dev_vendor_agent_definition_hash", Json.canonicalSha256(vendorAgent));
        placeholders.put("dev_vendor_template", read(VENDOR_TEMPLATE));
        placeholders.put("dev_stub_base_url", environment.getProperty("orqix.dev-seed.stub-base-url",
                "http://localhost:8095"));
        configuration.placeholders(placeholders);
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing packaged resource " + path, e);
        }
    }
}
