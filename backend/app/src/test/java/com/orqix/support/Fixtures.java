package com.orqix.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;

/** Shared fixtures are loaded from /examples, never copied into test folders (docs/build/README.md section 6). */
public final class Fixtures {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private Fixtures() {
    }

    public static Path repoRoot() {
        return Path.of(System.getProperty("orqix.repoRoot", "../.."));
    }

    public static JsonNode load(String relative) {
        try {
            return MAPPER.readTree(Files.readString(repoRoot().resolve(relative)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode purchaseTemplate() {
        return load("examples/purchase-approval.json");
    }

    public static JsonNode purchaseScenarios() {
        return load("examples/fixtures/purchase-scenarios.json");
    }

    public static JsonNode procurementConnection() {
        return load("examples/connections/procurement.connection.json");
    }
}
