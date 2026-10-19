package com.orqix.compiler.internal.bpmn;

import static com.orqix.compiler.internal.bpmn.BpmnGenerator.BPMNDI_NS;
import static com.orqix.compiler.internal.bpmn.BpmnGenerator.BPMN_NS;
import static com.orqix.compiler.internal.bpmn.BpmnGenerator.DC_NS;
import static com.orqix.compiler.internal.bpmn.BpmnGenerator.FLOWABLE_NS;
import static com.orqix.compiler.internal.bpmn.BpmnGenerator.ORQIX_NS;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.orqix.compiler.internal.bpmn.BpmnXml.El;
import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads the BPMN dialect written by {@link BpmnGenerator} back into the process part of a workflow document: the
 * workflow name, {@code nodes}, {@code transitions} and (when the diagram is present) {@code uiLayout.positions}
 * (UI03, docs/build/S56). It is the exact inverse of the generator for what the generator writes from these
 * sections; anything else is reported as a {@link Finding}, never skipped. Values the generator derives from other
 * sections (retry cycles, pinned versions, hashes, diagram lines) are not read here: the caller regenerates the BPMN
 * and compares, which is what proves that nothing in the text was lost.
 * <p>
 * Optional values are written back the way the studio stores them: {@code atTime} 09:00, {@code leaseSeconds} 120,
 * {@code required: false} on payload/result fields and the parallel {@code mode} "all" are left out. Callers that compare
 * with or apply to a draft give the result the draft's own spelling of these defaults with {@link BpmnDefaults#adopt}.
 */
public final class BpmnReader {

    /** Something in the BPMN that the workflow document cannot hold. */
    public record Finding(String kind, String elementId, String message) {
    }

    /** {@code graph}: {workflowKey, name, nodes[], transitions[], positions{}|null}; null when nothing could be read. */
    public record Result(ObjectNode graph, List<Finding> findings) {
        public boolean clean() {
            return findings.isEmpty();
        }
    }

    public static final String UNSUPPORTED = "unsupported";

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Pattern APPROVAL_ROUTE = Pattern.compile(
            "^\\$\\{orqixRoute\\.(approved|rejected)\\(execution, '([^']*)'\\)\\}$");
    private static final Pattern CONDITION_ROUTE = Pattern.compile(
            "^\\$\\{orqixRoute\\.condition\\(execution, '([^']*)'\\)\\}$");
    private static final Set<String> FLOW_ELEMENTS = Set.of("startEvent", "userTask", "serviceTask",
            "exclusiveGateway", "parallelGateway", "inclusiveGateway", "intermediateCatchEvent", "eventBasedGateway",
            "endEvent", "sequenceFlow", "boundaryEvent");
    private static final BigDecimal CARD_HALF_WIDTH = new BigDecimal(110);
    private static final BigDecimal CARD_HALF_HEIGHT = new BigDecimal(36);

    private final List<Finding> findings = new ArrayList<>();
    private final Map<String, El> byId = new LinkedHashMap<>();
    private final Map<String, String> types = new LinkedHashMap<>();
    private final Map<String, ObjectNode> nodes = new LinkedHashMap<>();

    private BpmnReader() {
    }

    public static Result read(El root) {
        return new BpmnReader().run(root);
    }

