package com.orqix.stub;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates request bodies against the {@code requestSchema} of the configured operation in the shared connection
 * definition ({@code examples/connections/procurement.connection.json}), so the stub can never drift from the contract.
 */
@Component
public class ContractValidator {

    private static final Logger log = LoggerFactory.getLogger(ContractValidator.class);

    /** One schema violation. */
    public record Violation(String path, String message) {
    }

    private final Schema schema;
    private final String idempotencyHeader;

    public ContractValidator(StubProperties properties, JsonMapper jsonMapper) throws IOException {
        JsonNode definition;
        try (InputStream in = properties.contractResource().getInputStream()) {
            definition = jsonMapper.readTree(in);
        }
        JsonNode operation = definition.path("operations").path(properties.contractOperation());
        JsonNode requestSchema = operation.path("requestSchema");
        if (requestSchema.isMissingNode() || !requestSchema.isObject()) {
            throw new IllegalStateException("Contract %s has no operations.%s.requestSchema"
                    .formatted(properties.contractResource(), properties.contractOperation()));
        }
        this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(requestSchema);
        String header = operation.path("idempotencyHeader").asString("");
        this.idempotencyHeader = header.isBlank() ? "Idempotency-Key" : header;
        log.info("loaded request schema of operation '{}' from {} (idempotency header: {})",
                properties.contractOperation(), properties.contractResource(), idempotencyHeader);
    }

    public String idempotencyHeader() {
        return idempotencyHeader;
    }

    public List<Violation> validate(JsonNode body) {
        List<Error> errors = schema.validate(body);
        return errors.stream()
                .map(error -> new Violation(error.getInstanceLocation().toString(), error.getMessage()))
                .toList();
    }
}
