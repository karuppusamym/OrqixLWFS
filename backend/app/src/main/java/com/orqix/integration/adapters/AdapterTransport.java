package com.orqix.integration.adapters;

import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Delivery port of the IN02 adapters. Only {@link LocalStubTransport} exists: SMTP, Teams, JDBC and Kafka clients are not
 * linked into this build, so no adapter can reach a remote system. Live acceptance is an external gate (docs 22).
 */
public interface AdapterTransport {

    /**
     * @param outcome always {@code stubbed} for the local transport
     * @param detail  adapter specific, contains no secret and no message body
     */
    record Result(String outcome, ObjectNode detail) {
    }

    Result execute(AdapterRules.Kind kind, UUID workspaceId, String adapterKey, JsonNode config, JsonNode payload);
}