    private Result run(El root) {
        if (!root.is(BPMN_NS, "definitions")) {
            unsupported(null, "The root element is " + root.label() + "; a BPMN 2.0 bpmn:definitions element is expected.");
            return new Result(null, List.copyOf(findings));
        }
        List<El> processes = root.all(BPMN_NS, "process");
        El diagram = null;
        int diagrams = 0;
        for (El child : root.children()) {
            if (child.is(BPMN_NS, "process") || child.is(BPMN_NS, "message") || child.is(BPMN_NS, "error")) {
                continue;
            }
            if (child.is(BPMNDI_NS, "BPMNDiagram")) {
                diagram = child;
                diagrams++;
                continue;
            }
            String what = switch (child.local()) {
                case "collaboration" -> "Pools and collaborations (bpmn:collaboration) are not part of a workflow document.";
                case "signal", "escalation", "dataStore", "itemDefinition", "resource", "interface" ->
                    child.label() + " definitions are not supported by the workflow document.";
                default -> child.label() + " is not supported at this level.";
            };
            unsupported(child.attr("id"), what);
        }
        if (processes.size() != 1) {
            unsupported(null, processes.isEmpty() ? "There is no bpmn:process." : "There are " + processes.size()
                    + " bpmn:process elements; a workflow is exactly one process.");
            return new Result(null, List.copyOf(findings));
        }
        if (diagrams > 1) {
            unsupported(null, "There are " + diagrams + " diagrams; at most one bpmndi:BPMNDiagram is supported.");
        }
        El process = processes.get(0);
        ObjectNode graph = F.objectNode();
        graph.put("workflowKey", process.attr("id"));
        if (process.attr("name") == null) {
            unsupported(process.attr("id"), "The process has no name; the name attribute is the workflow name.");
        } else {
            graph.put("name", process.attr("name"));
        }
        List<El> flows = new ArrayList<>();
        List<El> helpers = new ArrayList<>();
        for (El el : process.children()) {
            if (el.is(BPMN_NS, "extensionElements")) {
                continue;
            }
            String id = el.attr("id");
            if (!el.ns().equals(BPMN_NS) || !FLOW_ELEMENTS.contains(el.local())) {
                unsupported(id, describeUnsupported(el));
                continue;
            }
            if (id == null || id.isEmpty()) {
                unsupported(null, el.label() + " on line " + el.line() + " has no id.");
                continue;
            }
            if (byId.putIfAbsent(id, el) != null) {
                unsupported(id, "The id '" + id + "' is used by more than one element.");
                continue;
            }
            if (id.contains("__")) {
                helpers.add(el);
            } else if (el.is(BPMN_NS, "sequenceFlow")) {
                flows.add(el);
            } else if (el.is(BPMN_NS, "boundaryEvent")) {
                unsupported(id, "Boundary events are only generated for a step's time limit or failure path (ids ending "
                        + "in __timeout or __failed); this one is not part of a workflow document.");
            } else {
                readNode(el);
            }
        }
        for (El helper : helpers) {
            checkHelper(helper);
        }
        ArrayNode nodeArray = graph.putArray("nodes");
        nodes.values().forEach(nodeArray::add);
        ArrayNode transitions = graph.putArray("transitions");
        for (El flow : flows) {
            ObjectNode t = readFlow(flow);
            if (t != null) {
                transitions.add(t);
            }
        }
        inferJoins(transitions);
        graph.set("positions", diagram == null ? null : positions(diagram, process.attr("id")));
        return new Result(graph, List.copyOf(findings));
    }

    // ------------------------------------------------------------------------------------------------ steps

    private void readNode(El el) {
        String id = el.attr("id");
        String name = el.attr("name");
        if (name == null) {
            unsupported(id, el.label() + " '" + id + "' has no name; the name is the step label.");
        }
        ObjectNode config = F.objectNode();
        String type;
        El ext = el.child(BPMN_NS, "extensionElements");
        switch (el.local()) {
            case "startEvent" -> type = "start";
            case "userTask" -> {
                type = "approval";
                approval(el, ext, config);
            }
            case "serviceTask" -> type = service(el, ext, config);
            case "exclusiveGateway" -> type = "branch";
            case "parallelGateway", "inclusiveGateway" -> {
                boolean join = "true".equals(el.attr(FLOWABLE_NS, "async"));
                type = join ? "parallelJoin" : "parallelSplit";
                if (!join && el.local().equals("inclusiveGateway")) {
                    config.put("mode", "inclusive");
                }
            }
            case "intermediateCatchEvent" -> type = catchEvent(el, ext, config);
            case "eventBasedGateway" -> type = messageWithLimit(el, config);
            case "endEvent" -> {
                type = "end";
                El outcome = ext == null ? null : ext.child(ORQIX_NS, "outcome");
                if (outcome == null) {
                    unsupported(id, "End event '" + id + "' has no orqix:outcome (business outcome and kind).");
                } else {
                    putText(config, "businessOutcome", outcome.attr("label"));
                    putText(config, "outcomeKind", outcome.attr("kind"));
                }
            }
            default -> throw new IllegalStateException(el.local());
        }
        if (type == null) {
            return;
        }
        types.put(id, type);
        ObjectNode node = F.objectNode();
        node.put("id", id);
        node.put("type", type);
        if (name != null) {
            node.put("label", name);
        }
        node.set("config", config);
        nodes.put(id, node);
    }

