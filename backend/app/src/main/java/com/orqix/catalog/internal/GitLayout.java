package com.orqix.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The deterministic Git layout of one workflow (CG-S01, docs/build/S147). Under
 * {@code <basePath>/workflows/<workflowKey>/}:
 * <ul>
 * <li>{@code workflow.json}: the canonical definition (keys sorted, two-space indent) without {@code provenance},
 * which records the commit itself and therefore cannot be part of the commit;</li>
 * <li>{@code tests.json}: the shared publication test cases, present when the workflow has any;</li>
 * <li>{@code generated/}: BPMN, DMN, the pinned references, connection definitions (never bindings), shared asset and
 * view versions, for review only. Orqix rewrites them on every push and ignores them on import;</li>
 * <li>{@code orqix-sync.json}: format version, draft revision and content hash, also ignored on import.</li>
 * </ul>
 * The <b>content hash</b> covers the source files only: SHA-256 over the sorted lines {@code path NUL canonical-sha256
 * LF}, where the per-file hash is RFC 8785 canonical JSON. Re-indenting a file in Git therefore keeps the hash.
 */
final class GitLayout {

    static final String FORMAT = "1";
    static final String WORKFLOW = "workflow.json";
    static final String TESTS = "tests.json";
    static final String GENERATED = "generated/";
    static final String SYNC = "orqix-sync.json";
    static final int MAX_FILES = 200;
    static final int MAX_SOURCE_BYTES = 512 * 1024;

    /** Member names that carry credentials in other formats; never accepted in a pushed or pulled file. */
    private static final Set<String> SECRET_KEYS = Set.of("secretref", "secret", "clientsecret", "password", "apikey",
            "privatekey", "accesstoken", "refreshtoken", "credential", "credentials", "authorization", "bearertoken");
    /** Well-known token shapes (GitHub, GitLab, Slack, AWS access keys, PEM private keys, JWT). */
    private static final Pattern SECRET_VALUE = Pattern.compile("(gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}"
            + "|glpat-[A-Za-z0-9_-]{20,}|xox[abprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----"
            + "|eyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})");

    private GitLayout() {
    }

    static String folder(String basePath, String workflowKey) {
        return basePath + "/workflows/" + workflowKey;
    }

    static boolean isSource(String relative) {
        return WORKFLOW.equals(relative) || TESTS.equals(relative);
    }

    /** The definition as it is stored in Git: provenance removed. */
    static ObjectNode sourceDefinition(JsonNode definition) {
        ObjectNode copy = (ObjectNode) definition.deepCopy();
        copy.remove("provenance");
        return copy;
    }

    /** The test file, or null when there are no cases. */
    static ObjectNode testsFile(JsonNode cases) {
        if (cases == null || !cases.isArray() || cases.isEmpty()) {
            return null;
        }
        ObjectNode tests = JsonNodeFactory.instance.objectNode();
        tests.put("formatVersion", FORMAT);
        tests.set("cases", cases.deepCopy());
        return tests;
    }

    /** Source files by relative path. */
    static Map<String, JsonNode> sources(JsonNode definition, JsonNode cases) {
        Map<String, JsonNode> sources = new TreeMap<>();
        sources.put(WORKFLOW, sourceDefinition(definition));
        ObjectNode tests = testsFile(cases);
        if (tests != null) {
            sources.put(TESTS, tests);
        }
        return sources;
    }

    static String contentHash(Json json, Map<String, JsonNode> sources) {
        StringBuilder lines = new StringBuilder();
        new TreeMap<>(sources).forEach((path, node) -> lines.append(path).append('\0')
                .append(Json.canonicalSha256(json.write(node))).append('\n'));
        return Json.sha256Hex(lines.toString());
    }

    static Map<String, String> fileHashes(Json json, Map<String, JsonNode> sources) {
        Map<String, String> hashes = new TreeMap<>();
        sources.forEach((path, node) -> hashes.put(path, Json.canonicalSha256(json.write(node))));
        return hashes;
    }

    /** Pretty JSON with object keys sorted, ending in a newline: stable bytes for the same content. */
    static byte[] pretty(Json json, JsonNode node) {
        return (json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(sorted(node)) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            List<String> names = new ArrayList<>();
            for (String name : node.propertyNames()) {
                names.add(name);
            }
            names.sort(null);
            for (String name : names) {
                out.set(name, sorted(node.get(name)));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> out.add(sorted(item)));
            return out;
        }
        return node;
    }

