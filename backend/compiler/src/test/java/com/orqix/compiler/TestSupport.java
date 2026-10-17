package com.orqix.compiler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.orqix.compiler.internal.json.JsonPointers;
import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Fixture loading, RFC 6902 patching and context mapping shared by the compiler tests. */
public final class TestSupport {

    private TestSupport() {
    }

    public static Path repoRoot() {
        String root = System.getProperty("orqix.repoRoot");
        if (root == null) {
            // Running from an IDE without the surefire property: backend/compiler is the working directory.
            return Path.of("../..").toAbsolutePath().normalize();
        }
        return Path.of(root).toAbsolutePath().normalize();
    }

    public static String read(String repoRelative) {
        try {
            return Files.readString(repoRoot().resolve(repoRelative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode json(String repoRelative) {
        return StrictJson.parse(read(repoRelative));
    }

    public static String purchaseJson() {
        return read("examples/purchase-approval.json");
    }

    public static ObjectNode purchase() {
        return (ObjectNode) json("examples/purchase-approval.json");
    }

    public static String onboardingJson() {
        return read("examples/new-hire-onboarding.json");
    }

    public static ObjectNode onboarding() {
        return (ObjectNode) json("examples/new-hire-onboarding.json");
    }

    /** Release id used for the pinned {@code purchase-approval} child in tests. */
    public static final String PURCHASE_RELEASE_ID = "00000000-0000-7000-8000-0000000000a1";

    /**
     * The default workspace plus the groups of the onboarding example and {@code purchase-approval} as a published
     * workflow (release 1) that other workflows may start.
     */
    public static CompileContext onboardingContext() {
        CompileContext base = defaultContext();
        Map<String, CompileContext.GroupInfo> groups = new LinkedHashMap<>(base.groups());
        groups.put("it-desk", new CompileContext.GroupInfo("it-desk", "IT service desk", 1));
        groups.put("facilities-team", new CompileContext.GroupInfo("facilities-team", "Facilities team", 1));
        Map<String, CompileContext.WorkflowInfo> workflows = new LinkedHashMap<>();
        workflows.put("purchase-approval", purchaseAsChild(1));
        return new CompileContext(base.selfApprovalAllowed(), groups, base.connectionVersions(),
                base.enabledConnectionBindings(), workflows);
    }

    /** The purchase template as a published child workflow (it starts no workflow itself). */
    public static CompileContext.WorkflowInfo purchaseAsChild(int releaseNumber) {
        String semanticHash = Compilers.workflowCompiler().validate(purchaseJson(), defaultContext()).semanticHash();
        return CompileContext.WorkflowInfo.of("purchase-approval", "Purchase approval", PURCHASE_RELEASE_ID,
                releaseNumber, semanticHash, purchaseJson(), Set.of(), 0);
    }

    /** @return the default fixture workspace of compiler-cases.json */
    public static CompileContext defaultContext() {
        JsonNode cases = json("examples/fixtures/compiler-cases.json");
        return context(cases.get("defaultContext"), null);
    }

    /** Maps a fixture context (with optional per-case override) to a {@link CompileContext}. */
    public static CompileContext context(JsonNode defaults, JsonNode override) {
        JsonNode o = override == null ? JsonNodeFactory.instance.objectNode() : override;
        boolean test = (o.has("workspaceIsTest") ? o : defaults).get("workspaceIsTest").booleanValue();
        Map<String, CompileContext.GroupInfo> groups = new LinkedHashMap<>();
        for (JsonNode g : (o.has("groups") ? o : defaults).get("groups")) {
            groups.put(g.stringValue(), new CompileContext.GroupInfo(g.stringValue(), g.stringValue(), 1));
        }
        List<CompileContext.ConnectionVersion> versions = new ArrayList<>();
        Set<String> enabled = new HashSet<>();
        for (JsonNode c : (o.has("connections") ? o : defaults).get("connections")) {
            String definition = read(c.get("definition").stringValue());
            versions.add(new CompileContext.ConnectionVersion(c.get("key").stringValue(),
                    c.get("definitionVersion").intValue(), definition, CanonicalJson.sha256Hex(definition)));
            if (c.get("bindingEnabled").booleanValue()) {
                enabled.add(c.get("key").stringValue());
            }
        }
        return new CompileContext(test, groups, versions, enabled);
    }

    /** Applies RFC 6902 add/remove/replace operations to a copy of the document. */
    public static JsonNode patch(JsonNode document, JsonNode operations) {
        JsonNode doc = document.deepCopy();
        for (JsonNode op : operations) {
            List<String> tokens = JsonPointers.parse(op.get("path").stringValue());
            String last = tokens.get(tokens.size() - 1);
            JsonNode parent = doc;
            for (String token : tokens.subList(0, tokens.size() - 1)) {
                parent = parent.isArray() ? parent.get(Integer.parseInt(token)) : parent.get(token);
                if (parent == null) {
                    throw new IllegalArgumentException("Patch path does not exist: " + op.get("path"));
                }
            }
            String kind = op.get("op").stringValue();
            if (parent instanceof ObjectNode object) {
                switch (kind) {
                    case "add" -> object.set(last, op.get("value").deepCopy());
                    case "replace" -> {
                        require(object.has(last), op);
                        object.set(last, op.get("value").deepCopy());
                    }
                    case "remove" -> {
                        require(object.has(last), op);
                        object.remove(last);
                    }
                    default -> throw new IllegalArgumentException("Unsupported op " + kind);
                }
            } else if (parent instanceof ArrayNode array) {
                switch (kind) {
                    case "add" -> {
                        if (last.equals("-")) {
                            array.add(op.get("value").deepCopy());
                        } else {
                            int index = Integer.parseInt(last);
                            require(index <= array.size(), op);
                            array.insert(index, op.get("value").deepCopy());
                        }
                    }
                    case "replace" -> {
                        int index = Integer.parseInt(last);
                        require(index < array.size(), op);
                        array.set(index, op.get("value").deepCopy());
                    }
                    case "remove" -> {
                        int index = Integer.parseInt(last);
                        require(index < array.size(), op);
                        array.remove(index);
                    }
                    default -> throw new IllegalArgumentException("Unsupported op " + kind);
                }
            } else {
                throw new IllegalArgumentException("Patch parent is not a container: " + op.get("path"));
            }
        }
        return doc;
    }

    private static void require(boolean condition, JsonNode op) {
        if (!condition) {
            throw new IllegalArgumentException("Patch target does not exist: " + op);
        }
    }

    /** @return a copy with object members in a different (seeded random) order at every level */
    public static JsonNode shuffleKeys(JsonNode node, Random random) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>(node.propertyNames());
            Collections.shuffle(names, random);
            if (names.size() > 1 && names.equals(new ArrayList<>(node.propertyNames()))) {
                Collections.reverse(names);
            }
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            for (String name : names) {
                copy.set(name, shuffleKeys(node.get(name), random));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(e -> copy.add(shuffleKeys(e, random)));
            return copy;
        }
        return node;
    }

    /** @return compact JSON text (members in stored order) */
    public static String text(JsonNode node) {
        return StrictJson.write(node);
    }

    public static List<String> codes(List<ValidationIssue> issues, Severity severity) {
        return issues.stream().filter(i -> i.severity() == severity).map(ValidationIssue::code).toList();
    }
}