    private void approval(El el, El ext, ObjectNode config) {
        String id = el.attr("id");
        putText(config, "candidateGroupRef", el.attr(FLOWABLE_NS, "candidateGroups"));
        El a = ext == null ? null : ext.child(ORQIX_NS, "approval");
        if (a == null) {
            unsupported(id, "User task '" + id + "' has no orqix:approval; only Orqix approval steps are supported "
                    + "(assignees, forms and listeners of other engines are not).");
            return;
        }
        String reason = a.attr("rejectReasonRequired");
        if (reason != null) {
            Boolean value = bool(id, "rejectReasonRequired", reason);
            if (value != null) {
                config.put("rejectReasonRequired", value);
            }
        }
        if (a.attr("dueAfterHours") != null) {
            Integer hours = integer(id, "dueAfterHours", a.attr("dueAfterHours"));
            if (hours != null) {
                config.put("dueAfterHours", hours);
            }
        }
        if (a.attr("dueAfterMinutes") != null) {
            Integer minutes = integer(id, "dueAfterMinutes", a.attr("dueAfterMinutes"));
            if (minutes != null) {
                config.put("dueAfterMinutes", minutes);
            }
        }
        putText(config, "timeoutAfter", a.attr("timeoutAfter"));
        if (a.attr("requiredSkills") != null) {
            ArrayNode skills = config.putArray("requiredSkills");
            for (String skill : a.attr("requiredSkills").split(",", -1)) {
                skills.add(skill);
            }
        }
        jsonAttribute(id, a, "calendar", config);
        jsonAttribute(id, a, "escalation", config);
        El loop = el.child(BPMN_NS, "multiInstanceLoopCharacteristics");
        if (loop != null) {
            El cardinality = loop.child(BPMN_NS, "loopCardinality");
            Integer approvers = cardinality == null ? null : integer(id, "loopCardinality", cardinality.text());
            if (approvers == null) {
                unsupported(id, "The multi-instance settings of '" + id + "' need a loopCardinality (the number of "
                        + "different approvers).");
            } else {
                config.putObject("multiInstance").put("approvers", approvers);
            }
        }
    }

