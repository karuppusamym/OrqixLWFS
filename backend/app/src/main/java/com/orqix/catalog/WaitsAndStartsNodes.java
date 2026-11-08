package com.orqix.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Release views of the S139 waits that are not steps of their own (docs/build/S139):
 * <ul>
 * <li>a branch {@code <step>__<branch>} of "Wait for the first" (CG-W07) with kind message, callback or check is seen
 * as a {@code waitForMessage} node, so the message, callback and safety-net services handle it like any message wait
 * (payload validation, callback tokens, checks). A check branch's done condition names the step's values
 * ({@code /steps/<step>/x}); here they become the branch's own ({@code /steps/<step>__<branch>/x}).</li>
 * <li>a message handler (CG-E08, a subprocess with a message trigger) is seen as a {@code messageHandler} node with the
 * message's declared values.</li>
 * </ul>
 */
public final class WaitsAndStartsNodes {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private WaitsAndStartsNodes() {
    }

    /** @return the synthesized node, or null when the id names neither a branch nor a message handler */
    public static JsonNode node(JsonNode definition, String nodeId) {
        int at = nodeId.indexOf("__");
        if (at > 0) {
            return branch(definition, nodeId.substring(0, at), nodeId.substring(at + 2));
        }
        for (JsonNode s : definition.path("subprocesses")) {
            JsonNode trigger = s.path("trigger");
            if (nodeId.equals(s.path("id").asString(null)) && "message".equals(trigger.path("type").asString(""))) {
                ObjectNode node = F.objectNode();
                node.put("id", nodeId);
                node.put("type", "messageHandler");
                node.put("label", s.path("label").asString(nodeId));
                ObjectNode config = node.putObject("config");
                config.put("message", trigger.path("message").asString());
                config.put("correlationField", trigger.path("correlationField").asString());
                config.set("payload", trigger.path("payload").isArray() ? trigger.get("payload") : F.arrayNode());
                config.put("interrupting", s.path("interrupting").asBoolean(false));
                return node;
            }
        }
        return null;
    }

    /** @return the "Wait for the first" step a branch id belongs to, or null */
    public static JsonNode firstStep(JsonNode definition, String elementId) {
        int at = elementId.indexOf("__");
        if (at <= 0) {
            return null;
        }
        String stepId = elementId.substring(0, at);
        for (JsonNode n : definition.path("nodes")) {
            if (stepId.equals(n.path("id").asString(null)) && "waitForFirst".equals(n.path("type").asString(""))) {
                return n;
            }
        }
        return null;
    }

    private static JsonNode branch(JsonNode definition, String stepId, String branchId) {
        JsonNode step = firstStep(definition, stepId + "__" + branchId);
        if (step == null) {
            return null;
        }
        for (JsonNode b : step.path("config").path("branches")) {
            if (!branchId.equals(b.path("id").asString(null))) {
                continue;
            }
            String kind = b.path("kind").asString("");
            if (!kind.equals("message") && !kind.equals("callback") && !kind.equals("check")) {
                return null;
            }
            ObjectNode node = F.objectNode();
            String id = stepId + "__" + branchId;
            node.put("id", id);
            node.put("type", "waitForMessage");
            node.put("label", step.path("label").asString(stepId) + ": " + b.path("label").asString(branchId));
            node.put("waitForFirst", stepId);
            node.put("branch", branchId);
            ObjectNode config = node.putObject("config");
            config.put("message", kind.equals("message") ? b.path("message").asString() : internalMessage(stepId, branchId));
            if (kind.equals("message")) {
                config.put("correlationField", b.path("correlationField").asString());
            }
            config.set("payload", b.path("payload").isArray() ? b.get("payload") : F.arrayNode());
            if (kind.equals("callback")) {
                config.putObject("externalCallback").put("tokenValidFor", b.path("tokenValidFor").asString());
            }
            if (kind.equals("check")) {
                config.set("fallbackCheck", ownRefs(b.path("check").deepCopy(), "/steps/" + stepId + "/",
                        "/steps/" + id + "/"));
            }
            return node;
        }
        return null;
    }

    /** Rewrites field references of the step's values to the branch's own. */
    private static JsonNode ownRefs(JsonNode node, String from, String to) {
        if (node instanceof ObjectNode object) {
            for (String name : java.util.List.copyOf(object.propertyNames())) {
                JsonNode value = object.get(name);
                if (value.isString() && "fieldRef".equals(name) && value.asString().startsWith(from)) {
                    object.set(name, StringNode.valueOf(to + value.asString().substring(from.length())));
                } else {
                    ownRefs(value, from, to);
                }
            }
        } else if (node != null && node.isArray()) {
            node.forEach(child -> ownRefs(child, from, to));
        }
        return node;
    }

    /** The same internal message name as the compiler's {@code Node.WaitForFirst.internalMessage}. */
    public static String internalMessage(String stepId, String branchId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((stepId + "/" + branchId).getBytes(StandardCharsets.UTF_8));
            return "orqix-" + branchId + "-" + HexFormat.of().formatHex(digest).substring(0, 10);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
