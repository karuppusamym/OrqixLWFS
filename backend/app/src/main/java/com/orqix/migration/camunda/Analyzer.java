package com.orqix.migration.camunda;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.orqix.migration.camunda.Analysis.Need;

/**
 * Adapter {@code camunda-bpmn-0.5}: reads a Camunda 7 / Camunda 8 / plain BPMN 2.0 file and gives every source element
 * and every nested construct a disposition (S11 section 2, doc 05). Nothing is silently omitted: unknown elements are
 * blockers, dropped settings are info items that need acknowledgement. Pure Java; no Spring, no database.
 */
public final class Analyzer {

    public static final String ADAPTER_VERSION = "camunda-bpmn-0.5";

    private static final String BPMN = Namespaces.BPMN;
    private static final String C7 = Namespaces.CAMUNDA7;
    private static final String ZB = Namespaces.ZEEBE;

    private static final Set<String> GATEWAY_BLOCKED = Set.of("inclusiveGateway", "eventBasedGateway",
            "complexGateway");
    private static final Set<String> TASK_BLOCKED = Set.of("scriptTask", "sendTask", "receiveTask", "manualTask", "task");
    private static final Set<String> EVENT_BLOCKED = Set.of("intermediateCatchEvent", "intermediateThrowEvent", "boundaryEvent");
    private static final Set<String> CONTAINER_BLOCKED = Set.of("subProcess", "adHocSubProcess", "transaction");
    private static final Set<String> ARTIFACTS = Set.of("textAnnotation", "association", "group", "dataObject",
            "dataObjectReference", "dataStoreReference");
    private static final Set<String> DECLARATIONS = Set.of("message", "signal", "error", "escalation", "itemDefinition",
            "resource", "category", "dataStore", "import", "extension", "relationship", "interface", "partnerEntity",
            "partnerRole", "endPoint", "eventDefinition", "globalTask", "correlationProperty");
    private static final Set<String> FORM_ATTRIBUTES = Set.of("formKey", "formRef", "formRefBinding", "formRefVersion");
    private static final Set<String> CONSUMED_ATTRIBUTES = Set.of("candidateGroups", "candidateUsers", "assignee", "class",
            "delegateExpression", "expression", "type", "topic", "resultVariable", "decisionRef", "mapDecisionResult", "collection", "elementVariable", "modelerTemplate",
            "modelerTemplateVersion", "diagramRelationId", "modelerTemplateIcon");
    private static final Pattern REJECTING_NAME = Pattern.compile("(?i).*(reject|denied|decline|refus).*");

    private final XElement root;
    private final String sourceHash;
    private final List<Analysis.Item> items = new ArrayList<>();
    private int sequence;
    private Analysis analysis;
    /** Camunda 8 embedded forms ({@code zeebe:userTaskForm}): id to the JSON text, the element it came from, and parse results. */
    private final Map<String, String> embeddedSources = new LinkedHashMap<>();
    private final Map<String, XElement> embeddedElements = new LinkedHashMap<>();
    private final Map<String, FormImport.Model> embeddedModels = new LinkedHashMap<>();
    private final Set<String> embeddedUsed = new HashSet<>();
    /** User task id to the due time in hours read from {@code zeebe:taskSchedule}. */
    private final Map<String, Integer> dueHours = new LinkedHashMap<>();

    private Analyzer(XElement root, String sourceHash) {
        this.root = root;
        this.sourceHash = sourceHash;
    }

    /** Parses (safely) and analyses; throws {@link SourceException} when the file cannot be read. */
    public static Analysis analyze(String content, Limits limits) {
        XElement root = SafeXml.parse(content, limits);
        return new Analyzer(root, sha256Hex(content.getBytes(StandardCharsets.UTF_8))).run();
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------ driver

    private Analysis run() {
        if (!root.is(BPMN, "definitions")) {
            throw SourceException.invalid("This is not a BPMN 2.0 file. The root element must be bpmn:definitions in the "
                    + "namespace " + BPMN + ".");
        }
        List<XElement> processes = root.children(BPMN, "process");
        if (processes.isEmpty()) {
            throw SourceException.invalid("The file contains no BPMN process.");
        }
        List<XElement> executable = processes.stream().filter(p -> "true".equalsIgnoreCase(p.attr("isExecutable"))).toList();
        XElement process = executable.isEmpty() ? processes.get(0) : executable.get(0);
        if (process.attr("id") == null || process.attr("id").isBlank()) {
            throw SourceException.invalid("The process has no id.");
        }
        String[] detected = detect();
        analysis = new Analysis(detected[0], detected[1], sourceHash, process.attr("id"), blankToNull(process.attr("name")));
        analysis.assumptions.add("Source detected as " + describe(detected[0]) + ".");
        limits(detected[0], detected[1]);
        if (detected[2] != null) {
            analysis.assumptions.add(detected[2]);
        }
        if (executable.isEmpty()) {
            analysis.assumptions.add("No process is marked isExecutable=\"true\"; the first process '" + process.attr("id")
                    + "' was imported.");
        }
        analysis.assumptions.add("Diagram layout (bpmndi) is not imported; the canvas layout is generated.");
        add(process, "targetPolicy", Report.REVIEW, "TARGET_POLICY_REVIEW",
                "The target workflow denies self-approval and requires a reason for every rejected approval. Review these target policies and inferred request fields.",
                "Acknowledge the target policies and validate behavior before publishing.", Need.ACKNOWLEDGE, null);

        definitionsLevel(process);
        for (XElement other : processes) {
            if (other != process) {
                add(other, "process", Report.INFO, "PROCESS_NOT_CONVERTED",
                        "Only one process is converted per import; process '" + labelOf(other) + "' with "
                                + countDescendants(other) + " elements was not converted.",
                        "Import the other process in a separate file, or acknowledge that it is left out.", Need.ACKNOWLEDGE,
                        null);
            }
        }
        processLevel(process);
        graphAnalysis();
        unusedEmbeddedForms();
        serviceGroupItems();
        items.sort(Comparator.<Analysis.Item>comparingInt(i -> i.ordinal).thenComparingInt(i -> i.sequence));
        analysis.items.addAll(items);
        return analysis;
    }

    private String[] detect() {
        int c7 = 0;
        int c8 = 0;
        Deque<XElement> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            XElement e = stack.pop();
            if (C7.equals(e.ns())) {
                c7++;
            } else if (ZB.equals(e.ns())) {
                c8++;
            }
            for (String key : e.attributes().keySet()) {
                if (key.startsWith("{" + C7 + "}")) {
                    c7++;
                } else if (key.startsWith("{" + ZB + "}")) {
                    c8++;
                }
            }
            e.children().forEach(stack::push);
        }
        String platform = root.attr(Namespaces.MODELER, "executionPlatform");
        String version = blankToNull(root.attr(Namespaces.MODELER, "executionPlatformVersion"));
        String system;
        String note = null;
        if (c7 == 0 && c8 == 0) {
            if (platform != null && platform.toLowerCase(Locale.ROOT).contains("cloud")
                    || platform == null && version != null && version.startsWith("8.")) {
                system = "camunda8";
            } else if (platform != null && platform.toLowerCase(Locale.ROOT).contains("platform")
                    || platform == null && version != null && version.startsWith("7.")) {
                system = "camunda7";
            } else {
                system = "bpmn";
            }
        } else if (c8 > c7) {
            system = "camunda8";
        } else {
            system = "camunda7";
        }
        if (c7 > 0 && c8 > 0) {
            note = "The file contains both Camunda 7 (camunda:) and Camunda 8 (zeebe:) extensions; it is treated as "
                    + describe(system) + " and the other extensions are reported individually.";
        }
        if (version != null && system.equals("camunda8") && version.startsWith("7.")
                || version != null && system.equals("camunda7") && version.startsWith("8.")) {
            String mismatch = "The modeler records platform version " + version + " but the extensions are " + describe(system)
                    + "; the extensions decide how the file is read.";
            note = note == null ? mismatch : note + " " + mismatch;
        }
        return new String[] {system, version, note};
    }

    /** What the adapter does and does not cover for the detected platform; shown as the first assumptions of the report. */
    private void limits(String system, String version) {
        if ("camunda8".equals(system)) {
            analysis.assumptions.add("Source version: Camunda 8" + (version == null ? " (the modeler did not record a version)" : " "
                    + version + " (modeler executionPlatformVersion)") + ". Adapter " + ADAPTER_VERSION + " reads the Camunda 8 "
                    + "constructs listed in the report only: job workers and the REST (http-json) connector become connection calls "
                    + "after you choose an operation; FEEL conditions of the supported comparison subset convert; user tasks with "
                    + "candidate groups become approvals (due time, boundary timer as time limit); duration timers, date timers on a "
                    + "date field, parallel pairs, call activities, multi-instance call activities and called decisions convert after "
                    + "review. Message and signal events, other connectors, sub-processes, FEEL functions and scripts stay blockers. "
                    + "This is not a claim of general Camunda 8 compatibility.");
        } else if ("camunda7".equals(system)) {
            analysis.assumptions.add("Source version: Camunda 7" + (version == null ? " (the modeler did not record a version)" : " "
                    + version + " (modeler executionPlatformVersion)") + ". Adapter " + ADAPTER_VERSION + " reads the constructs listed "
                    + "in the report only; this is not a claim of general Camunda 7 compatibility.");
        }
    }

    private static String describe(String system) {
        return switch (system) {
            case "camunda7" -> "Camunda 7 (camunda: extensions)";
            case "camunda8" -> "Camunda 8 (zeebe: extensions)";
            default -> "plain BPMN 2.0";
        };
    }

    // ------------------------------------------------------------------------------------------------ definitions