    private String service(El el, El ext, ObjectNode config) {
        String id = el.attr("id");
        String delegate = el.attr(FLOWABLE_NS, "delegateExpression");
        String kind = delegate == null ? null : switch (delegate) {
            case "${orqixDecisionTask}" -> "decision";
            case "${orqixHttpTask}" -> "http";
            case "${orqixGenerateDocumentTask}" -> "generateDocument";
            case "${orqixExternalTask}" -> "externalTask";
            case "${orqixNotificationTask}" -> "notification";
            case "${orqixChildTask}" -> "startWorkflow";
            default -> null;
        };
        if ("${orqixAdapterTask}".equals(delegate)) {
            unsupported(id, "Step '" + id + "' sends through an adapter. Adapter steps are shown in BPMN but are edited in the"
                    + " Studio only, so this draft cannot be applied from BPMN.");
            return null;
        }
        if (kind == null) {
            unsupported(id, "Service task '" + id + "' is not an Orqix step: only the generated delegates "
                    + "(decision, call a system, document, external worker, notification, start a workflow) are supported; "
                    + "classes, scripts, expressions and connectors of other engines are not.");
            return null;
        }
        String extension = switch (kind) {
            case "decision" -> "decision";
            case "http" -> "http";
            case "generateDocument" -> "generateDocument";
            case "externalTask" -> "external";
            case "notification" -> "notification";
            default -> "child";
        };
        El x = ext == null ? null : ext.child(ORQIX_NS, extension);
        if (x == null) {
            unsupported(id, "Service task '" + id + "' has no orqix:" + extension + " settings.");
            return null;
        }
        switch (kind) {
            case "decision" -> putText(config, "decisionRef", x.attr("ref"));
            case "http" -> {
                putText(config, "connectionRef", x.attr("connection"));
                putText(config, "operation", x.attr("operation"));
                config.set("inputMapping", pairs(id, x, "input", "fieldRef"));
                config.set("resultMapping", pairs(id, x, "result", "pointer"));
                Integer timeout = integer(id, "timeoutSeconds", x.attr("timeoutSeconds"));
                if (timeout != null) {
                    config.put("timeoutSeconds", timeout);
                }
                putText(config, "compensates", x.attr("compensates"));
            }
            case "generateDocument" -> {
                putText(config, "format", x.attr("format"));
                putText(config, "fileName", x.attr("fileName"));
                String encoded = x.attr("templateBase64");
                if (encoded != null) {
                    try {
                        config.put("template", new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
                    } catch (IllegalArgumentException e) {
                        unsupported(id, "The templateBase64 of '" + id + "' is not valid base64.");
                    }
                }
                config.set("inputMapping", pairs(id, x, "input", "fieldRef"));
            }
            case "externalTask" -> {
                putText(config, "topic", x.attr("topic"));
                config.set("inputMapping", pairs(id, x, "input", "fieldRef"));
                config.set("result", fields(id, x));
                Integer lease = x.attr("leaseSeconds") == null ? null : integer(id, "leaseSeconds", x.attr("leaseSeconds"));
                if (lease != null && lease != 120) {
                    config.put("leaseSeconds", lease);
                }
            }
            case "notification" -> {
                putText(config, "channel", x.attr("channel"));
                putText(config, "recipient", x.attr("recipient"));
                putText(config, "message", x.attr("message"));
            }
            default -> {
                putText(config, "workflowRef", x.attr("workflow"));
                config.set("inputMapping", pairs(id, x, "input", "fieldRef"));
                config.set("resultMapping", pairs(id, x, "result", "output"));
            }
        }
        return kind;
    }

    private String catchEvent(El el, El ext, ObjectNode config) {
        String id = el.attr("id");
        El wait = ext == null ? null : ext.child(ORQIX_NS, "wait");
        El message = ext == null ? null : ext.child(ORQIX_NS, "message");
        if (wait != null) {
            if (wait.attr("duration") != null) {
                config.put("duration", wait.attr("duration"));
            } else {
                putText(config, "untilField", wait.attr("untilField"));
                String at = wait.attr("atTime");
                if (at != null && !"09:00".equals(at)) {
                    config.put("atTime", at);
                }
            }
            return "wait";
        }
        if (message != null) {
            readMessage(id, message, config);
            return "waitForMessage";
        }
        unsupported(id, "Intermediate catch event '" + id + "' is neither an Orqix wait (orqix:wait) nor a message wait "
                + "(orqix:message); signal, conditional, link and plain timer events are not supported.");
        return null;
    }

    private String messageWithLimit(El el, ObjectNode config) {
        String id = el.attr("id");
        El catching = byId.get(id + BpmnGenerator.MESSAGE_CATCH);
        El timeout = byId.get(id + BpmnGenerator.MESSAGE_TIMEOUT);
        El message = ext(catching, "message");
        El limit = ext(timeout, "messageTimeout");
        if (message == null || limit == null) {
            // Helpers may come later in the text; look ahead among the siblings.
            for (El sibling : el.parent().children()) {
                String sid = sibling.attr("id");
                if ((id + BpmnGenerator.MESSAGE_CATCH).equals(sid)) {
                    message = ext(sibling, "message");
                } else if ((id + BpmnGenerator.MESSAGE_TIMEOUT).equals(sid)) {
                    limit = ext(sibling, "messageTimeout");
                }
            }
        }
        if (message == null || limit == null) {
            unsupported(id, "Event-based gateway '" + id + "' is only supported as a message wait with a time limit: it "
                    + "needs '" + id + "__message' (orqix:message) and '" + id + "__timeout' (orqix:messageTimeout).");
            return null;
        }
        readMessage(id, message, config);
        putText(config, "timeoutAfter", limit.attr("after"));
        return "waitForMessage";
    }

    private void readMessage(String id, El message, ObjectNode config) {
        putText(config, "message", message.attr("name"));
        putText(config, "correlationField", message.attr("correlationField"));
        config.set("payload", fields(id, message));
    }

    private static El ext(El el, String name) {
        El ext = el == null ? null : el.child(BPMN_NS, "extensionElements");
        return ext == null ? null : ext.child(ORQIX_NS, name);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void checkHelper(El helper) {
        String id = helper.attr("id");
        int at = id.indexOf("__");
        String owner = id.substring(0, at);
        String suffix = id.substring(at);
        String type = types.get(owner);
        boolean known = type != null && switch (suffix) {
            case BpmnGenerator.APPROVAL_OUT, BpmnGenerator.APPROVAL_ROUTE -> type.equals("approval");
            case "__timeout" -> type.equals("approval") || type.equals("waitForMessage");
            case BpmnGenerator.FAILED_BOUNDARY -> type.equals("http") || type.equals("startWorkflow");
            case BpmnGenerator.MESSAGE_CATCH, "__toMessage", "__toTimeout" -> type.equals("waitForMessage");
            default -> false;
        };
        if (!known) {
            unsupported(id, "'" + id + "' uses the reserved '__' in its id but is not a generated part of a step "
                    + (type == null ? "('" + owner + "' is not a step)" : "of type " + type) + ".");
        }
    }

    // ------------------------------------------------------------------------------------------------ connections

    private ObjectNode readFlow(El flow) {
        String id = flow.attr("id");
        String source = flow.attr("sourceRef");
        String target = flow.attr("targetRef");
        if (source == null || target == null) {
            unsupported(id, "Sequence flow '" + id + "' needs a sourceRef and a targetRef.");
            return null;
        }
        if (target.contains("__") || !nodes.containsKey(target)) {
            unsupported(id, "Sequence flow '" + id + "' goes to '" + target + "', which is not a step.");
            return null;
        }
        El expressionEl = flow.child(BPMN_NS, "conditionExpression");
        String expression = expressionEl == null ? null : expressionEl.text();
        String from;
        String on;
        if (source.contains("__")) {
            int at = source.indexOf("__");
            from = source.substring(0, at);
            String suffix = source.substring(at);
            String type = types.get(from);
            if (type == null) {
                unsupported(id, "Sequence flow '" + id + "' starts at '" + source + "', which is not part of a step.");
                return null;
            }
            switch (suffix) {
                case BpmnGenerator.APPROVAL_ROUTE -> {
                    Matcher m = expression == null ? null : APPROVAL_ROUTE.matcher(expression);
                    if (m == null || !m.matches() || !m.group(2).equals(from)) {
                        unsupported(id, "A path out of the approval '" + from + "' must carry the generated "
                                + "approved/rejected condition for that step; other expressions are not supported.");
                        return null;
                    }
                    on = m.group(1);
                }
                case "__timeout" -> on = "timedOut";
                case BpmnGenerator.FAILED_BOUNDARY -> on = "failed";
                case BpmnGenerator.MESSAGE_CATCH -> on = "next";
                default -> {
                    unsupported(id, "Sequence flow '" + id + "' starts at '" + source + "', which no connection can "
                            + "start from.");
                    return null;
                }
            }
        } else {
            from = source;
            if (!nodes.containsKey(from)) {
                unsupported(id, "Sequence flow '" + id + "' starts at '" + source + "', which is not a step.");
                return null;
            }
            El gateway = byId.get(from);
            if (expression != null) {
                Matcher m = CONDITION_ROUTE.matcher(expression);
                if (!m.matches() || !m.group(1).equals(id)) {
                    unsupported(id, "The condition of '" + id + "' must be the generated ${orqixRoute.condition(execution, '"
                            + id + "')} with the comparison in orqix:condition; free expressions are not supported.");
                    return null;
                }
                on = "condition";
            } else if (gateway != null && id.equals(gateway.attr("default"))) {
                on = "default";
            } else {
                on = "next";
            }
        }
        ObjectNode t = F.objectNode();
        t.put("id", id);
        t.put("from", from);
        t.put("to", target);
        t.put("on", on);
        if (flow.attr("name") != null) {
            t.put("label", flow.attr("name"));
        }
        if (on.equals("condition")) {
            El ext = flow.child(BPMN_NS, "extensionElements");
            El c = ext == null ? null : ext.child(ORQIX_NS, "condition");
            if (c == null) {
                unsupported(id, "Conditional path '" + id + "' has no orqix:condition (the comparison it tests).");
                return null;
            }
            JsonNode condition = condition(id, c);
            if (condition == null) {
                return null;
            }
            t.set("condition", condition);
        }
        return t;
    }

    private JsonNode condition(String id, El c) {
        if (c.attr("group") != null) {
            ArrayNode terms = F.arrayNode();
            for (El term : c.all(ORQIX_NS, "term")) {
                ObjectNode atom = atom(id, term);
                if (atom == null) {
                    return null;
                }
                terms.add(atom);
            }
            ObjectNode group = F.objectNode();
            group.set(c.attr("group"), terms);
            return group;
        }
        return atom(id, c);
    }

    private ObjectNode atom(String id, El c) {
        ObjectNode atom = F.objectNode();
        putText(atom, "fieldRef", c.attr("fieldRef"));
        putText(atom, "operator", c.attr("operator"));
        String type = c.attr("valueType");
        String value = c.attr("value");
        if (value == null || type == null) {
            unsupported(id, "A condition of '" + id + "' needs valueType and value.");
            return null;
        }
        switch (type) {
            case "string" -> atom.put("value", value);
            case "number" -> {
                try {
                    atom.put("value", new BigDecimal(value));
                } catch (NumberFormatException e) {
                    unsupported(id, "The condition value '" + value + "' of '" + id + "' is not a number.");
                    return null;
                }
            }
            case "boolean" -> {
                Boolean b = bool(id, "value", value);
                if (b == null) {
                    return null;
                }
                atom.put("value", b);
            }
            default -> {
                unsupported(id, "The condition valueType '" + type + "' of '" + id + "' is not string, number or boolean.");
                return null;
            }
        }
        return atom;
    }

    // ------------------------------------------------------------------------------------------------ joins

    /**
     * {@code joinRef} is not written to BPMN: each split's join is found again as the first join every path from the
     * split reaches at the same nesting level. When that is not exactly one join, the split is reported.
     */
    private void inferJoins(ArrayNode transitions) {
        Map<String, List<String>> next = new LinkedHashMap<>();
        for (JsonNode t : transitions) {
            next.computeIfAbsent(t.get("from").stringValue(), k -> new ArrayList<>()).add(t.get("to").stringValue());
        }
        for (ObjectNode node : nodes.values()) {
            if (!"parallelSplit".equals(node.get("type").stringValue())) {
                continue;
            }
            String split = node.get("id").stringValue();
            Set<String> joins = new LinkedHashSet<>();
            Deque<Object[]> queue = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            for (String to : next.getOrDefault(split, List.of())) {
                queue.add(new Object[] {to, 0});
            }
            while (!queue.isEmpty()) {
                Object[] item = queue.removeFirst();
                String at = (String) item[0];
                int depth = (Integer) item[1];
                if (!seen.add(at + "@" + depth) || depth > 50) {
                    continue;
                }
                String type = types.get(at);
                if ("parallelJoin".equals(type)) {
                    if (depth == 0) {
                        joins.add(at);
                        continue;
                    }
                    depth--;
                } else if ("parallelSplit".equals(type)) {
                    depth++;
                }
                for (String to : next.getOrDefault(at, List.of())) {
                    queue.add(new Object[] {to, depth});
                }
            }
            ObjectNode config = (ObjectNode) node.get("config");
            ObjectNode rebuilt = F.objectNode();
            if (joins.size() == 1) {
                rebuilt.put("joinRef", joins.iterator().next());
            } else {
                unsupported(split, "The parallel split '" + split + "' must lead to exactly one matching join; "
                        + (joins.isEmpty() ? "none was found." : "found " + String.join(", ", joins) + "."));
            }
            config.properties().forEach(e -> rebuilt.set(e.getKey(), e.getValue()));
            node.set("config", rebuilt);
        }
    }

    // ------------------------------------------------------------------------------------------------ diagram

    private ObjectNode positions(El diagram, String processId) {
        El plane = diagram.child(BPMNDI_NS, "BPMNPlane");
        ObjectNode positions = F.objectNode();
        if (plane == null) {
            unsupported(diagram.attr("id"), "The diagram has no bpmndi:BPMNPlane.");
            return positions;
        }
        Map<String, El> shapes = new LinkedHashMap<>();
        for (El child : plane.children()) {
            if (child.is(BPMNDI_NS, "BPMNShape")) {
                String element = child.attr("bpmnElement");
                if (element != null && shapes.putIfAbsent(element, child) != null) {
                    unsupported(element, "The diagram has more than one shape for '" + element + "'.");
                }
            } else if (!child.is(BPMNDI_NS, "BPMNEdge")) {
                unsupported(child.attr("id"), "Diagram element " + child.label() + " is not supported (only shapes and "
                        + "edges of the process).");
            }
        }
        for (Map.Entry<String, ObjectNode> e : nodes.entrySet()) {
            El shape = shapes.get(e.getKey());
            El bounds = shape == null ? null : shape.child(DC_NS, "Bounds");
            if (bounds == null) {
                unsupported(e.getKey(), "Step '" + e.getKey() + "' has no shape with bounds in the diagram; either every "
                        + "step has a shape (its canvas position) or the diagram is left out.");
                continue;
            }
            try {
                int[] size = size(e.getValue());
                BigDecimal x = new BigDecimal(bounds.attr("x")).add(BigDecimal.valueOf(size[0] / 2))
                        .subtract(CARD_HALF_WIDTH);
                BigDecimal y = new BigDecimal(bounds.attr("y")).add(BigDecimal.valueOf(size[1] / 2))
                        .subtract(CARD_HALF_HEIGHT);
                ObjectNode p = positions.putObject(e.getKey());
                p.set("x", number(x));
                p.set("y", number(y));
            } catch (RuntimeException ex) {
                unsupported(e.getKey(), "The diagram bounds of '" + e.getKey() + "' need numeric x and y.");
            }
        }
        for (String element : shapes.keySet()) {
            if (!element.contains("__") && !nodes.containsKey(element)) {
                unsupported(element, "The diagram has a shape for '" + element + "', which is not a step.");
            }
        }
        if (processId != null && !processId.equals(plane.attr("bpmnElement"))) {
            unsupported(plane.attr("id"), "The diagram plane must show the process '" + processId + "'.");
        }
        return positions;
    }

    /** Shape size the generator uses for a step (BpmnGenerator.Diagram). */
    private static int[] size(ObjectNode node) {
        return switch (node.get("type").stringValue()) {
            case "start", "end", "wait" -> new int[] {36, 36};
            case "waitForMessage" -> node.get("config").has("timeoutAfter") ? new int[] {40, 40} : new int[] {36, 36};
            case "branch", "parallelSplit", "parallelJoin" -> new int[] {40, 40};
            default -> new int[] {100, 80};
        };
    }

    private static JsonNode number(BigDecimal value) {
        BigDecimal v = value.stripTrailingZeros();
        if (v.scale() <= 0 && v.abs().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) < 0) {
            return F.numberNode(v.intValueExact());
        }
        return F.numberNode(v);
    }

    // ------------------------------------------------------------------------------------------------ values

    private ObjectNode pairs(String id, El parent, String child, String valueAttr) {
        ObjectNode map = F.objectNode();
        for (El e : parent.all(ORQIX_NS, child)) {
            String name = e.attr("name");
            String value = e.attr(valueAttr);
            if (name == null || value == null) {
                unsupported(id, "Every orqix:" + child + " of '" + id + "' needs name and " + valueAttr + ".");
                continue;
            }
            if (map.has(name)) {
                unsupported(id, "orqix:" + child + " '" + name + "' appears twice on '" + id + "'.");
                continue;
            }
            map.put(name, value);
        }
        return map;
    }

    private ArrayNode fields(String id, El parent) {
        ArrayNode list = F.arrayNode();
        for (El e : parent.all(ORQIX_NS, "field")) {
            ObjectNode f = list.addObject();
            putText(f, "name", e.attr("name"));
            putText(f, "type", e.attr("type"));
            String required = e.attr("required");
            if (required != null) {
                Boolean value = bool(id, "required", required);
                if (Boolean.TRUE.equals(value)) {
                    f.put("required", true);
                }
            }
        }
        return list;
    }

    private void jsonAttribute(String id, El el, String name, ObjectNode config) {
        String text = el.attr(name);
        if (text == null) {
            return;
        }
        try {
            JsonNode value = StrictJson.parse(text);
            if (!value.isObject()) {
                throw new IllegalArgumentException();
            }
            config.set(name, value);
        } catch (RuntimeException e) {
            unsupported(id, "The " + name + " of '" + id + "' must be a JSON object.");
        }
    }

    private Integer integer(String id, String name, String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            unsupported(id, "The " + name + " of '" + id + "' must be a whole number, not '" + text + "'.");
            return null;
        }
    }

