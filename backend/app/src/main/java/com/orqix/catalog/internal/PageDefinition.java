package com.orqix.catalog.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;

/**
 * UI02 / S111 application page shape. Data only: components pin exact view or extension versions, lists and details use
 * fixed field allowlists, and chains use the fixed step allowlist. Pin existence, view kinds, workflow compatibility and
 * extension approval are checked against the database by {@link PageReferences}.
 */
public final class PageDefinition {
    static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    static final Pattern ROUTE = Pattern.compile("/(?!/)[A-Za-z0-9/_-]{1,199}");
    public static final Set<String> TASK_FIELDS = Set.of("requestTitle", "reference", "label", "workflowName", "requester",
            "createdAt", "dueAt", "status", "priority");
    static final Map<String, Set<String>> LIST_COLUMNS = Map.of(
            "assignedTasks", TASK_FIELDS, "claimableTasks", TASK_FIELDS,
            "myRequests", Set.of("requestTitle", "reference", "workflowName", "lifecycle", "createdAt", "updatedAt"),
            "cases", Set.of("status", "definitionVersion", "version", "createdAt"));
    public static final Set<String> STEP_ACTIONS = Set.of("submit", "saveDraft", "claim", "requestCorrection", "approve",
            "reject", "saveCaseData", "suspendCase", "resumeCase", "closeCase", "startPlanItem", "completePlanItem",
            "planDiscretionaryItem", "navigate");
    /** Steps that act on one plan item of the case, named by its CMMN element id and resolved when the step runs. */
    public static final Set<String> PLAN_ITEM_STEPS = Set.of("startPlanItem", "completePlanItem", "planDiscretionaryItem");
    static final Set<String> CASE_STEPS = Set.of("saveCaseData", "suspendCase", "resumeCase", "closeCase", "startPlanItem",
            "completePlanItem", "planDiscretionaryItem");
    static final Pattern ELEMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9._-]{0,63}");
    static final Map<String, List<String>> LAYOUT_REGIONS = Map.of(
            "single", List.of("main"), "sidebar", List.of("main", "side"), "split", List.of("start", "end"));
    private static final Set<String> COMMON = Set.of("id", "type", "visibleWhen");
    private static final Map<String, Set<String>> KEYS = Map.of(
            "heading", Set.of("text"),
            "text", Set.of("text"),
            "view", Set.of("view", "workflowId", "label"),
            "list", Set.of("label", "source", "columns", "limit"),
            "details", Set.of("label", "source", "fields"),
            "actions", Set.of("label", "chains"),
            "extension", Set.of("label", "extension", "bindings", "chains", "height"));

    private PageDefinition() { }

    /** A component that pins a view or extension version. */
    public record Pin(String componentId, UUID id, int version) { }

    /** Facts extracted from a valid page for reference checks and runtime. */
    public record Parsed(String context, Map<String, JsonNode> components, Map<String, JsonNode> chains,
            List<Pin> views, List<Pin> extensions) { }

    public static Parsed validate(JsonNode node) {
        keys(node, "definition", Set.of("schemaVersion", "kind", "title", "context", "layout", "breakpoint", "regions", "chains"));
        if (!node.path("schemaVersion").isIntegralNumber() || node.path("schemaVersion").asInt() != 1) fail("schemaVersion", "must be 1");
        text(node, "title", 120, "definition");
        String context = text(node, "context", 10, "definition");
        if (!Set.of("none", "task", "case").contains(context)) fail("context", "must be none, task or case");
        String layout = text(node, "layout", 10, "definition");
        if (!LAYOUT_REGIONS.containsKey(layout)) fail("layout", "must be single, sidebar or split");
        if (!Set.of("sm", "md", "lg").contains(text(node, "breakpoint", 2, "definition"))) fail("breakpoint", "must be sm, md or lg");
        JsonNode regions = node.path("regions");
        List<String> expected = LAYOUT_REGIONS.get(layout);
        if (!regions.isArray() || regions.size() != expected.size()) fail("regions", "must match the " + layout + " layout");
        Set<String> ids = new HashSet<>();
        Map<String, JsonNode> components = new HashMap<>();
        List<Pin> views = new java.util.ArrayList<>();
        List<Pin> extensions = new java.util.ArrayList<>();
        int total = 0;
        for (int r = 0; r < regions.size(); r++) {
            JsonNode region = regions.get(r);
            String rp = "regions/" + r;
            object(region, rp);
            keys(region, rp, Set.of("id", "label", "components"));
            if (!expected.get(r).equals(region.path("id").asString())) fail(rp + "/id", "must be " + expected.get(r));
            text(region, "label", 120, rp);
            JsonNode list = region.path("components");
            if (!list.isArray() || list.size() > 12) fail(rp + "/components", "must contain at most 12 components");
            total += list.size();
            for (int c = 0; c < list.size(); c++) {
                JsonNode component = list.get(c);
                String cp = rp + "/components/" + c;
                object(component, cp);
                String id = id(component, cp, ids);
                String type = text(component, "type", 20, cp);
                if (!KEYS.containsKey(type)) fail(cp + "/type", "is not a supported component");
                Set<String> allowed = new HashSet<>(COMMON);
                allowed.addAll(KEYS.get(type));
                keys(component, cp, allowed);
                component(component, type, context, cp, views, extensions, id);
                components.put(id, component);
            }
        }
        if (total < 1 || total > 24) fail("regions", "must contain 1 to 24 components in total");
        JsonNode chains = node.path("chains");
        if (!chains.isArray() || chains.size() > 10) fail("chains", "must contain at most 10 chains");
        Map<String, JsonNode> chainById = new HashMap<>();
        for (int i = 0; i < chains.size(); i++) {
            JsonNode chain = chains.get(i);
            String path = "chains/" + i;
            object(chain, path);
            keys(chain, path, Set.of("id", "label", "stopOnFailure", "steps"));
            String id = id(chain, path, ids);
            text(chain, "label", 80, path);
            if (!chain.path("stopOnFailure").isBoolean() || !chain.path("stopOnFailure").asBoolean()) {
                fail(path + "/stopOnFailure", "must be true; a chain always stops at the first failed step");
            }
            steps(chain.path("steps"), path, components);
            chainById.put(id, chain);
        }
        for (var entry : components.entrySet()) {
            for (JsonNode chain : entry.getValue().path("chains")) {
                if (!chainById.containsKey(chain.asString())) fail("components/" + entry.getKey() + "/chains", "must reference page chains");
            }
        }
        return new Parsed(context, Map.copyOf(components), Map.copyOf(chainById), List.copyOf(views), List.copyOf(extensions));
    }

    private static void component(JsonNode component, String type, String context, String cp, List<Pin> views,
            List<Pin> extensions, String id) {
        if (component.has("visibleWhen")) condition(component.path("visibleWhen"), context, cp + "/visibleWhen");
        switch (type) {
            case "heading" -> text(component, "text", 200, cp);
            case "text" -> text(component, "text", 1000, cp);
            case "view" -> {
                views.add(pin(component.path("view"), cp + "/view", id));
                if (component.has("workflowId")) uuid(component.path("workflowId"), cp + "/workflowId");
                if (component.has("label")) text(component, "label", 120, cp);
            }
            case "list" -> {
                text(component, "label", 120, cp);
                String source = text(component, "source", 20, cp);
                if (!LIST_COLUMNS.containsKey(source)) fail(cp + "/source", "must be assignedTasks, claimableTasks, myRequests or cases");
                fields(component.path("columns"), cp + "/columns", 10, field -> LIST_COLUMNS.get(source).contains(field));
                JsonNode limit = component.path("limit");
                if (!limit.isIntegralNumber() || limit.asInt() < 1 || limit.asInt() > 50) fail(cp + "/limit", "must be 1 to 50");
            }
            case "details" -> {
                text(component, "label", 120, cp);
                String source = text(component, "source", 10, cp);
                if (!source.equals(context) || source.equals("none")) fail(cp + "/source", "must match the page context");
                fields(component.path("fields"), cp + "/fields", 20, field -> contextBinding(field, context));
            }
            case "actions" -> {
                if (component.has("label")) text(component, "label", 120, cp);
                chainIds(component.path("chains"), cp + "/chains", 1);
            }
            case "extension" -> {
                text(component, "label", 120, cp);
                extensions.add(pin(component.path("extension"), cp + "/extension", id));
                JsonNode bindings = component.path("bindings");
                if (!bindings.isArray() || bindings.size() > 20) fail(cp + "/bindings", "must contain at most 20 bindings");
                Set<String> seen = new HashSet<>();
                for (JsonNode binding : bindings) {
                    if (!binding.isString() || !contextBinding(binding.asString(), context) || !seen.add(binding.asString())) {
                        fail(cp + "/bindings", "must be distinct bindings available in the page context");
                    }
                }
                chainIds(component.path("chains"), cp + "/chains", 0);
                if (component.has("height")) {
                    JsonNode height = component.path("height");
                    if (!height.isIntegralNumber() || height.asInt() < 80 || height.asInt() > 800) fail(cp + "/height", "must be 80 to 800");
                }
            }
            default -> fail(cp + "/type", "is not a supported component");
        }
    }

    private static void steps(JsonNode steps, String path, Map<String, JsonNode> components) {
        if (!steps.isArray() || steps.isEmpty() || steps.size() > 5) fail(path + "/steps", "must contain 1 to 5 steps");
        int decisions = 0;
        boolean claimed = false;
        boolean corrected = false;
        boolean closed = false;
        Set<String> submitted = new HashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            JsonNode step = steps.get(i);
            String sp = path + "/steps/" + i;
            object(step, sp);
            keys(step, sp, Set.of("action", "component", "target", "planItem"));
            String action = text(step, "action", 24, sp);
            if (!STEP_ACTIONS.contains(action)) fail(sp + "/action", "is not in the action allowlist");
            if (PLAN_ITEM_STEPS.contains(action)) {
                if (!ELEMENT.matcher(text(step, "planItem", 64, sp)).matches()) fail(sp + "/planItem", "must be a CMMN element id");
            } else if (step.has("planItem")) {
                fail(sp + "/planItem", "is only used by plan-item steps");
            }
            if (closed && CASE_STEPS.contains(action)) fail(sp, "no case step can follow closing the case");
            if (action.equals("navigate")) {
                if (step.has("component")) fail(sp + "/component", "is not used by navigation");
                String target = text(step, "target", 200, sp);
                if (!ROUTE.matcher(target).matches() || target.contains("..")) fail(sp + "/target", "must be a local route");
                if (i != steps.size() - 1) fail(sp, "navigation must be the last step");
                continue;
            }
            if (step.has("target")) fail(sp + "/target", "is only allowed for navigation");
            String target = text(step, "component", 64, sp);
            JsonNode component = components.get(target);
            if (component == null || !"view".equals(component.path("type").asString())) {
                fail(sp + "/component", "must reference a view component on this page");
            }
            switch (action) {
                case "approve", "reject" -> {
                    if (++decisions > 1) fail(sp, "a chain can contain only one approve or reject step");
                }
                case "claim" -> {
                    if (claimed || corrected || decisions > 0) fail(sp, "claim may appear once, before a correction or decision");
                    claimed = true;
                }
                case "requestCorrection" -> {
                    if (corrected || decisions > 0) fail(sp, "a correction may appear once, before a decision");
                    corrected = true;
                }
                case "closeCase" -> closed = true;
                case "submit" -> {
                    if (!submitted.add(target)) fail(sp, "a request component can be submitted once per chain");
                }
                default -> { }
            }
        }
    }

    /** {@code task.<summary>}, {@code request.<field>} on task pages; {@code case.<field>} on case pages. */
    public static boolean contextBinding(String binding, String context) {
        int dot = binding.indexOf('.');
        if (dot < 1 || !NAME.matcher(binding.substring(dot + 1)).matches()) return false;
        String scope = binding.substring(0, dot);
        String name = binding.substring(dot + 1);
        return switch (context) {
            case "task" -> scope.equals("task") && TASK_FIELDS.contains(name) || scope.equals("request");
            case "case" -> scope.equals("case");
            default -> false;
        };
    }

    private static void condition(JsonNode condition, String context, String path) {
        object(condition, path);
        keys(condition, path, Set.of("binding", "equals"));
        if (!contextBinding(text(condition, "binding", 72, path), context)) fail(path + "/binding", "must be available in the page context");
        JsonNode equals = condition.path("equals");
        if (!(equals.isString() && equals.asString().length() <= 200 || equals.isNumber() || equals.isBoolean())) {
            fail(path + "/equals", "must be text of at most 200 characters, a number or a boolean");
        }
    }

    private static void fields(JsonNode fields, String path, int max, java.util.function.Predicate<String> allowed) {
        if (!fields.isArray() || fields.isEmpty() || fields.size() > max) fail(path, "must contain 1 to " + max + " fields");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            JsonNode field = fields.get(i);
            String fp = path + "/" + i;
            object(field, fp);
            keys(field, fp, Set.of("field", "label"));
            String name = text(field, "field", 72, fp);
            text(field, "label", 120, fp);
            if (!allowed.test(name) || !seen.add(name)) fail(fp + "/field", "is not an available distinct field");
        }
    }

    private static void chainIds(JsonNode chains, String path, int min) {
        if (!chains.isArray() || chains.size() < min || chains.size() > 10) fail(path, "must list " + min + " to 10 chains");
        Set<String> seen = new HashSet<>();
        for (JsonNode chain : chains) {
            if (!chain.isString() || !ID.matcher(chain.asString()).matches() || !seen.add(chain.asString())) fail(path, "must list distinct chain ids");
        }
    }

    private static Pin pin(JsonNode pin, String path, String component) {
        object(pin, path);
        keys(pin, path, Set.of("id", "version"));
        UUID id = uuid(pin.path("id"), path + "/id");
        JsonNode version = pin.path("version");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.asInt() < 1) fail(path + "/version", "must be an exact published version");
        return new Pin(component, id, version.asInt());
    }

    private static UUID uuid(JsonNode value, String path) {
        try {
            if (value.isString()) return UUID.fromString(value.asString());
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        throw invalid(path + " must be an id.");
    }

    private static String id(JsonNode node, String path, Set<String> ids) {
        String id = text(node, "id", 64, path);
        if (!ID.matcher(id).matches()) fail(path + "/id", "must be an identifier");
        if (!ids.add(id)) fail(path + "/id", "is duplicated");
        return id;
    }

    private static void object(JsonNode node, String path) {
        if (node == null || !node.isObject()) fail(path, "must be an object");
    }

    private static void keys(JsonNode node, String path, Set<String> allowed) {
        for (String name : node.propertyNames()) if (!allowed.contains(name)) fail(path + "/" + name, "is not supported");
    }

    private static String text(JsonNode node, String key, int max, String path) {
        JsonNode value = node.path(key);
        if (!value.isString() || value.asString().isBlank() || value.asString().length() > max
                || !value.asString().equals(value.asString().trim())) fail(path + "/" + key, "must be nonblank trimmed text");
        return value.asString();
    }

    static ApiException invalid(String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message);
    }

    private static void fail(String path, String reason) {
        throw invalid(path + " " + reason + ".");
    }
}
