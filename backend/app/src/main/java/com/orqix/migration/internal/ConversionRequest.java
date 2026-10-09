package com.orqix.migration.internal;

import java.util.Set;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import tools.jackson.databind.JsonNode;

/** Validate before interpreting mappings: Jackson coercion must not change submitted migration intent. */
final class ConversionRequest {
    private static final String KEY = "^[a-z][a-z0-9-]{0,62}[a-z0-9]$";
    static void validate(JsonNode request) {
        object(request, Set.of("workflowKey", "name", "groupMappings", "approvalRoutes", "serviceTaskMappings", "inputSchema", "acknowledgeInfo"), "request");
        if (Json.depth(request) > 64) fail("request", "must have at most 64 levels");
        key(request.path("workflowKey"), "workflowKey");
        text(request.path("name"), "name", 200);
        if (request.has("acknowledgeInfo") && !request.path("acknowledgeInfo").isBoolean()) fail("acknowledgeInfo", "must be boolean");
        if (request.has("inputSchema") && !request.path("inputSchema").isObject()) fail("inputSchema", "must be an object");
        if (request.has("groupMappings")) {
            strings(request.path("groupMappings"), "groupMappings");
            request.path("groupMappings").properties().forEach(e -> key(e.getValue(), "groupMappings/" + e.getKey()));
        }
        if (request.has("approvalRoutes")) {
            object(request.path("approvalRoutes"), null, "approvalRoutes");
            request.path("approvalRoutes").properties().forEach(e -> {
                String path = "approvalRoutes/" + e.getKey();
                object(e.getValue(), Set.of("approvedFlowId", "rejectedFlowId"), path);
                text(e.getValue().path("approvedFlowId"), path + "/approvedFlowId", 1000);
                text(e.getValue().path("rejectedFlowId"), path + "/rejectedFlowId", 1000);
            });
        }
        if (request.has("serviceTaskMappings")) {
            object(request.path("serviceTaskMappings"), null, "serviceTaskMappings");
            request.path("serviceTaskMappings").properties().forEach(e -> {
                String path = "serviceTaskMappings/" + e.getKey(); JsonNode value = e.getValue();
                object(value, Set.of("connectionRef", "operation", "inputMapping", "resultMapping", "timeoutSeconds", "definitionVersion"), path);
                key(value.path("connectionRef"), path + "/connectionRef");
                text(value.path("operation"), path + "/operation", 1000);
                strings(value.path("inputMapping"), path + "/inputMapping");
                strings(value.path("resultMapping"), path + "/resultMapping");
                integer(value, "definitionVersion", 1, Integer.MAX_VALUE, path);
                integer(value, "timeoutSeconds", 1, 30, path);
            });
        }
    }
    private static void integer(JsonNode value, String name, int min, int max, String path) {
        if (!value.has(name)) return;
        JsonNode field = value.path(name);
        if (!field.isIntegralNumber() || !field.canConvertToInt() || field.asInt() < min || field.asInt() > max)
            fail(path + "/" + name, "must be an integer between " + min + " and " + max);
    }
    private static void strings(JsonNode node, String path) {
        object(node, null, path);
        node.properties().forEach(e -> { if (!e.getValue().isString()) fail(path + "/" + e.getKey(), "must be a string"); });
    }
    private static void key(JsonNode node, String path) {
        if (!node.isString() || !node.asString().matches(KEY)) fail(path, "must be a valid workflow/group/connection key");
    }
    private static void text(JsonNode node, String path, int max) {
        if (!node.isString() || node.asString().isBlank() || node.asString().length() > max) fail(path, "must be a nonblank string of at most " + max + " characters");
    }
    private static void object(JsonNode node, Set<String> allowed, String path) {
        if (node == null || !node.isObject()) fail(path, "must be an object");
        if (allowed != null) node.propertyNames().forEach(name -> { if (!allowed.contains(name)) fail(path + "/" + name, "is not a supported field"); });
    }
    private static void fail(String path, String message) { throw ApiException.malformed(path + " " + message + "."); }
}