    private Boolean bool(String id, String name, String text) {
        if ("true".equals(text) || "false".equals(text)) {
            return Boolean.valueOf(text);
        }
        unsupported(id, "The " + name + " of '" + id + "' must be true or false, not '" + text + "'.");
        return null;
    }

    private static void putText(ObjectNode target, String name, String value) {
        if (value != null) {
            target.put(name, value);
        }
    }

    private void unsupported(String elementId, String message) {
        findings.add(new Finding(UNSUPPORTED, elementId, message));
    }

    private static String describeUnsupported(El el) {
        String what = switch (el.local()) {
            case "laneSet", "lane" -> "Lanes (bpmn:laneSet) are not stored in the workflow document: the studio derives "
                    + "lanes from each approval's group, so a lane drawn here would be lost.";
            case "subProcess", "adHocSubProcess", "transaction" ->
                "Sub-processes are not supported; use a 'Start another workflow' step.";
            case "callActivity" -> "Call activities are generated as service tasks with ${orqixChildTask}; a "
                    + "bpmn:callActivity is not supported.";
            case "scriptTask", "businessRuleTask", "sendTask", "receiveTask", "manualTask", "task" ->
                el.label() + " is not an Orqix step type.";
            case "complexGateway" -> "Complex gateways are not supported.";
            case "intermediateThrowEvent" -> "Throw events (signals, messages, escalations, links) are not supported.";
            case "textAnnotation", "association", "group" ->
                "Annotations and groups (" + el.label() + ") are not kept by the workflow document.";
            case "dataObject", "dataObjectReference", "dataStoreReference", "property", "ioSpecification" ->
                "Data objects and properties (" + el.label() + ") are not supported.";
            default -> el.label() + " is not supported in a workflow process.";
        };
        String id = el.attr("id");
        return (id == null ? "" : "'" + id + "': ") + what;
    }
}