    /**
     * Refuses a file set that carries something credential-like. {@code token} is the resolved Git token, which must
     * never appear in a file either.
     */
    static void guard(Map<String, byte[]> files, String token) {
        List<String> found = new ArrayList<>();
        files.forEach((path, bytes) -> {
            String text = new String(bytes, StandardCharsets.UTF_8);
            if ((token != null && token.length() >= 8 && text.contains(token)) || SECRET_VALUE.matcher(text).find()) {
                found.add(path);
            }
        });
        if (!found.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A file looks like it contains a credential, so nothing"
                    + " was sent or saved. Secrets and bindings never go through Git.").with("paths", found);
        }
    }

    /** Refuses JSON with a credential-like member name (any depth). */
    static void guardJson(String path, JsonNode node) {
        List<String> pointers = new ArrayList<>();
        scan(node, "", pointers);
        if (!pointers.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The file " + path + " has members that look like"
                    + " credentials, so nothing was sent or saved. Secrets and bindings never go through Git.")
                    .with("paths", pointers.stream().limit(20).map(p -> path + "#" + p).toList());
        }
    }

    private static void scan(JsonNode node, String pointer, List<String> found) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                String child = pointer + "/" + e.getKey();
                if (SECRET_KEYS.contains(e.getKey().toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", ""))
                        && e.getValue().isString() && !e.getValue().asString().isEmpty()) {
                    found.add(child);
                }
                scan(e.getValue(), child, found);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                scan(node.get(i), pointer + "/" + i, found);
            }
        }
    }

    /** One change between the current draft and the incoming files. */
    record Change(String kind, String area, String title) {
    }

    /** Structural differences: steps, routes, rule tables, request fields, other settings and test cases. */
    static List<Change> diff(JsonNode before, JsonNode after, JsonNode testsBefore, JsonNode testsAfter) {
        List<Change> changes = new ArrayList<>();
        byId(changes, "step", before.path("nodes"), after.path("nodes"), "id", "label");
        byId(changes, "route", before.path("transitions"), after.path("transitions"), "id", "label");
        byId(changes, "ruleTable", before.path("decisions"), after.path("decisions"), "key", "name");
        Map<String, JsonNode> fieldsBefore = members(before.path("inputSchema").path("properties"));
        Map<String, JsonNode> fieldsAfter = members(after.path("inputSchema").path("properties"));
        compare(changes, "field", fieldsBefore, fieldsAfter, k -> k);
        Set<String> structural = Set.of("nodes", "transitions", "decisions", "inputSchema", "provenance");
        Map<String, JsonNode> restBefore = new TreeMap<>(members(before));
        Map<String, JsonNode> restAfter = new TreeMap<>(members(after));
        structural.forEach(restBefore::remove);
        structural.forEach(restAfter::remove);
        compare(changes, "setting", restBefore, restAfter, k -> k);
        byId(changes, "test", testsBefore == null ? JsonNodeFactory.instance.arrayNode() : testsBefore,
                testsAfter == null ? JsonNodeFactory.instance.arrayNode() : testsAfter, "id", "name");
        return changes;
    }

    private static void byId(List<Change> changes, String area, JsonNode before, JsonNode after, String id, String label) {
        Map<String, JsonNode> a = new LinkedHashMap<>();
        Map<String, JsonNode> b = new LinkedHashMap<>();
        before.forEach(n -> a.put(n.path(id).asString(""), n));
        after.forEach(n -> b.put(n.path(id).asString(""), n));
        compare(changes, area, a, b, k -> {
            JsonNode n = b.containsKey(k) ? b.get(k) : a.get(k);
            String text = n.path(label).asString("");
            return text.isBlank() ? k : text + " (" + k + ")";
        });
    }

    private static void compare(List<Change> changes, String area, Map<String, JsonNode> before,
            Map<String, JsonNode> after, java.util.function.Function<String, String> title) {
        for (String key : before.keySet()) {
            if (!after.containsKey(key)) {
                changes.add(new Change("removed", area, title.apply(key)));
            } else if (!before.get(key).equals(after.get(key))) {
                changes.add(new Change("changed", area, title.apply(key)));
            }
        }
        for (String key : after.keySet()) {
            if (!before.containsKey(key)) {
                changes.add(new Change("added", area, title.apply(key)));
            }
        }
    }

    private static Map<String, JsonNode> members(JsonNode node) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (node.isObject()) {
            node.properties().forEach(e -> out.put(e.getKey(), e.getValue()));
        }
        return out;
    }
}
