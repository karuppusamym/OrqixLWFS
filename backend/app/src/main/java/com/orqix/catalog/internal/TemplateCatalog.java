package com.orqix.catalog.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * Built-in templates, packaged from {@code /examples} by the Maven build (never copied by hand). R0 ships the
 * purchase-approval template.
 */
@Component
public class TemplateCatalog {

    public record Template(String id, String name, String description, String typicalOwner, List<String> requiredInputs,
            List<String> requiredGroups, List<String> requiredConnections, List<String> requiredWorkflows,
            String definitionJson) {
    }

    /** Insertion order is the order of the template list. */
    private static final Map<String, String> RESOURCES = new LinkedHashMap<>();
    private static final Map<String, String> OWNERS = Map.of("purchase-approval", "Purchasing operations",
            "new-hire-onboarding", "People operations", "order-payment-wait", "Order operations", "case-with-stages", "Lending operations");

    static {
        RESOURCES.put("purchase-approval", "templates/purchase-approval.json");
        RESOURCES.put("new-hire-onboarding", "templates/new-hire-onboarding.json");
        RESOURCES.put("order-payment-wait", "templates/order-payment-wait.json");
        RESOURCES.put("case-with-stages", "templates/case-with-stages.json");
    }

    private final Json json;
    private final Map<String, Template> templates = new LinkedHashMap<>();

    public TemplateCatalog(Json json) {
        this.json = json;
        RESOURCES.forEach((id, resource) -> templates.put(id, load(id, resource)));
    }

    public List<Template> all() {
        return List.copyOf(templates.values());
    }

    public Optional<Template> find(String id) {
        return Optional.ofNullable(templates.get(id));
    }

    private Template load(String id, String resource) {
        String text;
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Template resource " + resource + " is missing", e);
        }
        JsonNode definition = json.parse(text);
        List<String> inputs = new ArrayList<>();
        definition.path("inputSchema").path("properties").propertyNames().forEach(inputs::add);
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        for (JsonNode node : definition.path("nodes")) {
            String group = node.path("config").path("candidateGroupRef").asString(null);
            if (group != null) {
                groups.add(group);
            }
        }
        List<String> connections = new ArrayList<>();
        for (JsonNode dependency : definition.path("dependencies").path("connections")) {
            connections.add(dependency.path("key").asString());
        }
        LinkedHashSet<String> workflows = new LinkedHashSet<>();
        for (JsonNode node : definition.path("nodes")) {
            String ref = node.path("config").path("workflowRef").asString(null);
            if (ref != null) {
                workflows.add(ref);
            }
        }
        return new Template(id, definition.path("name").asString(id),
                definition.path("metadata").path("description").asString(""), OWNERS.getOrDefault(id, "Workflow owner"),
                inputs, List.copyOf(groups), connections, List.copyOf(workflows), text);
    }
}