    private void definitionsLevel(XElement chosen) {
        for (XElement child : root.children()) {
            if (child == chosen || child.is(BPMN, "process")) {
                continue;
            }
            if (Namespaces.BPMNDI.equals(child.ns())) {
                continue; // presentation only; recorded as an assumption
            }
            if (child.is(BPMN, "collaboration")) {
                add(child, "collaboration", Report.INFO, "ARTIFACT_NOT_IMPORTED",
                        "The collaboration (pools and message flows) is not imported; only the process is converted.",
                        "Acknowledge, or model the hand-offs between pools as separate workflows.", Need.ACKNOWLEDGE, null);
                for (XElement part : child.children()) {
                    if (part.is(BPMN, "participant")) {
                        add(part, "participant", Report.INFO, "ARTIFACT_NOT_IMPORTED",
                                "The pool '" + labelOf(part) + "' is not imported.", "Acknowledge.", Need.ACKNOWLEDGE, null);
                    } else if (part.is(BPMN, "messageFlow")) {
                        add(part, "messageFlow", Report.BLOCKER, "MESSAGE_FLOW_NOT_SUPPORTED",
                                "Message flows between pools need messaging, which workflows do not support in v0.1.",
                                "Rebuild the hand-off as a call to a registered connection operation.", Need.NONE, null);
                    } else if (!part.is(BPMN, "documentation") && !part.is(BPMN, "extensionElements")) {
                        unknown(part);
                    }
                }
            } else if (child.is(BPMN, "extensionElements")) {
                scanExtensions(root, child);
            } else if (child.is(BPMN, "documentation")) {
                add(child, "documentation", Report.INFO, "DOCUMENTATION_NOT_IMPORTED",
                        "Documentation text is not imported.", "Acknowledge; copy it to the workflow description if needed.",
                        Need.ACKNOWLEDGE, null);
            } else if (BPMN.equals(child.ns()) && DECLARATIONS.contains(child.name())) {
                add(child, child.name(), Report.INFO, "DECLARATION_NOT_IMPORTED",
                        "The " + child.name() + " declaration '" + labelOf(child) + "' is not imported on its own; the events that "
                                + "use it are reported separately.",
                        "Acknowledge.", Need.ACKNOWLEDGE, null);
            } else if (BPMN.equals(child.ns()) || C7.equals(child.ns()) || ZB.equals(child.ns())) {
                unknown(child);
            } else {
                add(child, child.name(), Report.INFO, "EXTENSION_NOT_IMPORTED",
                        "The element " + child.label() + " belongs to a namespace Orqix does not know and is not imported.",
                        "Acknowledge.", Need.ACKNOWLEDGE, null);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ process

    private void processLevel(XElement process) {
        scanAttributes(process, "process");
        List<XElement> flowElements = new ArrayList<>();
        for (XElement child : process.children()) {
            if (child.is(BPMN, "extensionElements")) {
                scanExtensions(process, child);
            } else if (child.is(BPMN, "documentation")) {
                add(child, "documentation", Report.INFO, "DOCUMENTATION_NOT_IMPORTED",
                        "Documentation text is not imported.", "Acknowledge; copy it to the workflow description if needed.",
                        Need.ACKNOWLEDGE, null);
            } else if (child.is(BPMN, "laneSet")) {
                add(child, "laneSet", Report.INFO, "ARTIFACT_NOT_IMPORTED",
                        "Lanes are not imported. Who works on each step is set by the workspace group on each approval.",
                        "Acknowledge, and choose workspace groups in the mapping step.", Need.ACKNOWLEDGE, null);
            } else if (BPMN.equals(child.ns()) && ARTIFACTS.contains(child.name())) {
                add(child, child.name(), Report.INFO, "ARTIFACT_NOT_IMPORTED",
                        "The " + child.name() + " '" + labelOf(child) + "' is documentation or data and is not imported.",
                        "Acknowledge.", Need.ACKNOWLEDGE, null);
            } else if (BPMN.equals(child.ns()) || C7.equals(child.ns()) || ZB.equals(child.ns())) {
                flowElements.add(child);
            } else {
                add(child, child.name(), Report.INFO, "EXTENSION_NOT_IMPORTED",
                        "The element " + child.label() + " belongs to a namespace Orqix does not know and is not imported.",
                        "Acknowledge.", Need.ACKNOWLEDGE, null);
            }
        }
        // Pass 1: nodes and flows, plus every element that is blocked on its own.
        Map<String, String> unnamed = new LinkedHashMap<>();
        for (XElement e : flowElements) {
            String id = idOf(e);
            switch (e.name()) {
                case "sequenceFlow" -> {
                    // flows are linked once all nodes are known
                }
                case "startEvent", "endEvent", "userTask", "serviceTask", "exclusiveGateway" -> {
                    analysis.nodes.put(id, new Analysis.Node(id, blankToNull(e.attr("name")), e.name(), e));
                }
                default -> {
                    Analysis.Node blocked = new Analysis.Node(id, blankToNull(e.attr("name")), e.name(), e);
                    analysis.nodes.put(id, blocked);
                }
            }
            unnamed.put(id, e.name());
        }
        for (XElement e : flowElements) {
            if (!"sequenceFlow".equals(e.name())) {
                continue;
            }
            String id = idOf(e);
            Analysis.Node source = analysis.nodes.get(e.attr("sourceRef"));
            Analysis.Node target = analysis.nodes.get(e.attr("targetRef"));
            if (source == null || target == null) {
                add(e, "sequenceFlow", Report.BLOCKER, "INVALID_REFERENCE",
                        "The flow connects '" + e.attr("sourceRef") + "' to '" + e.attr("targetRef") + "', and at least one "
                                + "of them is not a step of this process.",
                        "Fix the reference in the source file.", Need.NONE, null);
                scanFlowChildren(e);
                continue;
            }
            Analysis.Flow flow = new Analysis.Flow(id, blankToNull(e.attr("name")), e, source, target);
            analysis.flows.put(id, flow);
            source.out.add(flow);
            target.in.add(flow);
        }
        // Pass 2: classification of every node.
        for (Analysis.Node node : new ArrayList<>(analysis.nodes.values())) {
            classify(node);
        }
        assignCanonicalIds();
    }

    private void classify(Analysis.Node node) {
        XElement e = node.element;
        String type = node.type;
        switch (type) {
            case "startEvent" -> {
                scanNodeChildren(node);
                if (eventDefinition(e) != null) {
                    blockedEvent(node);
                } else {
                    analysis.startIds.add(node.id);
                    add(e, type, Report.SUPPORTED, "START_EVENT_MAPPED",
                            "A none start event becomes the request form that starts the workflow.", null, Need.NONE, null);
                }
            }
            case "endEvent" -> {
                scanNodeChildren(node);
                if (eventDefinition(e) != null) {
                    blockedEvent(node);
                } else {
                    analysis.endIds.add(node.id);
                    String outcome = blankToNull(e.attr("name")) == null ? "Completed" : e.attr("name");
                    String kind = REJECTING_NAME.matcher(outcome).matches() ? "rejected" : "success";
                    add(e, type, Report.REVIEW, "END_EVENT_MAPPED",
                            "A none end event becomes a finish step. Its business outcome '" + outcome + "' is taken from the "
                                    + "name and classified as '" + kind + "' because " + (kind.equals("rejected")
                                    ? "the name means rejection" : "the name does not mean rejection") + ".",
                            "Check the outcome classification: it decides whether the request counts as rejected or successful.",
                            Need.ACKNOWLEDGE, null);
                }
            }
            case "userTask" -> {
                scanNodeChildren(node);
                // item is created after the graph analysis (needs the flows)
                Assignment assignment = assignment(e);
                Analysis.UserTask created = new Analysis.UserTask(node, assignment.kind(), assignment.values());
                created.dueAfterHours = dueHours.get(node.id);
                analysis.userTasks.put(node.id, created);
            }
            case "serviceTask" -> {
                scanNodeChildren(node);
                service(node);
            }
            case "businessRuleTask" -> {
                scanNodeChildren(node);
                businessRule(node);
            }
            case "parallelGateway" -> scanNodeChildren(node);
            case "intermediateCatchEvent" -> {
                scanNodeChildren(node);
                timerCatch(node);
            }
            case "boundaryEvent" -> scanNodeChildren(node); // disposition after the user tasks are known (boundaryTimers)
            case "callActivity" -> {
                scanNodeChildren(node);
                analysis.children.add(node.id);
                boolean multi = analysis.multiInstance.containsKey(node.id);
                XElement callExt = e.child(BPMN, "extensionElements");
                XElement zeebeCalled = callExt == null ? null : callExt.child(ZB, "calledElement");
                String calledId = zeebeCalled != null ? zeebeCalled.attr("processId") : e.attr("calledElement");
                if (zeebeCalled != null) {
                    callHint(node, zeebeCalled, callExt, calledId);
                }
                Analysis.Item call = add(e, type, Report.ACTION, "CHILD_WORKFLOW_MAPPING_REQUIRED", "Called process '" + calledId + "' requires a reviewed workspace workflow and explicit input/result mappings."
                        + (multi ? " It runs once per item of the collection '" + analysis.multiInstance.get(node.id).collectionVariable()
                        + "'; the mapping must also give the for-each settings (itemInput, maxItems, itemType) because the source collection has no bound." : ""),
                        "Import and publish the child first; map its key and fields. Its release is pinned when the parent is published.", Need.CHILD, node.id);
                call.reviewAfterResolution = reviewOf(call.issue, "CHILD_WORKFLOW_MAPPED",
                        "The call becomes a pinned child workflow request" + (multi ? " started once per collection item (bounded for-each)" : "")
                                + ". Outcomes, rejections and cancellation of the child are handled by the Orqix start-workflow step, "
                                + "not by the source engine's call semantics; variable propagation follows only the mapping you gave.",
                        "Check the input and result mapping and the child's outcomes against the source behaviour.");
            }
            case "exclusiveGateway" -> scanNodeChildren(node); // disposition after the graph analysis
            default -> {
                if (TASK_BLOCKED.contains(type) && connectorJobType(e) != null) {
                    scanNodeChildren(node);
                    unsupportedConnector(node, connectorJobType(e));
                } else if (TASK_BLOCKED.contains(type)) {
                    scanNodeChildren(node);
                    add(e, type, Report.BLOCKER, "TASK_TYPE_NOT_SUPPORTED",
                            "A " + type + " has no equivalent in workflow v0.1, which supports approvals, decisions, branches, "
                                    + "connection calls and notifications.",
                            "Replace it with an approval step or a call to a registered connection operation.", Need.NONE,
                            null);
                } else if (GATEWAY_BLOCKED.contains(type)) {
                    scanNodeChildren(node);
                    add(e, type, Report.BLOCKER, "GATEWAY_NOT_SUPPORTED_IN_V0",
                            "A " + type + " is not supported in v0.1 (parallel paths arrive with a later release).",
                            "Redesign this part as a sequence of steps or an exclusive choice.", Need.NONE, null);
                } else if (EVENT_BLOCKED.contains(type)) {
                    scanNodeChildren(node);
                    blockedEvent(node);
                } else if (CONTAINER_BLOCKED.contains(type)) {
                    scanNodeChildren(node);
                    add(e, type, Report.BLOCKER, "SUBPROCESS_NOT_SUPPORTED",
                            "Sub-processes are not supported in v0.1.", "Flatten the sub-process into the main process.",
                            Need.NONE, null);
                    descendantsInsideBlocked(e, e);
                } else {
                    unknown(e);
                }
            }
        }
    }

    private void blockedEvent(Analysis.Node node) {
        XElement def = eventDefinition(node.element);
        String kind = def == null ? "" : def.name().replace("EventDefinition", "");
        String what = def == null ? "an intermediate or boundary event" : kind + " event";
        switch (kind) {
            case "message" -> add(node.element, node.type, Report.BLOCKER, "MESSAGE_EVENT_NOT_SUPPORTED",
                    "This " + node.type + " waits for or sends a message. Workflows have no message events yet, so a message "
                            + "cannot start, wake up or be sent from a converted workflow.",
                    "Model the wait as a human step or a wait with a duration, call a registered connection operation to send "
                            + "the message, or start the follow-up work as its own workflow.", Need.NONE, null);
            case "signal" -> add(node.element, node.type, Report.BLOCKER, "SIGNAL_EVENT_NOT_SUPPORTED",
                    "This " + node.type + " waits for or sends a signal. Workflows have no signal events, so it cannot be converted.",
                    "Start the other work as its own workflow, or model the wait as a human step.", Need.NONE, null);
            default -> add(node.element, node.type, Report.BLOCKER, "EVENT_NOT_SUPPORTED_IN_V0",
                    "This " + node.type + " is " + what + ". Timers other than a constant duration or a date held in a request "
                            + "field, message, signal, error and non-interrupting events are not supported in v0.1.",
                    "Model the wait or exception as a human step or a call to a connection operation.", Need.NONE, null);
        }
    }

    private void blockedEvent(Analysis.Node node, String code, String why, String suggestion) {
        add(node.element, node.type, Report.BLOCKER, code, "This " + node.type + " cannot be converted. " + why, suggestion,
                Need.NONE, null);
    }

    /** A timer catch event: a constant duration, or a date held in a variable (a new required date field). */
    private void timerCatch(Analysis.Node node) {
        XElement e = node.element;
        XElement def = eventDefinition(e);
        long definitions = e.children().stream().filter(c -> c.name().endsWith("EventDefinition")).count();
        if (def != null && def.is(BPMN, "messageEventDefinition") && definitions == 1) {
            messageCatch(node, def);
            return;
        }
        if (def == null || !def.is(BPMN, "timerEventDefinition") || definitions != 1 || def.children().size() != 1
                || node.in.size() != 1 || node.out.size() != 1) {
            blockedEvent(node);
            return;
        }
        XElement duration = def.child(BPMN, "timeDuration");
        XElement date = def.child(BPMN, "timeDate");
        if (def.child(BPMN, "timeCycle") != null) {
            blockedEvent(node, "TIMER_CYCLE_NOT_SUPPORTED", "It repeats on a schedule (" + def.child(BPMN, "timeCycle").text()
                    + "). A workflow wait ends once.", "Start the workflow on a schedule outside Orqix, or model one wait.");
        } else if (duration != null) {
            String iso = Zeebe.duration(duration.text());
            if (iso != null && Zeebe.inBounds(iso)) {
                analysis.waits.put(node.id, iso);
                add(e, node.type, Report.REVIEW, "DURATION_TIMER_MAPPED",
                        "The constant duration " + iso + " becomes a durable wait of the same length. The wait starts when "
                                + "the previous step finishes; business calendars, time zones and timer reset behaviour of the "
                                + "source engine are not carried over.",
                        "Check that a fixed elapsed duration is what the source process meant.", Need.ACKNOWLEDGE, null);
            } else {
                blockedEvent(node, "TIMER_DURATION_NOT_SUPPORTED", "Its duration '" + duration.text() + "' is not a constant between "
                        + "one minute and 90 days written as days, hours and minutes (for example PT2H or P1D).",
                        "Use a constant duration such as PT2H, or model the wait as a human step.");
            }
        } else if (date != null) {
            String text = date.text().strip();
            String variable = text.startsWith("=") ? Zeebe.variable(text) : null;
            if (variable != null && fieldKey(variable) != null) {
                analysis.waitDates.put(node.id, variable);
                analysis.assumptions.add("Variable '" + variable + "' (the date timer '" + labelOf(e) + "' waits for) becomes the required "
                        + "request date field '" + fieldKey(variable) + "' (YYYY-MM-DD).");
                add(e, node.type, Report.REVIEW, "DATE_TIMER_MAPPED",
                        "The wait ends on the calendar date the requester enters in the new request date field '" + fieldKey(variable)
                                + "' at 09:00 UTC. The time of day, time zone and a date that is already past behave differently from "
                                + "the source engine.",
                        "Check the time of day (09:00 UTC) and that requesters know to enter the date.", Need.ACKNOWLEDGE, null);
            } else {
                blockedEvent(node, "TIMER_DATE_NOT_SUPPORTED", "It waits until '" + text + "'. Only a date held in a variable "
                        + "(written =variableName) can be waited for, because the date comes from the request form.",
                        "Keep the date in a variable that the requester provides, or use a duration.");
            }
        } else {
            blockedEvent(node);
        }
    }

    private static final Pattern MESSAGE_NAME = Pattern.compile("^[a-z][a-zA-Z0-9]*(-[a-z0-9][a-zA-Z0-9]*)*$");

    /**
     * A message catch event becomes a "wait for a message" step (S23) when its message has a canonical name and a correlation
     * key that is a plain variable (that variable becomes the request field the message is matched on). Message payload
     * variables are not imported.
     */
    private void messageCatch(Analysis.Node node, XElement def) {
        XElement e = node.element;
        XElement message = null;
        int declarations = 0;
        for (XElement candidate : root.children(BPMN, "message")) {
            if (candidate.attr("id") != null && candidate.attr("id").equals(def.attr("messageRef"))) {
                message = candidate;
                declarations++;
            }
        }
        String name = message == null ? null : blankToNull(message.attr("name"));
        XElement ext = message == null ? null : message.child(BPMN, "extensionElements");
        XElement subscription = ext == null ? null : ext.child(ZB, "subscription");
        String key = subscription == null ? null : blankToNull(subscription.attr("correlationKey"));
        String variable = key == null ? null : Zeebe.variable(key);
        String reason = null;
        if (declarations > 1) {
            reason = "Its message reference has duplicate declarations and cannot be resolved safely.";
        } else if (ext != null && ext.children(ZB, "subscription").size() > 1) {
            reason = "The message has multiple correlation subscriptions and cannot be resolved safely.";
        } else if (message == null || name == null) {
            reason = "It has no message declaration with a name.";
        } else if (name.length() < 2 || name.length() > 64 || !MESSAGE_NAME.matcher(name).matches()) {
            reason = "The message name '" + name + "' must be lowercase letters and digits in kebab-case or camelCase (for example payment-received).";
        } else if (key == null) {
            reason = "The message has no correlation key, so it cannot be matched to this request.";
        } else if (variable == null || fieldKey(variable) == null) {
            reason = "Its correlation key '" + Zeebe.mask(key) + "' is an expression. Only a plain variable (written =orderId) can be matched to a request field.";
        } else if (node.in.size() != 1 || node.out.size() != 1) {
            reason = "It needs exactly one incoming and one outgoing flow.";
        }
        if (reason != null) {
            blockedEvent(node, "MESSAGE_EVENT_NOT_SUPPORTED", reason, "Use a plain message name and a correlation key that is a request variable, or model the wait as a human step.");
            return;
        }
        analysis.messageCatches.put(node.id, new Analysis.MessageCatch(name, variable));
        analysis.assumptions.add("Variable '" + variable + "' (the correlation key of message '" + name + "') becomes the request field '" + fieldKey(variable)
                + "'; the message must carry the same value.");
        add(e, node.type, Report.REVIEW, "MESSAGE_CATCH_MAPPED",
                "The step waits until the message '" + name + "' arrives with the same value as the request field '" + fieldKey(variable)
                        + "'. Variables the message carries in Camunda 8 are not imported (the step has no payload); a message that arrives early is kept for a "
                        + "limited time, which the source engine's buffering may not match.",
                "Check the message name, the correlation field and who sends the message.", Need.ACKNOWLEDGE, null);
    }

    /**
     * An interrupting timer boundary event on a user task that can become an approval is that approval's time limit
     * ({@code timeoutAfter} and the {@code timedOut} route). Every other boundary event stays a blocker with its reason.
     */
    private void boundaryTimers() {
        for (Analysis.Node node : analysis.nodes.values()) {
            if (!"boundaryEvent".equals(node.type)) {
                continue;
            }
            XElement e = node.element;
            XElement def = eventDefinition(e);
            if (def == null || !def.is(BPMN, "timerEventDefinition")) {
                blockedEvent(node);
                continue;
            }
            Analysis.UserTask task = analysis.userTasks.get(e.attr("attachedToRef"));
            XElement duration = def.child(BPMN, "timeDuration");
            String iso = duration == null ? null : Zeebe.duration(duration.text());
            String reason;
            if (task == null) {
                reason = "Only a timer on a user task can become the time limit of an approval.";
            } else if ("false".equalsIgnoreCase(e.attr("cancelActivity"))) {
                reason = "It is non-interrupting (the task stays open when the timer fires), which an approval time limit cannot do.";
            } else if ("expression".equals(task.assignmentKind) || task.outcomesImpossible) {
                reason = "Its user task cannot become an approval, so there is no approval to limit.";
            } else if (!node.in.isEmpty() || node.out.size() != 1) {
                reason = "It needs exactly one outgoing flow, which becomes the timed-out route.";
            } else if (def.child(BPMN, "timeCycle") != null || def.child(BPMN, "timeDate") != null || duration == null) {
                reason = "Only a constant duration can be a time limit, not a date or a repeating timer.";
            } else if (iso == null || !Zeebe.inBounds(iso)) {
                reason = "Its duration '" + duration.text() + "' is not a constant between one minute and 90 days.";
            } else if (task.timeoutAfter != null) {
                reason = "The task already has another time limit.";
            } else {
                reason = null;
            }
            if (reason != null) {
                blockedEvent(node, "EVENT_NOT_SUPPORTED_IN_V0", reason,
                        "Model the escalation as a separate approval, or leave the task without a time limit.");
                continue;
            }
            task.timeoutAfter = iso;
            task.timeoutFlow = node.out.get(0);
            analysis.timeoutBoundaries.put(node.id, task.id());
            add(e, node.type, Report.REVIEW, "BOUNDARY_TIMER_MAPPED",
                    "The interrupting timer " + iso + " becomes the time limit of the approval '" + labelOf(task.node.element)
                            + "': when nobody decides in time the approval ends and the request continues on the flow '"
                            + labelOf(node.out.get(0).element) + "' (the timed-out route). The time runs from when the approval opens; "
                            + "business calendars and time zones of the source engine are not carried over.",
                    "Check the time limit and where the timed-out route leads (for example an escalation approval or a rejection).",
                    Need.ACKNOWLEDGE, null);
        }
    }

    private void descendantsInsideBlocked(XElement scope, XElement owner) {
        for (XElement child : scope.children()) {
            if (child.is(BPMN, "extensionElements") || child.is(BPMN, "documentation") || child.is(BPMN, "incoming")
                    || child.is(BPMN, "outgoing")) {
                continue;
            }
            if (child.attr("id") != null && BPMN.equals(child.ns())) {
                add(child, child.name(), Report.BLOCKER, "INSIDE_UNSUPPORTED_SCOPE",
                        "This " + child.name() + " is inside the " + owner.name() + " '" + labelOf(owner)
                                + "', which cannot be converted, so it cannot be converted either.",
                        "Resolve the " + owner.name() + " first.", Need.NONE, null);
            }
            descendantsInsideBlocked(child, owner);
        }
    }

    private static XElement eventDefinition(XElement event) {
        for (XElement child : event.children()) {
            if (BPMN.equals(child.ns()) && child.name().endsWith("EventDefinition")) {
                return child;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ user and service tasks

    private static final Pattern PLAIN_VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Multi-instance on a call activity over a plain collection variable becomes a bounded for-each (S14); the source
     * collection has no bound, so the designer must set one. Everything else keeps blocking with the reason.
     */
    private void multiInstance(Analysis.Node node, XElement loop) {
        scanAttributes(loop, "multiInstanceLoopCharacteristics");
        XElement extensions = loop.child(BPMN, "extensionElements");
        if (extensions != null) {
            scanExtensions(loop, extensions);
        }
        XElement zeebeLoop = extensions == null ? null : extensions.child(ZB, "loopCharacteristics");
        String collection = zeebeLoop != null ? zeebeLoop.attr("inputCollection") : loop.attr(C7, "collection");
        String element = zeebeLoop != null ? zeebeLoop.attr("inputElement") : loop.attr(C7, "elementVariable");
        boolean sequential = "true".equalsIgnoreCase(loop.attr("isSequential"));
        String plain = plainVariable(collection);
        String reason = null;
        if (!"callActivity".equals(node.type)) {
            reason = "Only a call activity can run once per item; a " + node.type + " with multi-instance has no bounded "
                    + "equivalent in workflows.";
        } else if (loop.child(BPMN, "loopCardinality") != null) {
            reason = "It repeats a fixed count (loopCardinality) instead of reading a collection, so there is no item to pass on.";
        } else if (loop.child(BPMN, "completionCondition") != null) {
            reason = "It has a completion condition that ends the loop early, which a for-each cannot express.";
        } else if (zeebeLoop != null && blankToNull(zeebeLoop.attr("outputCollection")) != null) {
            reason = "It aggregates item results into an output collection by expression. Collected results are configured with "
                    + "collectionMapping, not translated.";
        } else if (plain == null) {
            reason = "Its collection (" + (blankToNull(collection) == null ? "none" : "'" + collection + "'") + ") is not a plain "
                    + "variable name, so the items cannot be taken from a request field.";
        } else if (element == null || !PLAIN_VARIABLE.matcher(element.strip()).matches()) {
            reason = "It declares no element variable, so no item is handed to the called process.";
        } else if (fieldKey(plain) == null) {
            reason = "The collection name '" + plain + "' cannot be used as a request field name.";
        }
        if (reason == null) {
            analysis.multiInstance.put(node.id, new Analysis.MultiInstance(plain, fieldKey(plain), element.strip(), sequential));
            analysis.assumptions.add("The collection '" + plain + "' becomes the required request field '" + fieldKey(plain)
                    + "' (an array of 1 to the maximum you set, scalar items only).");
            add(loop, "multiInstanceLoopCharacteristics", Report.REVIEW, "MULTI_INSTANCE_MAPPED",
                    "Runs the called process once per item of '" + plain + "' (" + (sequential ? "one after another" : "all at the "
                            + "same time, then waits for all") + "), handing each item over as the child field you name in the "
                            + "mapping (source element variable '" + element.strip() + "'). The source collection is unbounded; Orqix "
                            + "needs a limit of 1 to 25 items that you set in the mapping. Only text, number, integer and yes/no "
                            + "items are supported, and every item starts its own child run.",
                    "Set forEach.maxItems and forEach.itemInput in the child mapping and check that no run has more items.",
                    Need.ACKNOWLEDGE, null);
        } else {
            add(loop, "multiInstanceLoopCharacteristics", Report.BLOCKER, "MULTI_INSTANCE_NOT_SUPPORTED",
                    "Multi-instance execution cannot be converted. " + reason,
                    "Model one pass in a child workflow and call it once per item from a call activity over a plain collection, "
                            + "or start the workflow once per item.", Need.NONE, null);
        }
        loopParts(loop);
    }

    /** Child elements of a loop definition (cardinality, completion condition, loop condition) are never translated. */
    private void loopParts(XElement loop) {
        for (XElement part : loop.children()) {
            if (part.is(BPMN, "extensionElements") || part.is(BPMN, "documentation")) {
                continue;
            }
            if (BPMN.equals(part.ns()) && part.attr("id") != null) {
                add(part, part.name(), Report.BLOCKER, "MULTI_INSTANCE_NOT_SUPPORTED",
                        "The " + part.name() + " of a multi-instance activity (" + (part.text().isBlank() ? "no text" : "'" + part.text()
                                + "'") + ") is not translated.",
                        "Remove it in the source model or rebuild the loop in the studio.", Need.NONE, null);
            } else if (!BPMN.equals(part.ns())) {
                unknown(part);
            }
        }
    }

    /** The variable name of a plain {@code items}, {@code ${items}} or {@code =items} reference, or null. */
    private static String plainVariable(String text) {
        if (text == null) {
            return null;
        }
        String value = text.strip();
        if ((value.startsWith("${") || value.startsWith("#{")) && value.endsWith("}")) {
            value = value.substring(2, value.length() - 1).strip();
        } else if (value.startsWith("=")) {
            value = value.substring(1).strip();
        }
        return PLAIN_VARIABLE.matcher(value).matches() ? value : null;
    }

    private record Assignment(String kind, List<String> values) {
    }

    private static Assignment assignment(XElement userTask) {
        String groups = userTask.attr(C7, "candidateGroups");
        String users = userTask.attr(C7, "candidateUsers");
        String assignee = userTask.attr(C7, "assignee");
        XElement ext = userTask.child(BPMN, "extensionElements");
        XElement definition = ext == null ? null : ext.child(ZB, "assignmentDefinition");
        if (definition != null) {
            groups = definition.attr("candidateGroups");
            users = definition.attr("candidateUsers");
            assignee = definition.attr("assignee");
        }
        // A FEEL literal list is a fixed assignment, not an evaluated expression.
        if (groups != null && groups.matches("=\\s*\\[\\s*\"[A-Za-z0-9_-]+\"(?:\\s*,\\s*\"[A-Za-z0-9_-]+\")*\\s*\\]")) {
            var matcher = Pattern.compile("\"([A-Za-z0-9_-]+)\"").matcher(groups);
            List<String> literalGroups = new ArrayList<>();
            while (matcher.find()) literalGroups.add(matcher.group(1));
            groups = String.join(",", literalGroups);
        }
        List<String> all = new ArrayList<>();
        for (String raw : new String[] {groups, users, assignee}) {
            if (raw != null && (raw.contains("${") || raw.contains("#{") || raw.strip().startsWith("="))) {
                return new Assignment("expression", List.of());
            }
        }
        if (groups != null && !groups.isBlank()) {
            for (String g : groups.split(",")) {
                if (!g.isBlank() && !all.contains(g.strip())) {
                    all.add(g.strip());
                }
            }
            return new Assignment("groups", all);
        }
        for (String raw : new String[] {assignee, users}) {
            if (raw != null) {
                for (String u : raw.split(",")) {
                    if (!u.isBlank() && !all.contains(u.strip())) {
                        all.add(u.strip());
                    }
                }
            }
        }
        return all.isEmpty() ? new Assignment("none", List.of()) : new Assignment("assignee", all);
    }

    private void service(Analysis.Node node) {
        XElement e = node.element;
        String javaClass = e.attr(C7, "class");
        String delegate = e.attr(C7, "delegateExpression");
        String expression = e.attr(C7, "expression");
        String type = e.attr(C7, "type");
        String topic = e.attr(C7, "topic");
        XElement ext = e.child(BPMN, "extensionElements");
        XElement taskDefinition = ext == null ? null : ext.child(ZB, "taskDefinition");
        XElement connector = ext == null ? null : ext.child(C7, "connector");
        // Camunda 8: a job type is a job worker, the REST connector (mappable) or another connector (not supported).
        boolean zeebeJob = taskDefinition != null && javaClass == null && delegate == null && expression == null && type == null;
        String jobType = zeebeJob ? blankToNull(taskDefinition.attr("type")) : null;
        if (zeebeJob && jobType != null && jobType.startsWith(Zeebe.HTTP_JSON)) {
            httpJsonConnector(node, ext, taskDefinition, jobType);
            return;
        }
        if (zeebeJob && isConnector(jobType)) {
            unsupportedConnector(node, jobType);
            return;
        }
        Report.ServiceHint workerHint = zeebeJob ? workerHint(node, ext, jobType) : null;
        if (workerHint != null) {
            analysis.serviceHints.put(node.id, workerHint);
        }
        String code;
        String detail;
        if (javaClass != null) {
            code = "JAVA_DELEGATE_NOT_PORTABLE";
            detail = "Java class " + javaClass;
        } else if (delegate != null) {
            code = "JAVA_DELEGATE_NOT_PORTABLE";
            detail = "delegate expression " + delegate;
        } else if (expression != null) {
            code = "JAVA_DELEGATE_NOT_PORTABLE";
            detail = "expression " + expression;
        } else if ("external".equals(type)) {
            code = "WORKER_NOT_PORTABLE";
            detail = "external task worker for topic '" + (topic == null ? "" : topic) + "'";
        } else if (type != null) {
            code = "JAVA_DELEGATE_NOT_PORTABLE";
            detail = "built-in task type '" + type + "'";
        } else if (taskDefinition != null) {
            code = "WORKER_NOT_PORTABLE";
            detail = "job worker for job type '" + (taskDefinition.attr("type") == null ? "" : taskDefinition.attr("type")) + "'";
        } else if (connector != null) {
            code = "JAVA_DELEGATE_NOT_PORTABLE";
            detail = "Camunda connector";
        } else {
            code = "JAVA_DELEGATE_NOT_PORTABLE";
            detail = "no implementation declared";
        }
        String group = serviceGroupKey(javaClass, delegate, expression, type, topic, taskDefinition);
        if (group != null) {
            analysis.serviceGroups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(node.id);
        }
        analysis.services.put(node.id, new Analysis.Service(node, code, detail));
        Analysis.Item item = add(e, "serviceTask", Report.BLOCKER, code,
                "This service task runs " + detail + ". Source code is never imported or executed by Orqix."
                        + (workerHint != null && workerHint.suggestedOperation() != null ? " Suggested operation name: '"
                        + workerHint.suggestedOperation() + "' (from the job type)." : ""),
                "Register a connection operation that does the same work, then map this step to it (request fields and "
                        + "result) and test the behavior.",
                Need.SERVICE, node.id);
        item.reviewAfterResolution = reviewOf(item.issue, "SERVICE_MAPPED",
                "The service task (" + detail + ") is replaced by a call to the registered connection operation you chose. "
                        + "Request fields, the result, timeout (default 10 seconds) and retries now follow the connection "
                        + "definition, not the source implementation, which is never executed.",
                "Compare request, result, error and retry behaviour of the operation with the source implementation.");
    }

    /**
     * Identity of the implementation behind a service task: tasks with the same key can share one mapping. Only literal
     * identifiers group (an expression that is computed at run time cannot be compared); the text is never evaluated.
     */
    private static String serviceGroupKey(String javaClass, String delegate, String expression, String type, String topic,
            XElement taskDefinition) {
        String key = null;
        if (javaClass != null) {
            key = "class:" + javaClass.strip();
        } else if (delegate != null) {
            key = "delegateExpression:" + delegate.strip();
        } else if (expression != null) {
            key = "expression:" + expression.strip();
        } else if ("external".equals(type) && topic != null && !topic.isBlank()) {
            key = "topic:" + topic.strip();
        } else if (type == null && taskDefinition != null && taskDefinition.attr("type") != null
                && !taskDefinition.attr("type").isBlank()) {
            key = "jobType:" + taskDefinition.attr("type").strip();
        }
        if (key == null || key.length() > 250) {
            return null;
        }
        boolean computed = key.startsWith("topic:") || key.startsWith("jobType:") || key.startsWith("class:")
                ? key.contains("${") || key.contains("#{") || key.substring(key.indexOf(':') + 1).startsWith("=")
                : false;
        return computed ? null : key;
    }

    // ------------------------------------------------------------------------------------------------ Camunda 8 connectors

    private static boolean isConnector(String jobType) {
        return jobType != null && (jobType.startsWith("io.camunda:") || jobType.startsWith("io.camunda.connector"));
    }

    /** The job type of a Camunda 8 connector on this element, or null. */
    private static String connectorJobType(XElement element) {
        XElement ext = element.child(BPMN, "extensionElements");
        XElement definition = ext == null ? null : ext.child(ZB, "taskDefinition");
        String type = definition == null ? null : blankToNull(definition.attr("type"));
        return isConnector(type) ? type : null;
    }

    /** Suggested operation name: the element name, else the job type, as a camelCase operation key. */
    private static String suggestedOperation(Analysis.Node node, String jobType) {
        String fromName = Zeebe.operationName(node.name);
        return fromName != null ? fromName : Zeebe.operationName(jobType);
    }

    /** A plain job worker: facts for the mapping helper. Variables are suggestions, never evaluated. */
    private Report.ServiceHint workerHint(Analysis.Node node, XElement ext, String jobType) {
        XElement io = ext.child(ZB, "ioMapping");
        Map<String, String> inputFields = new LinkedHashMap<>();
        Zeebe.mappings(io, "input").forEach((target, source) -> {
            String variable = Zeebe.variable(source);
            if (variable != null && fieldKey(variable) != null && fieldKey(target) != null) {
                inputFields.put(fieldKey(target), fieldKey(variable));
            }
        });
        Map<String, String> resultFields = new LinkedHashMap<>();
        Zeebe.mappings(io, "output").forEach((target, source) -> {
            String path = Zeebe.path(source);
            if (path != null && fieldKey(target) != null) {
                resultFields.put(fieldKey(target), "/" + path.replace('.', '/'));
            }
        });
        return new Report.ServiceHint("jobWorker", jobType, suggestedOperation(node, jobType), null, null, null, false, null, List.of(),
                List.of(), null, inputFields, resultFields, List.of(), null);
    }

    /**
     * The Camunda 8 REST connector ({@code io.camunda:http-json}): becomes a "Call a system" step against a connection
     * operation the designer selects. Method, path, body field names and result pointers are derived as suggestions; the URL,
     * headers and credentials stay with the connection and are never copied into the workflow.
     */
    private void httpJsonConnector(Analysis.Node node, XElement ext, XElement taskDefinition, String jobType) {
        XElement e = node.element;
        XElement io = ext.child(ZB, "ioMapping");
        Map<String, String> inputs = Zeebe.mappings(io, "input");
        Map<String, String> headers = Zeebe.headers(ext.child(ZB, "taskHeaders"));
        List<String> unmapped = new ArrayList<>();
        String method = Zeebe.literal(inputs.get("method"));
        if (method == null && inputs.containsKey("method")) {
            unmapped.add("method: " + Zeebe.mask(inputs.get("method")));
        }
        method = method == null ? null : method.strip().toUpperCase(Locale.ROOT);
        String rawUrl = inputs.get("url");
        String url = Zeebe.literal(rawUrl);
        boolean computed = rawUrl != null && url == null;
        String host = null;
        String path = null;
        List<String> query = List.of();
        if (url != null && !url.isBlank()) {
            String[] parts = Zeebe.hostAndPath(url);
            host = parts[0];
            path = parts[1];
            query = Zeebe.queryNames(url);
        } else if (computed) {
            unmapped.add("url: " + Zeebe.mask(rawUrl));
        }
        String authType = Zeebe.literal(inputs.get("authentication.type"));
        List<String> headerNames = new ArrayList<>();
        List<String[]> headerEntries = Zeebe.context(inputs.get("headers"));
        if (headerEntries != null) {
            headerEntries.forEach(entry -> headerNames.add(entry[0]));
        } else if (inputs.containsKey("headers")) {
            unmapped.add("headers: computed");
        }
        Map<String, String> inputFields = new LinkedHashMap<>();
        for (String source : List.of("queryParameters", "body")) {
            if (!inputs.containsKey(source)) {
                continue;
            }
            List<String[]> entries = Zeebe.context(inputs.get(source));
            if (entries == null) {
                unmapped.add(source + ": " + Zeebe.mask(inputs.get(source)));
                continue;
            }
            for (String[] entry : entries) {
                String variable = Zeebe.variable(entry[1]);
                if (variable != null && fieldKey(variable) != null && fieldKey(entry[0]) != null) {
                    inputFields.put(fieldKey(entry[0]), fieldKey(variable));
                } else {
                    unmapped.add(source + "." + entry[0] + ": " + Zeebe.mask(entry[1]));
                }
            }
        }
        Map<String, String> resultFields = new LinkedHashMap<>();
        String resultVariable = Zeebe.literal(headers.get("resultVariable"));
        if (headers.containsKey("resultExpression")) {
            List<String[]> entries = Zeebe.context(headers.get("resultExpression"));
            if (entries == null) {
                unmapped.add("resultExpression: " + Zeebe.mask(headers.get("resultExpression")));
            } else {
                for (String[] entry : entries) {
                    String pointer = Zeebe.responsePointer(entry[1]);
                    if (pointer != null && fieldKey(entry[0]) != null) {
                        resultFields.put(fieldKey(entry[0]), pointer);
                    } else {
                        unmapped.add("resultExpression." + entry[0] + ": " + Zeebe.mask(entry[1]));
                    }
                }
            }
        }
        List<String> ignored = new ArrayList<>();
        inputs.keySet().stream().filter(k -> !Set.of("method", "url", "headers", "body", "queryParameters", "authentication.type")
                .contains(k) && !k.startsWith("authentication.")).forEach(ignored::add);
        headers.keySet().stream().filter(k -> !Set.of("resultVariable", "resultExpression").contains(k)).forEach(ignored::add);
        taskDefinition.attributes().keySet().stream().filter(k -> !"type".equals(k)).forEach(ignored::add);
        Report.ServiceHint hint = new Report.ServiceHint("httpJson", jobType, suggestedOperation(node, jobType), method, host, path, computed,
                authType, headerNames, query, resultVariable, inputFields, resultFields, unmapped, null);
        analysis.serviceHints.put(node.id, hint);
        analysis.services.put(node.id, new Analysis.Service(node, "CONNECTOR_MAPPING_REQUIRED", "REST connector (http-json)"));
        String origin = (method == null ? "request" : method) + " " + (host != null ? host : "") + (path != null ? path
                : computed ? "(address computed by an expression)" : "(no address)");
        Analysis.Item item = add(e, "serviceTask", Report.BLOCKER, "CONNECTOR_MAPPING_REQUIRED",
                "This is the Camunda 8 REST connector. Original call (read-only provenance): " + origin + ". It becomes a call to a "
                        + "connection operation you choose; the address, credentials and headers belong to the connection, never to "
                        + "the workflow." + (authType == null ? "" : " Source authentication: " + authType + " (not copied).")
                        + (inputFields.isEmpty() ? "" : " Request fields read from variables: " + String.join(", ", inputFields.values()) + ".")
                        + (resultFields.isEmpty() ? "" : " Result values read: " + String.join(", ", resultFields.keySet()) + ".")
                        + (unmapped.isEmpty() ? "" : " Not derived (shown, never evaluated): " + String.join("; ", unmapped) + ".")
                        + (ignored.isEmpty() ? "" : " Other connector settings are not imported: " + String.join(", ", ignored) + "."),
                "Choose the registered connection operation" + (method == null ? "" : " (" + method + (path == null ? "" : " " + path) + ")")
                        + " that makes this call, check the request and result mapping, and test it.",
                Need.SERVICE, node.id);
        item.reviewAfterResolution = reviewOf(item.issue, "CONNECTOR_MAPPED",
                "The REST connector call (" + origin + ") is replaced by a call to the registered connection operation you chose. The "
                        + "address, credentials, headers, timeout (default 10 seconds), retries and error handling now follow the "
                        + "connection definition, not the connector settings of the source.",
                "Compare the method and path, the request body and the result of the operation with the original call"
                        + ", and test both a success and a failure.");
    }

    /** Any other Camunda 8 connector (Slack, e-mail, GraphQL, ...): not convertible, with a suggested replacement. */
    private void unsupportedConnector(Analysis.Node node, String jobType) {
        String lower = jobType.toLowerCase(Locale.ROOT);
        String replacement;
        if (lower.contains("slack") || lower.contains("mail") || lower.contains("sendgrid") || lower.contains("twilio")
                || lower.contains("teams") || lower.contains("whatsapp") || lower.contains("sns")) {
            replacement = "Use a notification step (an in-app message to the requester), or register a connection to the messaging "
                    + "service and rebuild the step as a call to it.";
        } else {
            replacement = "Register a connection to that system and rebuild the step as a call to one of its operations.";
        }
        analysis.serviceHints.put(node.id, new Report.ServiceHint("unsupportedConnector", jobType, null, null, null, null, false, null,
                List.of(), List.of(), null, Map.of(), Map.of(), List.of(), replacement));
        add(node.element, node.type, Report.BLOCKER, "CONNECTOR_NOT_SUPPORTED",
                "This step uses the Camunda 8 connector '" + jobType + "'. Only the REST (http-json) connector can be converted; "
                        + "this one is not supported and its configuration is never imported.",
                replacement, Need.NONE, null);
    }

    /** Call activity facts: called process, variable propagation and mapping suggestions from its input/output mapping. */
    private void callHint(Analysis.Node node, XElement called, XElement ext, String processId) {
        XElement io = ext.child(ZB, "ioMapping");
        Map<String, String> inputs = new LinkedHashMap<>();
        Zeebe.mappings(io, "input").forEach((target, source) -> {
            String variable = Zeebe.variable(source);
            if (variable != null && fieldKey(variable) != null && fieldKey(target) != null) {
                inputs.put(fieldKey(target), "/input/" + fieldKey(variable));
            }
        });
        Map<String, String> results = new LinkedHashMap<>();
        Zeebe.mappings(io, "output").forEach((target, source) -> {
            String variable = Zeebe.variable(source);
            if (variable != null && fieldKey(variable) != null && fieldKey(target) != null) {
                results.put(fieldKey(target), fieldKey(variable));
            }
        });
        boolean child = !"false".equalsIgnoreCase(called.attr("propagateAllChildVariables"));
        boolean parent = !"false".equalsIgnoreCase(called.attr("propagateAllParentVariables"));
        analysis.callHints.put(node.id, new Report.CallHint(processId, blankToNull(called.attr("bindingType")) == null ? "latest"
                : called.attr("bindingType").strip(), child, parent, inputs, results));
        if (child || parent) {
            add(called, "callActivity", Report.INFO, "PROPAGATE_ALL_NOT_SUPPORTED",
                    "Camunda 8 hands " + (parent ? "every variable of this process to the called process" : "no variable down")
                            + (child ? " and takes every variable of the called process back" : "") + " unless propagation is switched "
                            + "off. Orqix passes only the fields you list in the child workflow mapping and reads back only the "
                            + "results you list." + (inputs.isEmpty() && results.isEmpty() ? "" : " Suggested from the source input/output "
                            + "mapping: inputs " + (inputs.isEmpty() ? "none" : String.join(", ", inputs.keySet())) + "; results "
                            + (results.isEmpty() ? "none" : String.join(", ", results.keySet())) + "."),
                    "Acknowledge, and list every field the called process needs and every result this process reads afterwards.",
                    Need.ACKNOWLEDGE, null);
        }
    }

    // ------------------------------------------------------------------------------------------------ Camunda 8 forms and schedule

    /** {@code zeebe:taskSchedule}: a due time written as a duration becomes the display-only due time of the approval. */
    private void schedule(XElement owner, XElement schedule) {
        String due = blankToNull(schedule.attr("dueDate"));
        String followUp = blankToNull(schedule.attr("followUpDate"));
        if (due != null) {
            Integer hours = "userTask".equals(owner.name()) ? Zeebe.dueHours(due) : null;
            if (hours != null) {
                dueHours.put(idOf(owner), hours);
                add(schedule, "taskSchedule", Report.REVIEW, "DUE_DATE_MAPPED",
                        "The due time (" + Zeebe.mask(due) + ") becomes a due time of " + hours + " hour(s) on the approval. It is "
                                + "displayed on the task only: no reminder is sent and nothing happens when it passes (use a boundary "
                                + "timer for a real time limit).",
                        "Check the due time.", Need.ACKNOWLEDGE, null);
            } else {
                add(schedule, "taskSchedule", Report.INFO, "DUE_DATE_NOT_IMPORTED",
                        "The due date '" + Zeebe.mask(due) + "' is not a duration of whole hours (1 to 720) counted from when the task "
                                + "opens, so it is not imported. An absolute or computed date has no equivalent on an approval.",
                        "Acknowledge; use a duration such as =now() + duration(\"PT48H\") in the source to import it.", Need.ACKNOWLEDGE, null);
            }
        }
        if (followUp != null) {
            add(schedule, "taskSchedule", Report.INFO, "FOLLOW_UP_NOT_IMPORTED",
                    "The follow-up date '" + Zeebe.mask(followUp) + "' is not imported; approvals have no follow-up date or reminder.",
                    "Acknowledge.", Need.ACKNOWLEDGE, null);
        }
        if (due == null && followUp == null) {
            add(schedule, "taskSchedule", Report.INFO, "SETTING_NOT_IMPORTED", "The empty task schedule is not imported.",
                    "Acknowledge.", Need.ACKNOWLEDGE, null);
        }
    }

    /** A {@code zeebe:userTaskForm} at process level: remembered by id and read when an element links to it. */
    private void embeddedFormSource(XElement owner, XElement form) {
        String id = blankToNull(form.attr("id"));
        if (id == null || !owner.is(BPMN, "process")) {
            unknown(form);
            return;
        }
        embeddedSources.put(id, form.text());
        embeddedElements.put(id, form);
    }

    private void unusedEmbeddedForms() {
        embeddedElements.forEach((id, element) -> {
            if (!embeddedUsed.contains(id)) {
                add(element, "userTaskForm", Report.INFO, "FORM_NOT_IMPORTED",
                        "The embedded form '" + id + "' is not linked to any start event or user task, so it is not imported.",
                        "Acknowledge.", Need.ACKNOWLEDGE, null);
            }
        });
    }

    /** {@code zeebe:formDefinition} of a start event or user task: an embedded form is read, a linked form file is requested. */
    private void formLink(XElement owner, XElement definition) {
        String ownerId = idOf(owner);
        String target = owner.is(BPMN, "startEvent") ? "start" : "userTask";
        String formKey = blankToNull(definition.attr("formKey"));
        String formId = blankToNull(definition.attr("formId"));
        String binding = blankToNull(definition.attr("bindingType"));
        String ownerName = blankToNull(owner.attr("name"));
        if (formKey != null && formKey.startsWith("camunda-forms:bpmn:")) {
            String embedded = formKey.substring("camunda-forms:bpmn:".length());
            String text = embeddedSources.get(embedded);
            if (text == null) {
                add(definition, "formDefinition", Report.BLOCKER, "FORM_NOT_FOUND",
                        "The form '" + embedded + "' is referenced here but the file has no zeebe:userTaskForm with that id.",
                        "Fix the reference in the source file.", Need.NONE, null);
                return;
            }
            embeddedUsed.add(embedded);
            FormImport.Model model = embeddedModels.get(embedded);
            boolean first = model == null;
            if (first) {
                try {
                    model = FormImport.parse(text, null, "userTaskForm[" + embedded + "]");
                } catch (SourceException e) {
                    add(definition, "formDefinition", Report.BLOCKER, "FORM_INVALID",
                            "The embedded form '" + embedded + "' cannot be read: " + e.getMessage(), "Export the form again.", Need.NONE,
                            null);
                    return;
                }
                embeddedModels.put(embedded, model);
            }
            analysis.embeddedForms.put(ownerId, model);
            analysis.formHints.put(ownerId, new Report.FormHint(target, embedded, formKey, binding, "embedded"));
            if (first) {
                for (Report.Issue issue : model.issuesFor(target, ownerName)) {
                    Need need = Report.BLOCKER.equals(issue.severity()) || Report.ACTION.equals(issue.severity()) ? Need.NONE
                            : Report.SUPPORTED.equals(issue.severity()) ? Need.NONE : Need.ACKNOWLEDGE;
                    items.add(new Analysis.Item(issue, need, null, definition.ordinal(), sequence++));
                }
            } else {
                add(definition, "formDefinition", Report.INFO, "FORM_SHARED",
                        "This " + target + " uses the same form '" + embedded + "' as another step; its fields are imported once.",
                        "Acknowledge.", Need.ACKNOWLEDGE, null);
            }
            return;
        }
        if (formId != null) {
            analysis.formHints.put(ownerId, new Report.FormHint(target, formId, null, binding, "upload"));
            analysis.linkedForms.put(ownerId, formId);
            Analysis.Item item = add(definition, "formDefinition", Report.INFO, "FORM_NOT_IMPORTED",
                    "The " + (target.equals("start") ? "start form" : "task form") + " '" + formId + "' is a separate Camunda form file"
                            + (binding == null ? "" : " (binding " + binding + ")") + ". Upload the .form file in the form step of this page "
                            + "to import it" + (target.equals("start") ? " as the request form" : " as a note on the approval step")
                            + "; without it the form is not imported.",
                    "Upload the form file with the id '" + formId + "', or acknowledge that the form is left out.", Need.FORM, ownerId);
            item.reviewAfterResolution = reviewOf(item.issue, "FORM_MAPPED", "The uploaded form '" + formId + "' is imported"
                    + (target.equals("start") ? " as the request form of the workflow." : " as a note on the approval step."),
                    "Check the imported fields in the form preview and in the studio.");
            return;
        }
        analysis.formHints.put(ownerId, new Report.FormHint(target, null, formKey != null ? formKey : blankToNull(definition.attr("externalReference")),
                binding, "unsupported"));
        add(definition, "formDefinition", Report.INFO, "FORM_NOT_IMPORTED",
                "The form reference '" + (formKey != null ? formKey : definition.attr("externalReference")) + "' is a custom form that "
                        + "Orqix cannot read, so the form is not imported.",
                "Acknowledge, and review the request form in the studio.", Need.ACKNOWLEDGE, null);
    }

    /** One item per implementation shared by two or more service tasks: one mapping resolves all of them. */
    private void serviceGroupItems() {
        for (Map.Entry<String, List<String>> group : analysis.serviceGroups.entrySet()) {
            List<String> ids = group.getValue();
            if (ids.size() < 2) {
                continue;
            }
            Analysis.Node first = analysis.nodes.get(ids.get(0));
            String kind = group.getKey().substring(0, group.getKey().indexOf(':'));
            Report.Issue issue = new Report.Issue(group.getKey(), group.getKey().substring(group.getKey().indexOf(':') + 1),
                    "serviceTaskGroup", Report.ACTION, "SERVICE_TASK_GROUP",
                    ids.size() + " service tasks share the " + describeKind(kind) + " '"
                            + group.getKey().substring(group.getKey().indexOf(':') + 1) + "': " + quoted(ids)
                            + ". One mapping under serviceGroupMappings['" + group.getKey() + "'] covers all of them; a mapping "
                            + "under serviceTaskMappings overrides it for a single task.",
                    "Register one connection operation for this implementation and map the group once.", null, Report.OPEN,
                    pathOf(first.element));
            items.add(new Analysis.Item(issue, Need.SERVICE_GROUP, group.getKey(), first.element.ordinal(), sequence++));
        }
    }

    private static String describeKind(String kind) {
        return switch (kind) {
            case "topic" -> "external task topic";
            case "jobType" -> "job type";
            case "class" -> "delegate class";
            case "delegateExpression" -> "delegate expression";
            default -> "expression";
        };
    }

    /** DMN decision called by a business rule task: mapped to an uploaded DMN decision, or blocked. */
    private void businessRule(Analysis.Node node) {
        XElement e = node.element;
        String ref = e.attr(C7, "decisionRef");
        String resultVariable = e.attr(C7, "resultVariable");
        String resultMapping = e.attr(C7, "mapDecisionResult");
        XElement ext = e.child(BPMN, "extensionElements");
        XElement called = ext == null ? null : ext.child(ZB, "calledDecision");
        if (called != null) {
            ref = called.attr("decisionId");
            resultVariable = called.attr("resultVariable");
            resultMapping = null;
        }
        boolean literal = ref != null && ref.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}");
        boolean otherImplementation = e.attr(C7, "class") != null || e.attr(C7, "delegateExpression") != null
                || e.attr(C7, "expression") != null || "external".equals(e.attr(C7, "type"));
        if (!literal || otherImplementation) {
            add(e, node.type, Report.BLOCKER, "DMN_IMPORT_NOT_SUPPORTED",
                    otherImplementation ? "This business rule task runs a delegate, expression or external worker instead of a DMN "
                            + "decision, which is never executed by Orqix."
                            : "This business rule task does not name a literal DMN decision id (" + (ref == null ? "none"
                            : "'" + ref + "'") + "), so no decision table can be attached.",
                    "Rebuild the logic as a decision table in the studio, or name the decision with a literal id.", Need.NONE, null);
            return;
        }
        analysis.decisions.put(node.id, new Analysis.DecisionRef(ref, blankToNull(resultVariable), blankToNull(resultMapping)));
        if (blankToNull(resultVariable) != null) {
            analysis.decisionResults.putIfAbsent(resultVariable.strip(), node.id);
        }
        boolean zeebeDecision = called != null;
        Analysis.Item item = add(e, node.type, zeebeDecision ? Report.ACTION : Report.BLOCKER,
                zeebeDecision ? "DECISION_MAPPING_REQUIRED" : "DMN_IMPORT_NOT_SUPPORTED",
                "This business rule task calls the DMN decision '" + ref + "'. It becomes a decision step once you upload that "
                        + "DMN file as a separate migration and map the task to it under decisionMappings."
                        + (zeebeDecision ? " A Camunda 8 deployment normally ships the .dmn file next to the process; upload that file "
                        + "(source format dmn) or import both together in a project." : ""),
                "Upload the DMN file (source format dmn), then map this task: {\"migrationId\": <DMN migration id>, "
                        + "\"decisionId\": \"" + ref + "\"}.",
                Need.DECISION, node.id);
        item.reviewAfterResolution = reviewOf(item.issue, "DECISION_MAPPED",
                "The call becomes a decision step that evaluates the converted decision table of '" + ref + "'. Orqix tables "
                        + "test one request or step field and return the first-and-only matching row (or the default); the "
                        + "source engine's variable scoping, result variable shapes and decision versioning are not carried over.",
                "Run the generated boundary cases of the DMN report and compare with the source decision on real samples.");
    }

    private static Report.Issue reviewOf(Report.Issue base, String code, String explanation, String suggestion) {
        return new Report.Issue(base.sourceElementId(), base.sourceName(), base.sourceKind(), Report.REVIEW, code, explanation,
                suggestion, base.targetNodeId(), Report.OPEN, base.sourcePath());
    }

    // ------------------------------------------------------------------------------------------------ graph analysis

    private void graphAnalysis() {
        // Conditions of flows that leave exclusive gateways.
        for (Analysis.Flow flow : analysis.flows.values()) {
            XElement condition = flow.element.child(BPMN, "conditionExpression");
            flow.hasConditionElement = condition != null;
            if (flow.source.type.equals("exclusiveGateway") && condition != null) {
                flow.condition = Expressions.parse(condition.text(), blankToNull(condition.attr("language")),
                        condition.attr(C7, "resource"));
            }
        }
        // User tasks first: they decide which gateways are consumed as approval outcome routers.
        for (Analysis.UserTask task : analysis.userTasks.values()) {
            analyseUserTask(task);
        }
        boundaryTimers();
        for (Analysis.Node node : analysis.nodes.values()) {
            if ("exclusiveGateway".equals(node.type)) {
                analyseGateway(node);
            }
        }
        analyseParallel();
        for (Analysis.UserTask task : analysis.userTasks.values()) {
            userTaskItem(task);
        }
        for (Analysis.Flow flow : analysis.flows.values()) {
            flowItem(flow);
        }
    }

    /** Only paired, acyclic split/join regions are supported; the compiler verifies branch boundaries. */
    private void analyseParallel() {
        Set<String> paired = new HashSet<>();
        for (Analysis.Node split : analysis.nodes.values()) {
            if (!"parallelGateway".equals(split.type) || split.in.size() != 1 || split.out.size() < 2) continue;
            Set<String> candidates = null;
            for (Analysis.Flow branch : split.out) {
                Set<String> reached = new HashSet<>();
                Deque<Analysis.Node> pending = new java.util.ArrayDeque<>(); pending.add(branch.target);
                while (!pending.isEmpty()) {
                    Analysis.Node current = pending.remove();
                    if (!reached.add(current.id)) continue;
                    current.out.forEach(f -> pending.add(f.target));
                }
                if (candidates == null) candidates = reached; else candidates.retainAll(reached);
            }
            List<Analysis.Node> joins = candidates == null ? List.of() : candidates.stream().map(analysis.nodes::get)
                .filter(n -> "parallelGateway".equals(n.type) && n.in.size() == split.out.size() && n.out.size() == 1)
                .toList();
            if (joins.size() == 1 && paired.add(joins.get(0).id)) {
                analysis.parallelJoins.put(split.id, joins.get(0).id);
                add(split.element, split.type, Report.REVIEW, "PARALLEL_SPLIT_MAPPED",
                        "All branches execute; continuation waits at the paired join. Unlike the source engine, a rejected finish "
                                + "inside one branch ends the whole request and cancels the other branches' open tasks.",
                        "Check that no branch is meant to continue after another branch was rejected.", Need.ACKNOWLEDGE, null);
                add(joins.get(0).element, joins.get(0).type, Report.SUPPORTED, "PARALLEL_JOIN_MAPPED", "Waits for every branch of the paired split.", null, Need.NONE, null);
            }
        }
        for (Analysis.Node n : analysis.nodes.values()) if ("parallelGateway".equals(n.type)
                && !analysis.parallelJoins.containsKey(n.id) && !paired.contains(n.id))
            add(n.element, n.type, Report.BLOCKER, "GATEWAY_NOT_SUPPORTED_IN_V0", "The parallel gateway is not an unambiguous paired split/join.", "Use separate split and join gateways with matching branch counts.", Need.NONE, null);
    }

    private void analyseUserTask(Analysis.UserTask task) {
        Analysis.Node node = task.node;
        if (node.out.size() == 1 && "exclusiveGateway".equals(node.out.get(0).target.type)
                && node.out.get(0).target.in.size() == 1) {
            Analysis.Node gateway = node.out.get(0).target;
            task.outcomeGateway = gateway;
            if (gateway.out.size() == 2) {
                task.candidateFlows.addAll(gateway.out);
                task.recognized = recognise(task, gateway);
                analysis.outcomeGatewayOf.put(node.id, gateway.id);
            } else {
                task.outcomesImpossible = true;
            }
        } else if (node.out.size() == 2) {
            task.candidateFlows.addAll(node.out);
        } else {
            task.outcomesImpossible = true;
        }
    }

    /** Looks for exactly two conditions on one boolean variable: true / false, or one of them plus the default flow. */
    private Analysis.Route recognise(Analysis.UserTask task, Analysis.Node gateway) {
        String variable = null;
        Analysis.Flow whenTrue = null;
        Analysis.Flow whenFalse = null;
        Analysis.Flow byDefault = null;
        String defaultId = blankToNull(gateway.element.attr("default"));
        for (Analysis.Flow flow : gateway.out) {
            if (flow.condition == null) {
                if (!flow.hasConditionElement && flow.id.equals(defaultId) && byDefault == null) {
                    byDefault = flow;
                    continue;
                }
                return null;
            }
            if (!(flow.condition instanceof Expressions.Comparison c) || !c.isBoolean() || c.op() != Expressions.Op.EQ) {
                return null;
            }
            if (variable != null && !variable.equals(c.variable())) {
                return null;
            }
            variable = c.variable();
            if (Boolean.TRUE.equals(c.literal())) {
                if (whenTrue != null) {
                    return null;
                }
                whenTrue = flow;
            } else {
                if (whenFalse != null) {
                    return null;
                }
                whenFalse = flow;
            }
        }
        if (variable == null) {
            return null;
        }
        Analysis.Flow positive = whenTrue != null ? whenTrue : byDefault;
        Analysis.Flow negative = whenFalse != null ? whenFalse : byDefault;
        if (positive == null || negative == null || positive == negative) {
            return null;
        }
        boolean inverted = REJECTING_NAME.matcher(variable).matches();
        return inverted ? new Analysis.Route(negative.id, positive.id, gateway.id, variable, true)
                : new Analysis.Route(positive.id, negative.id, gateway.id, variable, false);
    }

    private void analyseGateway(Analysis.Node gateway) {
        XElement e = gateway.element;
        boolean consumed = analysis.outcomeGatewayOf.containsValue(gateway.id);
        if (consumed) {
            return; // disposition is given together with the user task
        }
        if (gateway.out.size() <= 1) {
            if (gateway.out.isEmpty()) {
                add(e, "exclusiveGateway", Report.BLOCKER, "GATEWAY_NOT_SUPPORTED_IN_V0",
                        "This gateway has no outgoing flow, so there is nowhere to continue.",
                        "Connect it to the next step in the source file.", Need.NONE, null);
            } else {
                analysis.mergeGateways.add(gateway.id);
                add(e, "exclusiveGateway", Report.SUPPORTED, "MERGE_GATEWAY_REMOVED",
                        "A gateway with one outgoing flow only merges paths. Workflows merge implicitly, so it is removed and "
                                + "its incoming flows go straight to the next step.",
                        null, Need.NONE, null);
            }
            return;
        }
        if (gateway.in.size() > 1) {
            add(e, "exclusiveGateway", Report.BLOCKER, "GATEWAY_NOT_SUPPORTED_IN_V0",
                    "This gateway merges several paths and also splits into several. Separate the merge and the choice into "
                            + "two gateways.",
                    "Split it in the source model.", Need.NONE, null);
            return;
        }
        branch(gateway);
    }

    private void branch(Analysis.Node gateway) {
        XElement e = gateway.element;
        String defaultId = blankToNull(e.attr("default"));
        List<Analysis.BranchEntry> entries = new ArrayList<>();
        Map<String, String> variables = new LinkedHashMap<>();
        Analysis.Flow defaultFlow = null;
        Analysis.Flow derivedDefaultFrom = null;
        for (Analysis.Flow flow : gateway.out) {
            if (flow.id.equals(defaultId)) {
                if (flow.hasConditionElement) {
                    expressionBlocker(gateway, "The default flow '" + labelOf(flow.element) + "' also has a condition.");
                    return;
                }
                defaultFlow = flow;
                continue;
            }
            if (flow.condition == null) {
                expressionBlocker(gateway, "The flow '" + labelOf(flow.element) + "' has no condition and is not the default "
                        + "flow.");
                return;
            }
            if (flow.condition instanceof Expressions.Unsupported u) {
                expressionBlocker(gateway, "The condition of flow '" + labelOf(flow.element) + "' is not supported: "
                        + u.reason());
                return;
            }
            String group = flow.condition instanceof Expressions.Logical l ? l.group() : null;
            List<Expressions.Comparison> terms = flow.condition instanceof Expressions.Logical l ? l.terms()
                    : List.of((Expressions.Comparison) flow.condition);
            for (Expressions.Comparison c : terms) {
                String previous = variables.putIfAbsent(c.variable(), c.valueType());
                if (previous != null && !previous.equals(c.valueType())) {
                    expressionBlocker(gateway, "The conditions compare '" + c.variable() + "' with values of different types.");
                    return;
                }
                if (c.op().ordering() && !c.valueType().equals("number")) {
                    expressionBlocker(gateway, "'" + c.op().symbol() + "' only works with numbers.");
                    return;
                }
            }
            entries.add(new Analysis.BranchEntry(flow.id, group, terms, false, conditionText(flow)));
        }
        if (entries.isEmpty()) {
            expressionBlocker(gateway, "None of the flows has a condition.");
            return;
        }
        // The compiler lets plain conditions test one field only; groups may combine fields.
        boolean anyGroup = entries.stream().anyMatch(Analysis.BranchEntry::compound);
        if (!anyGroup && variables.size() > 1) {
            expressionBlocker(gateway, "The conditions test different variables (" + quoted(new ArrayList<>(variables.keySet()))
                    + "). A choice without 'and'/'or' groups can test one field.");
            return;
        }
        for (String variable : variables.keySet()) {
            if (fieldKey(variable) == null) {
                expressionBlocker(gateway, "The variable name '" + variable + "' cannot be used as a form field name.");
                return;
            }
            String task = analysis.decisionResults.get(variable);
            if (task != null && !"camunda8".equals(analysis.sourceSystem())
                    && !"singleEntry".equals(analysis.decisions.get(task).resultMapping())) {
                expressionBlocker(gateway, "The variable '" + variable + "' is the result of business rule task '" + task
                        + "', whose result is a list or map (mapDecisionResult " + analysis.decisions.get(task).resultMapping()
                        + "). Only a singleEntry result of a one-output decision can be compared.");
                return;
            }
        }
        if (defaultFlow == null) {
            // Two complementary conditions without a default flow: the "else" one becomes the default.
            if (entries.size() == 2 && !anyGroup) {
                Analysis.BranchEntry a = entries.get(0);
                Analysis.BranchEntry b = entries.get(1);
                Analysis.BranchEntry complement = complement(a, b) ? b : complement(b, a) ? a : null;
                if (complement != null) {
                    derivedDefaultFrom = analysis.flows.get(complement.flowId());
                    entries.remove(complement);
                    defaultFlow = derivedDefaultFrom;
                }
            }
            if (defaultFlow == null) {
                add(e, "exclusiveGateway", Report.BLOCKER, "BRANCH_DEFAULT_REQUIRED",
                        "Orqix choices need a default path for values that match no condition, and this gateway has none.",
                        "Mark one outgoing flow as the default flow in the source model.", Need.NONE, null);
                return;
            }
        }
        entries.add(new Analysis.BranchEntry(defaultFlow.id, null, List.of(), true, null));
        Analysis.Branch branch = new Analysis.Branch(gateway, variables, entries);
        if (derivedDefaultFrom != null) {
            branch.derivedDefaultFlowId = derivedDefaultFrom.id;
        }
        analysis.branches.put(gateway.id, branch);
        List<String> inputs = new ArrayList<>();
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            String key = fieldKey(variable.getKey());
            if (analysis.decisionResults.containsKey(variable.getKey())) {
                analysis.assumptions.add("Variable '" + variable.getKey() + "' (read by gateway '" + labelOf(e) + "') is the result "
                        + "variable of business rule task '" + analysis.decisionResults.get(variable.getKey()) + "'; the choice "
                        + "reads the output of the converted decision step, not a request field.");
                continue;
            }
            inputs.add(key);
            analysis.assumptions.add("Variable '" + variable.getKey() + "' (read by gateway '" + labelOf(e) + "') becomes the request "
                    + "form field '" + key + "' of type " + variable.getValue() + ", inferred from the compared values. Check "
                    + "that the requester provides it when starting the workflow.");
        }
        if (derivedDefaultFrom != null) {
            analysis.assumptions.add("Gateway '" + labelOf(e) + "' had no default flow; the complementary condition of flow '"
                    + labelOf(derivedDefaultFrom.element) + "' became the default path.");
        }
        add(e, "exclusiveGateway", Report.REVIEW, "GATEWAY_MAPPED_TO_BRANCH",
                "Becomes a choice on " + (inputs.isEmpty() ? "a decision result" : "the request field(s) " + quoted(inputs)) + " with "
                        + (entries.size() - 1) + " condition(s) and a default path. Camunda takes the first condition that is true "
                        + "in document order; Orqix requires the conditions to exclude each other and the compiler rejects "
                        + "overlapping ones.",
                "Check the inferred field types and that exactly one condition can be true for every value.", Need.ACKNOWLEDGE, null);
    }

    private static boolean complement(Analysis.BranchEntry first, Analysis.BranchEntry second) {
        if (first.compound() || second.compound()) {
            return false;
        }
        Expressions.Comparison a = first.scalar();
        Expressions.Comparison b = second.scalar();
        if (!a.variable().equals(b.variable())) {
            return false;
        }
        boolean sameLiteral = a.literal().equals(b.literal()) || a.literal() instanceof java.math.BigDecimal x
                && b.literal() instanceof java.math.BigDecimal y && x.compareTo(y) == 0;
        if (sameLiteral) {
            return a.op() == Expressions.Op.GTE && b.op() == Expressions.Op.LT
                    || a.op() == Expressions.Op.GT && b.op() == Expressions.Op.LTE
                    || a.op() == Expressions.Op.EQ && b.op() == Expressions.Op.NE;
        }
        return a.op() == Expressions.Op.EQ && b.op() == Expressions.Op.EQ
                && Boolean.TRUE.equals(a.literal()) && Boolean.FALSE.equals(b.literal());
    }

    private void expressionBlocker(Analysis.Node gateway, String why) {
        add(gateway.element, "exclusiveGateway", Report.BLOCKER, "EXPRESSION_NOT_SUPPORTED",
                "This choice cannot be converted. " + why + " Expression(s) in the source: " + conditionTexts(gateway)
                        + ". Supported: one field compared with == != >= > < <= (ordering on "
                        + "numbers only), or 2 to 8 such comparisons joined only by 'and' or only by 'or', plus a default path.",
                "Simplify the conditions in the source model, or rebuild this choice in the studio.", Need.NONE, null);
    }

    /** The condition text of a flow exactly as written in the source (for the report). */
    private static String conditionText(Analysis.Flow flow) {
        XElement condition = flow.element.child(BPMN, "conditionExpression");
        return condition == null ? "" : condition.text();
    }

    /** All condition texts of the flows leaving a gateway, for explanations. */
    private static String conditionTexts(Analysis.Node gateway) {
        List<String> texts = new ArrayList<>();
        for (Analysis.Flow flow : gateway.out) {
            String text = conditionText(flow);
            texts.add("'" + labelOf(flow.element) + "': " + (text.isBlank() ? "default" : text));
        }
        return String.join("; ", texts);
    }

    private void userTaskItem(Analysis.UserTask task) {
        Analysis.Node node = task.node;
        XElement e = node.element;
        if ("expression".equals(task.assignmentKind)) {
            add(e, "userTask", Report.BLOCKER, "ASSIGNMENT_NOT_SUPPORTED",
                    "The assignee or candidate groups are computed by an expression, which cannot be mapped to a workspace "
                            + "group.",
                    "Use fixed candidate groups in the source model.", Need.NONE, null);
            if (task.outcomeGateway != null) {
                gatewayFollowing(task, Report.BLOCKER, "ASSIGNMENT_NOT_SUPPORTED", Need.NONE);
            }
            return;
        }
        if (task.outcomesImpossible) {
            add(e, "userTask", Report.BLOCKER, "APPROVAL_OUTCOMES_NOT_FOUND",
                    "An approval needs exactly two outcomes, approved and rejected. This task has "
                            + (task.outcomeGateway != null ? "a gateway with " + task.outcomeGateway.out.size() + " paths"
                                    : node.out.size() + " outgoing flow(s)")
                            + ", so it cannot become an approval.",
                    "Model the task with a gateway that splits into approved and rejected paths.", Need.NONE, null);
            if (task.outcomeGateway != null) {
                gatewayFollowing(task, Report.BLOCKER, "GATEWAY_NOT_SUPPORTED_IN_V0", Need.NONE);
            }
            return;
        }
        String who = switch (task.assignmentKind) {
            case "groups" -> "candidate group(s) " + quoted(task.mappingKeys);
            case "assignee" -> "assignee/candidate user(s) " + quoted(task.mappingKeys);
            default -> "no assignee or candidate group";
        };
        if (task.recognized != null) {
            Analysis.Item item = add(e, "userTask", Report.ACTION, "GROUP_MAPPING_REQUIRED",
                    "Becomes an approval step. It has " + who + " and its approved and rejected paths are read from gateway '"
                            + labelOf(task.outcomeGateway.element) + "' (condition on '" + task.recognized.variable() + "'"
                            + (task.recognized.inverted() ? ", inverted because the name means rejection" : "") + ").",
                    "Choose the workspace group whose members decide this step.", Need.TASK, node.id);
            item.target(node.canonicalId);
            gatewayFollowing(task, Report.REVIEW, "APPROVAL_ROUTE_INFERRED", Need.ACKNOWLEDGE);
            analysis.assumptions.add("Approval '" + labelOf(e) + "': the variable '" + task.recognized.variable() + "' being "
                    + (task.recognized.inverted() ? "true means rejected" : "true means approved") + ".");
        } else {
            Analysis.Item item = add(e, "userTask", Report.ACTION, "APPROVAL_ROUTE_REQUIRED",
                    "Can become an approval step (" + who + "), but the approved and rejected paths could not be read from "
                            + "the conditions.",
                    "Choose which flow means approved and which means rejected, and the workspace group that decides.",
                    Need.TASK, node.id);
            item.target(node.canonicalId);
            gatewayFollowing(task, Report.ACTION, "APPROVAL_ROUTE_REQUIRED", Need.OUTCOME_GATEWAY);
        }
    }

    /** The gateway that follows a user task is reported together with the task. */
    private void gatewayFollowing(Analysis.UserTask task, String severity, String code, Need need) {
        Analysis.Node gateway = task.outcomeGateway;
        if (gateway == null) {
            return;
        }
        String explanation = switch (code) {
            case "APPROVAL_ROUTE_INFERRED" -> "Replaced by the approved and rejected routes of the approval step '"
                    + labelOf(task.node.element) + "'. The routes were inferred from the gateway conditions on '"
                    + task.recognized.variable() + "' (" + conditionTexts(task.outcomeGateway) + "): "
                    + (task.recognized.inverted() ? "the name means rejection, so a true value is read as rejected"
                    : "a true value is read as approved") + ". Other values of that variable are not modelled.";
            case "APPROVAL_ROUTE_REQUIRED" -> "Follows the approval step '" + labelOf(task.node.element) + "'. If you choose its "
                    + "approved and rejected flows, this gateway is replaced by those routes.";
            default -> "Follows the user task '" + labelOf(task.node.element) + "', which cannot become an approval, so this "
                    + "gateway cannot be converted.";
        };
        Analysis.Item item = add(gateway.element, "exclusiveGateway", severity, code, explanation,
                Report.ACTION.equals(severity) ? "Decide on the approval step '" + labelOf(task.node.element) + "'."
                        : Report.REVIEW.equals(severity) ? "Check that the approved and rejected routes match the source gateway." : null,
                need, task.node.id);
        if (!Report.BLOCKER.equals(severity)) {
            item.target(task.node.canonicalId);
        }
    }

    private void flowItem(Analysis.Flow flow) {
        XElement e = flow.element;
        String from = labelOf(flow.source.element);
        String to = labelOf(flow.target.element);
        if (flow.source.type.equals("exclusiveGateway") || !flow.hasConditionElement) {
            boolean absorbed = analysis.outcomeGatewayOf.containsValue(flow.target.id) && flow.target.in.size() == 1
                    && analysis.userTasks.containsKey(flow.source.id);
            if (absorbed) {
                add(e, "sequenceFlow", Report.SUPPORTED, "FLOW_ABSORBED",
                        "Replaced by the approved and rejected routes that start at the approval step '" + from + "'.", null,
                        Need.NONE, null);
            } else if (analysis.mergeGateways.contains(flow.source.id)) {
                add(e, "sequenceFlow", Report.SUPPORTED, "FLOW_ABSORBED",
                        "The merge gateway '" + from + "' is removed; its incoming flows continue directly to '" + to + "'.",
                        null, Need.NONE, null);
            } else if (flow.condition instanceof Expressions.Unsupported unsupported
                    && flow.source.type.equals("exclusiveGateway") && !analysis.outcomeGatewayOf.containsValue(flow.source.id)) {
                add(e, "sequenceFlow", Report.BLOCKER, "EXPRESSION_NOT_SUPPORTED",
                        "The condition '" + conditionText(flow) + "' on this flow is not supported: " + unsupported.reason(),
                        "Simplify the condition in the source model, or rebuild this choice in the studio.", Need.NONE, null);
            } else if (flow.condition != null && analysis.branches.containsKey(flow.source.id)
                    && !flow.id.equals(analysis.branches.get(flow.source.id).derivedDefaultFlowId)) {
                Analysis.BranchEntry entry = analysis.branches.get(flow.source.id).entries.stream()
                        .filter(b -> b.flowId().equals(flow.id)).findFirst().orElse(null);
                String shown = flow.condition instanceof Expressions.Logical l ? l.describe()
                        : ((Expressions.Comparison) flow.condition).describe();
                add(e, "sequenceFlow", Report.REVIEW, "CONDITION_CONVERTED",
                        "Source condition '" + conditionText(flow) + "' became the condition " + shown + " on the request or "
                                + "step field of the choice" + (entry != null && entry.compound() ? " (" + ("all".equals(entry.group())
                                ? "all of the terms must hold" : "any of the terms may hold") + ")" : "")
                                + ", so the path to '" + to + "' is taken only when it holds. Numbers compare as decimals "
                                + "(5000 equals 5000.00); text compares exactly.",
                        "Check boundary values (below, at and above any limit) and that the requester supplies the field.",
                        Need.ACKNOWLEDGE, null);
            } else if (analysis.branches.containsKey(flow.source.id) && analysis.branches.get(flow.source.id).derivedDefaultFlowId != null
                    && analysis.branches.get(flow.source.id).derivedDefaultFlowId.equals(flow.id)) {
                add(e, "sequenceFlow", Report.REVIEW, "DEFAULT_PATH_DERIVED",
                        "This flow had no default marker; it became the default path because its condition complements the other "
                                + "one.",
                        "Check that no other value should reach this path.", Need.ACKNOWLEDGE, null);
            } else {
                add(e, "sequenceFlow", Report.SUPPORTED, "FLOW_MAPPED",
                        "Becomes a transition from '" + from + "' to '" + to + "'.", null, Need.NONE, null);
            }
        } else if (analysis.userTasks.containsKey(flow.source.id)) {
            add(e, "sequenceFlow", Report.INFO, "CONDITION_DROPPED",
                    "The condition on this flow leaving the user task '" + from + "' is not imported; the approved and "
                            + "rejected paths are chosen in the mapping step.",
                    "Acknowledge.", Need.ACKNOWLEDGE, null);
        } else {
            add(e, "sequenceFlow", Report.BLOCKER, "EXPRESSION_NOT_SUPPORTED",
                    "A condition on a flow that does not leave an exclusive gateway cannot be converted.",
                    "Use an exclusive gateway to choose between paths.", Need.NONE, null);
        }
        scanFlowChildren(e);
    }

    // ------------------------------------------------------------------------------------------------ canonical ids

    private void assignCanonicalIds() {
        Set<String> used = new HashSet<>();
        for (Analysis.Node node : analysis.nodes.values()) {
            boolean becomesNode = switch (node.type) {
                case "startEvent", "endEvent" -> eventDefinition(node.element) == null;
                case "userTask", "serviceTask", "parallelGateway", "intermediateCatchEvent", "callActivity" -> true;
                case "exclusiveGateway" -> true;
                case "businessRuleTask" -> analysis.decisions.containsKey(node.id);
                default -> false;
            };
            if (!becomesNode) {
                continue;
            }
            String id = Identifiers.unique(Identifiers.sanitize(node.id, false), used);
            node.canonicalId = id;
            if (!id.equals(node.id)) {
                analysis.renamedIds.put(node.id, id);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ nested constructs

    private void scanNodeChildren(Analysis.Node node) {
        XElement e = node.element;
        scanAttributes(e, node.type);
        for (XElement child : e.children()) {
            if (!BPMN.equals(child.ns())) {
                if (C7.equals(child.ns()) || ZB.equals(child.ns())) {
                    unknown(child);
                } else {
                    add(child, child.name(), Report.INFO, "EXTENSION_NOT_IMPORTED",
                            "The element " + child.label() + " is not imported.", "Acknowledge.", Need.ACKNOWLEDGE, null);
                }
                continue;
            }
            switch (child.name()) {
                case "incoming", "outgoing", "script" -> {
                    // flow references are derived from sequence flows; a script belongs to a blocked script task
                }
                case "documentation" -> add(child, "documentation", Report.INFO, "DOCUMENTATION_NOT_IMPORTED",
                        "Documentation text is not imported.", "Acknowledge.", Need.ACKNOWLEDGE, null);
                case "extensionElements" -> scanExtensions(e, child);
                case "multiInstanceLoopCharacteristics" -> multiInstance(node, child);
                case "standardLoopCharacteristics" -> {
                    add(child, child.name(),
                            Report.BLOCKER, "MULTI_INSTANCE_NOT_SUPPORTED",
                            "Standard loops (repeat while a condition holds) are not supported. Bounded repetition in Orqix stops on "
                                    + "a child outcome, not on a source expression.",
                            "Model one pass and start the workflow once per item, or rebuild it with a decision.", Need.NONE, null);
                    loopParts(child);
                }
                case "ioSpecification", "dataInputAssociation", "dataOutputAssociation", "property", "potentialOwner",
                        "humanPerformer", "performer", "resourceRole", "rendering" -> add(child, child.name(), Report.INFO,
                        "ARTIFACT_NOT_IMPORTED", "The " + child.name() + " data or role definition is not imported.",
                        "Acknowledge.", Need.ACKNOWLEDGE, null);
                default -> {
                    if (!child.name().endsWith("EventDefinition") && !"conditionExpression".equals(child.name())) {
                        unknown(child);
                    }
                }
            }
        }
    }

    private void scanFlowChildren(XElement flow) {
        scanAttributes(flow, "sequenceFlow");
        for (XElement child : flow.children()) {
            if (child.is(BPMN, "conditionExpression")) {
                continue;
            }
            if (child.is(BPMN, "documentation")) {
                add(child, "documentation", Report.INFO, "DOCUMENTATION_NOT_IMPORTED", "Documentation text is not imported.",
                        "Acknowledge.", Need.ACKNOWLEDGE, null);
            } else if (child.is(BPMN, "extensionElements")) {
                scanExtensions(flow, child);
            } else {
                unknown(child);
            }
        }
    }

    private void scanExtensions(XElement owner, XElement extensionElements) {
        for (XElement c : extensionElements.children()) {
            if (C7.equals(c.ns())) {
                switch (c.name()) {
                    case "executionListener", "taskListener" -> listener(c);
                    case "inputOutput" -> ioMapping(c);
                    case "formData" -> form(c);
                    case "properties", "failedJobRetryTimeCycle" -> setting(c);
                    case "connector", "field" -> {
                        if (!"serviceTask".equals(owner.name())) {
                            unknown(c);
                        }
                    }
                    case "in", "out" -> {
                        if (!"callActivity".equals(owner.name())) unknown(c);
                        else ioMapping(c);
                    }
                    default -> unknown(c);
                }
            } else if (ZB.equals(c.ns())) {
                switch (c.name()) {
                    case "assignmentDefinition" -> {
                        if (!"userTask".equals(owner.name())) {
                            unknown(c);
                        }
                    }
                    case "taskDefinition" -> {
                        if ("userTask".equals(owner.name())) {
                            add(c, "taskDefinition", Report.INFO, "JOB_WORKER_USER_TASK",
                                    "This user task is handled by a job worker (job type '" + Zeebe.mask(c.attr("type") == null ? "" : c.attr("type"))
                                            + "'), the older Camunda 8 way. Orqix tasks are native, so the job type is not imported.",
                                    "Acknowledge.", Need.ACKNOWLEDGE, null);
                        } else if (!"serviceTask".equals(owner.name()) && !"businessRuleTask".equals(owner.name())
                                && !"scriptTask".equals(owner.name()) && !"sendTask".equals(owner.name())) {
                            unknown(c);
                        }
                    }
                    case "taskSchedule" -> {
                        if ("userTask".equals(owner.name())) {
                            schedule(owner, c);
                        } else {
                            unknown(c);
                        }
                    }
                    case "userTaskForm" -> embeddedFormSource(owner, c);
                    case "userTask", "calledDecision", "calledElement", "script", "subscription", "loopCharacteristics" -> {
                        // marker or detail of an element that is reported on its own
                    }
                    case "taskListeners", "executionListeners" -> listener(c);
                    case "ioMapping" -> {
                        if (connectorJobType(owner) == null || !"serviceTask".equals(owner.name())) {
                            zeebeIoMapping(owner, c);
                        }
                    }
                    case "formDefinition" -> {
                        if ("startEvent".equals(owner.name()) || "userTask".equals(owner.name())) {
                            formLink(owner, c);
                        } else {
                            form(c);
                        }
                    }
                    case "taskHeaders" -> {
                        if (connectorJobType(owner) == null || !"serviceTask".equals(owner.name())) {
                            setting(c);
                        }
                    }
                    case "properties" -> zeebeProperties(owner, c);
                    case "versionTag", "preConditions", "priorityDefinition", "linkedResources", "conditionalFilter" -> setting(c);
                    default -> unknown(c);
                }
            } else if (Namespaces.MODELER.equals(c.ns())) {
                // modeler bookkeeping (templates, execution platform) carries no process semantics
                continue;
            } else {
                add(c, c.name(), Report.INFO, "EXTENSION_NOT_IMPORTED",
                        "The extension element " + c.label() + " is not imported.", "Acknowledge.", Need.ACKNOWLEDGE, null);
            }
        }
    }

    private void listener(XElement c) {
        add(c, c.name(), Report.INFO, "LISTENER_DROPPED",
                "Listeners run code or expressions on engine events. They are not imported and Orqix never runs imported "
                        + "code.",
                "Acknowledge, and rebuild any side effect as a connection step.", Need.ACKNOWLEDGE, null);
    }

    private void ioMapping(XElement c) {
        add(c, c.name(), Report.INFO, "IO_MAPPING_NOT_IMPORTED",
                "Input and output mappings are not translated. Request fields of service calls are mapped in the mapping "
                        + "step instead.",
                "Acknowledge.", Need.ACKNOWLEDGE, null);
    }

    /** Camunda 8 input/output mapping: not translated, but the variables it sets are listed (values are never echoed). */
    private void zeebeIoMapping(XElement owner, XElement c) {
        List<String> in = new ArrayList<>();
        List<String> out = new ArrayList<>();
        Zeebe.mappings(c, "input").forEach((target, source) -> in.add(Zeebe.variable(source) != null && !target.equals(Zeebe.variable(source))
                ? target + " (from " + Zeebe.variable(source) + ")" : target));
        Zeebe.mappings(c, "output").forEach((target, source) -> out.add(target));
        add(c, c.name(), Report.INFO, "IO_MAPPING_NOT_IMPORTED",
                "Input and output mappings are not translated. Variables set before the step: " + (in.isEmpty() ? "none" : String.join(", ", in))
                        + ". Variables set from its result: " + (out.isEmpty() ? "none" : String.join(", ", out)) + "."
                        + ("serviceTask".equals(owner.name()) || "callActivity".equals(owner.name())
                        ? " The names are offered as suggestions in the mapping step where an operation or child workflow is mapped." : ""),
                "Acknowledge.", Need.ACKNOWLEDGE, null);
    }

    /** {@code zeebe:properties}: engine settings, except inbound connector configuration which is a blocker. */
    private void zeebeProperties(XElement owner, XElement c) {
        for (XElement property : c.children(ZB, "property")) {
            String name = property.attr("name");
            if (name != null && name.startsWith("inbound.")) {
                add(owner, owner.name(), Report.BLOCKER, "CONNECTOR_NOT_SUPPORTED",
                        "This " + owner.name() + " is an inbound Camunda 8 connector (" + name + "), for example a webhook that starts or "
                                + "wakes the process. Inbound connectors are not supported and their configuration is not imported.",
                        "Start the workflow through its API trigger or from the requester's form, or rebuild the hand-off as a call.",
                        Need.NONE, null);
                return;
            }
        }
        setting(c);
    }

    private void form(XElement c) {
        add(c, c.name(), Report.INFO, "FORM_NOT_IMPORTED",
                "Forms are not imported; the workflow gets a request form with the fields its conditions and service calls "
                        + "need.",
                "Acknowledge, and review the request form in the studio.", Need.ACKNOWLEDGE, null);
    }

    private void setting(XElement c) {
        add(c, c.name(), Report.INFO, "SETTING_NOT_IMPORTED",
                "Engine settings and properties (" + c.label() + ") are not imported.", "Acknowledge.", Need.ACKNOWLEDGE, null);
    }

    private void scanAttributes(XElement e, String kind) {
        List<String> forms = new ArrayList<>();
        List<String> settings = new ArrayList<>();
        for (String key : e.attributes().keySet()) {
            String ns = null;
            String local = key;
            if (key.startsWith("{")) {
                ns = key.substring(1, key.indexOf('}'));
                local = key.substring(key.indexOf('}') + 1);
            }
            if (!C7.equals(ns) && !ZB.equals(ns)) {
                continue;
            }
            if (FORM_ATTRIBUTES.contains(local)) {
                forms.add(Namespaces.prefixed(ns, local));
            } else if (!CONSUMED_ATTRIBUTES.contains(local)) {
                settings.add(Namespaces.prefixed(ns, local));
            }
        }
        if (!forms.isEmpty()) {
            add(e, kind, Report.INFO, "FORM_NOT_IMPORTED",
                    "Form references (" + String.join(", ", forms) + ") are not imported.",
                    "Acknowledge, and review the request form in the studio.", Need.ACKNOWLEDGE, null);
        }
        if (!settings.isEmpty()) {
            add(e, kind, Report.INFO, "SETTING_NOT_IMPORTED",
                    "Engine settings (" + String.join(", ", settings) + ") are not imported.", "Acknowledge.",
                    Need.ACKNOWLEDGE, null);
        }
    }

    private void unknown(XElement e) {
        add(e, e.name(), Report.BLOCKER, "UNKNOWN_ELEMENT",
                "The element " + e.label() + " is not known to the importer, so it cannot be converted safely.",
                "Remove it from the source model or rebuild what it does in the studio.", Need.NONE, null);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private Analysis.Item add(XElement el, String kind, String severity, String code, String explanation, String suggestion,
            Need need, String subject) {
        XElement owner = el;
        while (owner != null && owner.attr("id") == null) {
            owner = owner.parent();
        }
        String id = owner == null ? (analysis == null ? "definitions" : analysis.processId) : owner.attr("id");
        String name = owner == null ? null : blankToNull(owner.attr("name"));
        String resolution = Report.SUPPORTED.equals(severity) ? Report.NOT_NEEDED : Report.OPEN;
        Report.Issue issue = new Report.Issue(id, name, kind, severity, code, explanation, suggestion, null, resolution,
                pathOf(el));
        Analysis.Item item = new Analysis.Item(issue, need, subject, el.ordinal(), sequence++);
        items.add(item);
        return item;
    }

    private static String pathOf(XElement el) {
        Deque<String> parts = new java.util.ArrayDeque<>();
        for (XElement e = el; e != null; e = e.parent()) {
            String id = e.attr("id");
            parts.push(Namespaces.prefixed(e.ns(), e.name()) + (id == null ? "" : "[" + id + "]"));
        }
        return String.join("/", parts);
    }

    private String idOf(XElement e) {
        String id = e.attr("id");
        return id == null || id.isBlank() ? e.name() + "#" + e.ordinal() : id;
    }

    private static String labelOf(XElement e) {
        String name = blankToNull(e.attr("name"));
        return name != null ? name : e.attr("id") != null ? e.attr("id") : e.name();
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String quoted(List<String> values) {
        return values.stream().map(v -> "'" + v + "'").collect(java.util.stream.Collectors.joining(", "));
    }

    private static int countDescendants(XElement e) {
        int count = 0;
        for (XElement child : e.children()) {
            count += 1 + countDescendants(child);
        }
        return count;
    }

    /** Camunda variable name to canonical field key ({@code ^[a-z][A-Za-z0-9]{0,63}$}), or null when impossible. */
    public static String fieldKey(String variable) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : variable.toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128) {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            } else if (c == '_' || c == '-' || c == ' ') {
                upper = out.length() > 0;
            } else {
                return null;
            }
        }
        if (out.length() == 0 || !Character.isLetter(out.charAt(0))) {
            return null;
        }
        out.setCharAt(0, Character.toLowerCase(out.charAt(0)));
        return out.length() > 64 ? null : out.toString();
    }

}
