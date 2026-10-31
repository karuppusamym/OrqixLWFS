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
import java.util.TreeMap;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * Built-in templates, packaged from {@code /examples} by the Maven build (never copied by hand). R0 ships the
 * purchase-approval template. The regional regulated template packs ({@code examples/pack-*.json},
 * docs/regional-template-packs.md) are discovered by file name and describe themselves through metadata tags:
 * {@code pack:}, {@code region:}, {@code owner:} and {@code lang:}.
 */
@Component
public class TemplateCatalog {

    public record Template(String id, String name, String description, String typicalOwner, List<String> requiredInputs,
            List<String> requiredGroups, List<String> requiredConnections, List<String> requiredWorkflows,
            String definitionJson, String pack, String industry, String region, List<String> languages) {
    }

    /** Insertion order is the order of the template list. */
    private static final Map<String, String> RESOURCES = new LinkedHashMap<>();
    private static final Map<String, String> OWNERS = Map.of("purchase-approval", "Purchasing operations",
            "new-hire-onboarding", "People operations", "order-payment-wait", "Order operations", "case-with-stages", "Lending operations");

    private static final Map<String, String> INDUSTRIES = Map.of("banking-mfi", "Banking and microfinance",
            "shared-services", "Shared services", "public-sector", "Public sector");
    private static final Map<String, String> REGIONS = Map.of("india", "India", "eu", "European Union", "kenya", "Kenya",
            "nigeria", "Nigeria");

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
        packResources().forEach((id, resource) -> templates.put(id, load(id, resource)));
    }

    /** Regional pack templates in file-name order (region, then template kind). */
    private static Map<String, String> packResources() {
        Map<String, String> found = new TreeMap<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:templates/pack-*.json")) {
                String file = r.getFilename();
                if (file != null && file.matches("pack-[a-z]{2}-[a-z-]+\\.json")) {
                    found.put(file.substring(0, file.length() - ".json".length()), "templates/" + file);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Template packs cannot be listed", e);
        }
        return found;
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
        Map<String, String> tags = new LinkedHashMap<>();
        List<String> languages = new ArrayList<>();
        for (JsonNode tag : definition.path("metadata").path("tags")) {
            String value = tag.asString("");
            int colon = value.indexOf(':');
            if (colon > 0 && value.substring(0, colon).equals("lang")) {
                languages.add(value.substring(colon + 1));
            } else if (colon > 0) {
                tags.putIfAbsent(value.substring(0, colon), value.substring(colon + 1));
            }
        }
        String pack = tags.get("pack");
        String region = tags.get("region");
        String owner = OWNERS.getOrDefault(id, tags.getOrDefault("owner", "Workflow owner"));
        return new Template(id, definition.path("name").asString(id),
                definition.path("metadata").path("description").asString(""), owner,
                inputs, List.copyOf(groups), connections, List.copyOf(workflows), text, pack,
                pack == null ? null : INDUSTRIES.getOrDefault(pack, pack),
                region == null ? null : REGIONS.getOrDefault(region, region), List.copyOf(languages));
    }
}
