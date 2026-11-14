package com.orqix.runtime.explain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.orqix.identity.Permission;
import com.orqix.runtime.api.ExplainDtos;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.runtime.api.WaitDtos;

import tools.jackson.databind.JsonNode;

/**
 * Deterministic fact builder of "Explain this request" (CG-A07). From the request as the caller sees it (run detail with
 * current steps, attention, tasks and effects), the tail of its timeline, its open waits, the connection state of the
 * bindings it uses and the compiled model of its release, it produces a bounded fact set and the actions the caller may
 * take now.
 *
 * <p>Redaction is by construction: every fact text is written here from codes, statuses, times, step labels, group keys
 * and connection keys. Request values, subjects of waits, correlation references, worker or remote error details,
 * people's names, attention messages and decision values are never read into a fact. The only request-data element ever
 * named is a field name (the correctable fields of the correct-input action), and only when the caller may read the
 * request data. Pure Java (no Spring), so the rules can be unit-tested.
 */
public final class RunFacts {

    /** At most this many facts; the timeline contributes at most {@link #MAX_EVENT_FACTS}. */
    public static final int MAX_FACTS = 40;
    static final int MAX_EVENT_FACTS = 12;
    static final Set<String> CREDENTIAL_CODES = Set.of("CREDENTIAL_UNAVAILABLE", "TOKEN_REFRESH_FAILED",
            "TOKEN_REFRESH_FAILING", "HTTP_401", "HTTP_403");
    static final Set<String> BINDING_CODES = Set.of("CONNECTION_NOT_BOUND", "CONNECTION_UNKNOWN", "EGRESS_NOT_ALLOWED",
            "EGRESS_DENIED");
    /** Timeline entries worth a fact; their data is reduced to {@link #EVENT_KEYS}. */
    static final Set<String> EVENT_TYPES = Set.of("EFFECT_FAILED", "EFFECT_UNKNOWN", "EFFECT_DEFERRED",
            "RUN_NEEDS_ATTENTION", "OPERATOR_RETRY", "RUN_SUSPENDED", "RUN_RESUMED", "EXTERNAL_JOB_FAILED",
            "EXTERNAL_JOB_RETRY_SCHEDULED", "WAIT_STARTED", "MESSAGE_WAIT_STARTED", "TASK_CREATED", "CHILD_STARTED",
            "CHILD_NEEDS_ATTENTION", "EFFECT_RECONCILED", "INPUT_CORRECTED", "RUN_REPAIRED", "WAIT_DEADLINE_EXTENDED",
            "POLL_CHECK_FAILED", "TASK_ESCALATED");
    static final List<String> EVENT_KEYS = List.of("errorCode", "httpStatus", "attempt", "classification", "reason",
            "connectionKey", "until", "nextRetryAt", "topic");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    /** Health of one binding the request uses. */
    public record ConnectionState(String key, String name, boolean bound, boolean enabled, String authType,
            String circuitState, Instant circuitOpenUntil, String circuitReason, int consecutiveFailures,
            int tokenRefreshFailuresLastHour, Integer lastErrorHttpStatus, String lastErrorCode, Instant lastErrorAt) {
    }

    /** One timeline entry (seq, type, step, time and its data, reduced before use). */
    public record Event(long seq, String type, String nodeId, Instant at, JsonNode data) {
    }

    /**
     * Everything the builder reads.
     *
     * @param run         run detail as built for the caller (effects present because the caller has run.operate)
     * @param events      the tail of the timeline, oldest first
     * @param definition  canonical definition of the run's release (the compiled model)
     * @param permissions the caller's permissions in the workspace
     * @param dataVisible the caller may read the request data
     */
    public record Input(RuntimeDtos.RunDetail run, List<Event> events, WaitDtos.RunWaitList waits, JsonNode definition,
            Map<String, ConnectionState> connections, Set<Permission> permissions, boolean dataVisible, Instant now) {
    }

    /**
     * @param facts   bounded, ordered (F1...)
     * @param actions permitted actions, most specific first
     * @param refs    named fact ids the rules use: {@code attention}, {@code run}, {@code effect:<id>},
     *                {@code failingSince:<nodeId>}, {@code connection:<key>}, {@code circuit:<key>},
     *                {@code token:<key>}, {@code wait:<nodeId>}, {@code check:<waitId>}, {@code task:<taskId>},
     *                {@code model:<nodeId>}, {@code suspension}, {@code child:<id>}, {@code deferred:<key>}
     * @param problemConnections connection keys whose binding or credential is the likely cause
     */
    public record Result(List<ExplainDtos.Fact> facts, List<ExplainDtos.PermittedAction> actions,
            Map<String, String> refs, Set<String> problemConnections) {

        public String ref(String name) {
            return refs.get(name);
        }

        public boolean hasAction(String id) {
            return actions.stream().anyMatch(a -> a.id().equals(id));
        }
    }

    private final Input in;
    private final List<ExplainDtos.Fact> facts = new ArrayList<>();
    private final Map<String, String> refs = new LinkedHashMap<>();
    private final Set<String> problemConnections = new LinkedHashSet<>();

    private RunFacts(Input in) {
        this.in = in;
    }

    public static Result build(Input in) {
        RunFacts b = new RunFacts(in);
        b.collect();
        List<ExplainDtos.PermittedAction> actions = b.actions();
        return new Result(List.copyOf(b.facts), actions, Map.copyOf(b.refs), Set.copyOf(b.problemConnections));
    }

    // ================================================================== facts

    private void collect() {
        RuntimeDtos.RunDetail run = in.run();
        add("run", "run", "The request is " + lifecycleText(run.lifecycle()) + " (version " + run.version()
                + "), last changed " + time(run.updatedAt()) + ".", null, run.updatedAt(), null);
        if (run.suspension() != null) {
            add("suspension", "suspension", "An operator suspended the request on " + time(run.suspension().since())
                    + "; approvals, timers and messages are held until it is resumed.", null,
                    run.suspension().since(), null);
        }
        RuntimeDtos.Attention attention = run.attention();
        if (attention != null) {
            add("attention", "attention", "Step " + label(attention.nodeId()) + " stopped with " + code(attention.reason())
                    + " on " + time(attention.since()) + "; the request needs an operator.", attention.nodeId(),
                    attention.since(), null);
        }
        for (RuntimeDtos.Effect e : nz(run.effects())) {
            if ("succeeded".equals(e.state()) || "reconciled".equals(e.state())) {
                continue;
            }
            StringBuilder text = new StringBuilder("Call ").append(e.operation()).append(" on connection ")
                    .append(e.connectionKey()).append(" from step ").append(label(e.nodeId())).append(": ")
                    .append(effectState(e.state())).append(" after ").append(e.attempts()).append(" attempt(s)");
            if (e.lastHttpStatus() != null) {
                text.append("; last answer HTTP ").append(e.lastHttpStatus());
            }
            if (e.lastErrorCode() != null) {
                text.append(" (").append(code(e.lastErrorCode())).append(")");
            }
            add("effect:" + e.id(), "effect", text + ".", e.nodeId(), e.updatedAt(), null);
            if (isCredentialProblem(e.lastErrorCode(), e.lastHttpStatus())
                    || BINDING_CODES.contains(e.lastErrorCode())) {
                problemConnections.add(e.connectionKey());
            }
        }
        if (attention != null && "CONNECTION_UNAVAILABLE".equals(attention.reason())) {
            connectionOf(attention.nodeId()).ifPresent(problemConnections::add);
        }
        failingSince();
        for (RuntimeDtos.CurrentStep step : nz(run.currentSteps())) {
            if (step.nodeId() != null && attention != null && step.nodeId().equals(attention.nodeId())) {
                continue;
            }
            add("step:" + step.nodeId(), "step", "Current step " + label(step.nodeId(), step.label()) + " (" + step.kind()
                    + ") since " + time(step.since()) + (step.dueAt() == null ? "" : ", due " + time(step.dueAt())
                    + (step.dueAt().isBefore(in.now()) ? " (overdue)" : "")) + ".", step.nodeId(), step.since(), null);
        }
        waits();
        tasks();
        children();
        connections();
        model();
        timeline();
    }

    /** First failure of the trailing streak of failures of the stuck step ("returned 403 since 09:12"). */
    private void failingSince() {
        RuntimeDtos.Attention attention = in.run().attention();
        if (attention == null || attention.nodeId() == null) {
            return;
        }
        Event first = null;
        int count = 0;
        String code = null;
        for (int i = in.events().size() - 1; i >= 0; i--) {
            Event e = in.events().get(i);
            if (!attention.nodeId().equals(e.nodeId())) {
                continue;
            }
            if ("EFFECT_FAILED".equals(e.type()) || "EFFECT_UNKNOWN".equals(e.type())
                    || "EXTERNAL_JOB_FAILED".equals(e.type())) {
                String c = e.data() == null ? null : e.data().path("errorCode").asString(null);
                if (code == null) {
                    code = c;
                } else if (c != null && !c.equals(code)) {
                    break;
                }
                first = e;
                count++;
            } else if ("OPERATOR_RETRY".equals(e.type()) || "EFFECT_SUCCEEDED".equals(e.type())) {
                break;
            }
        }
        if (first != null) {
            add("failingSince:" + attention.nodeId(), "event", "Step " + label(attention.nodeId()) + " has failed "
                    + count + " time(s) in a row" + (code == null ? "" : " with " + code(code)) + " since "
                    + time(first.at()) + ".", attention.nodeId(), first.at(), first.seq());
        }
    }

    private void waits() {
        if (in.waits() == null) {
            return;
        }
        for (WaitDtos.RunWait w : in.waits().items()) {
            String what = switch (w.kind() == null ? "" : w.kind()) {
                case "approval" -> "a person to act on";
                case "timer" -> "a timer at";
                case "message" -> "a message at";
                case "callback" -> "a callback from another system at";
                case "poll" -> "a repeated check at";
                case "externalJob" -> "an external worker at";
                case "childRequest" -> "another request started by";
                case "signal" -> "a signal at";
                default -> "something at";
            };
            StringBuilder text = new StringBuilder("Waiting for ").append(what).append(" step ")
                    .append(label(w.nodeId(), w.label())).append(" since ").append(time(w.since()));
            if (w.dueAt() != null) {
                text.append("; due ").append(time(w.dueAt()));
                if (w.dueAt().isBefore(in.now())) {
                    text.append(" (passed)");
                }
            }
            if (w.deadlineAt() != null) {
                text.append("; deadline ").append(time(w.deadlineAt()));
            }
            if (w.nextCheckAt() != null) {
                text.append("; next check ").append(time(w.nextCheckAt()));
            }
            if (w.connectionKey() != null) {
                text.append("; connection ").append(w.connectionKey());
            }
            if (w.attempts() > 0) {
                text.append("; ").append(w.attempts()).append(" attempt(s)");
            }
            add("wait:" + w.nodeId(), "wait", text + ".", w.nodeId(), w.since(), null);
            WaitDtos.PollCheck c = w.check();
            if (c != null) {
                StringBuilder check = new StringBuilder("Check at step ").append(label(w.nodeId(), w.label()))
                        .append(" is ").append(c.state()).append(": ").append(c.checksDone()).append(" of ")
                        .append(c.maxChecks()).append(" checks done");
                if (c.consecutiveErrors() > 0) {
                    check.append(", ").append(c.consecutiveErrors()).append(" error(s) in a row");
                }
                if (c.lastHttpStatus() != null) {
                    check.append(", last answer HTTP ").append(c.lastHttpStatus());
                }
                if (c.lastOutcome() != null) {
                    check.append(", last outcome ").append(code(c.lastOutcome()));
                }
                add("check:" + c.waitId(), "check", check + ".", w.nodeId(), c.lastCheckedAt(), null);
                if (isCredentialProblem(null, c.lastHttpStatus()) && w.connectionKey() != null) {
                    problemConnections.add(w.connectionKey());
                }
            }
        }
    }

    private void tasks() {
        for (RuntimeDtos.TaskSummary t : nz(in.run().tasks())) {
            if (!"open".equals(t.status())) {
                continue;
            }
            String group = t.candidateGroup() == null ? "no group" : "group " + t.candidateGroup().key();
            boolean overdue = t.dueAt() != null && t.dueAt().isBefore(in.now());
            add("task:" + t.id(), "task", "Task " + label(t.nodeId(), t.label()) + " for " + group + " is "
                    + (t.assignee() == null ? "unassigned" : "assigned to a person") + " since " + time(t.createdAt())
                    + (t.dueAt() == null ? "" : "; due " + time(t.dueAt()) + (overdue ? " (overdue)" : "")) + ".",
                    t.nodeId(), t.createdAt(), null);
        }
    }

    private void children() {
        for (RuntimeDtos.ChildRun c : nz(in.run().children())) {
            if (!"needsAttention".equals(c.lifecycle()) && !"waiting".equals(c.lifecycle())
                    && !"running".equals(c.lifecycle())) {
                continue;
            }
            add("child:" + c.id(), "child", "Request " + c.reference() + " started by step "
                    + label(c.nodeId(), c.nodeLabel()) + " is " + lifecycleText(c.lifecycle())
                    + (c.attention() == null ? "" : " (" + code(c.attention().reason()) + ")") + ".", c.nodeId(),
                    c.startedAt(), null);
        }
    }

    private void connections() {
        for (ConnectionState c : in.connections().values()) {
            String name = c.name() == null ? c.key() : c.key() + " (\"" + c.name() + "\")";
            String binding = !c.bound() ? "has no binding in this workspace"
                    : !c.enabled() ? "has a binding that is switched off"
                    : "is bound and enabled (authentication " + (c.authType() == null ? "none" : c.authType()) + ")";
            add("connection:" + c.key(), "binding", "Connection " + name + " " + binding + ".", null, null, null);
            if (!c.bound() || !c.enabled()) {
                problemConnections.add(c.key());
            }
            if ("open".equals(c.circuitState())) {
                add("circuit:" + c.key(), "circuit", "Calls to " + c.key() + " are paused (circuit open) until "
                        + time(c.circuitOpenUntil()) + " after " + c.consecutiveFailures() + " failures in a row"
                        + (c.circuitReason() == null ? "" : " (" + code(c.circuitReason()) + ")")
                        + "; waiting calls do not use attempts and resume on their own.", null, c.circuitOpenUntil(),
                        null);
            } else if ("halfOpen".equals(c.circuitState())) {
                add("circuit:" + c.key(), "circuit", "Calls to " + c.key() + " were paused; one trial call now decides"
                        + " whether they resume.", null, null, null);
            }
            if (c.tokenRefreshFailuresLastHour() > 0) {
                add("token:" + c.key(), "connection", "The credential of " + c.key() + " could not be obtained "
                        + c.tokenRefreshFailuresLastHour() + " time(s) in the last hour.", null, null, null);
                problemConnections.add(c.key());
            }
            if (c.lastErrorCode() != null && c.lastErrorAt() != null) {
                add("lastError:" + c.key(), "connection", "Last failed call to " + c.key() + " in the last day: "
                        + code(c.lastErrorCode()) + (c.lastErrorHttpStatus() == null ? "" : " (HTTP "
                        + c.lastErrorHttpStatus() + ")") + " on " + time(c.lastErrorAt()) + ".", null, c.lastErrorAt(),
                        null);
                if (isCredentialProblem(c.lastErrorCode(), c.lastErrorHttpStatus())) {
                    problemConnections.add(c.key());
                }
            }
        }
    }

    /** What the compiled model says about the stuck or current steps: type, connection, timeouts and routes. */
    private void model() {
        Set<String> nodes = new LinkedHashSet<>();
        if (in.run().attention() != null && in.run().attention().nodeId() != null) {
            nodes.add(in.run().attention().nodeId());
        }
        nz(in.run().currentSteps()).forEach(s -> nodes.add(s.nodeId()));
        int added = 0;
        for (String nodeId : nodes) {
            JsonNode node = node(nodeId);
            if (node == null || added >= 4) {
                continue;
            }
            added++;
            JsonNode config = node.path("config");
            StringBuilder text = new StringBuilder("In the published workflow, step ").append(label(nodeId))
                    .append(" is a ").append(node.path("type").asString("step")).append(" step");
            if (config.hasNonNull("connectionRef")) {
                text.append(" calling ").append(config.path("operation").asString("?")).append(" on ")
                        .append(config.path("connectionRef").asString());
            }
            if (config.hasNonNull("candidateGroupRef")) {
                text.append(" for group ").append(config.path("candidateGroupRef").asString());
            }
            if (config.has("timeoutSeconds")) {
                text.append(", timeout ").append(config.path("timeoutSeconds").asInt()).append(" s");
            }
            if (config.has("dueAfterHours")) {
                text.append(", due after ").append(config.path("dueAfterHours").asInt()).append(" h");
            }
            if (config.hasNonNull("duration")) {
                text.append(", waits ").append(config.path("duration").asString());
            }
            if (config.hasNonNull("untilField")) {
                text.append(", waits until a date field");
            }
            if (config.path("retry").has("maxAttempts")) {
                text.append(", up to ").append(config.path("retry").path("maxAttempts").asInt()).append(" attempts");
            } else if (in.definition().path("execution").has("maxAttempts")
                    && config.hasNonNull("connectionRef")) {
                text.append(", up to ").append(in.definition().path("execution").path("maxAttempts").asInt())
                        .append(" attempts");
            }
            List<String> routes = new ArrayList<>();
            for (JsonNode t : in.definition().path("transitions")) {
                if (nodeId.equals(t.path("from").asString(null))) {
                    routes.add(t.path("on").asString("next") + " -> " + label(t.path("to").asString(null)));
                }
            }
            if (!routes.isEmpty()) {
                text.append("; routes: ").append(String.join(", ", routes.subList(0, Math.min(6, routes.size()))));
            }
            add("model:" + nodeId, "model", text + ".", nodeId, null, null);
        }
    }

    private void timeline() {
        List<Event> relevant = in.events().stream().filter(e -> EVENT_TYPES.contains(e.type())).toList();
        int from = Math.max(0, relevant.size() - MAX_EVENT_FACTS);
        for (Event e : relevant.subList(from, relevant.size())) {
            StringBuilder text = new StringBuilder(time(e.at())).append(": ").append(e.type());
            if (e.nodeId() != null) {
                text.append(" at step ").append(label(e.nodeId()));
            }
            List<String> parts = new ArrayList<>();
            if (e.data() != null && e.data().isObject()) {
                for (String key : EVENT_KEYS) {
                    JsonNode v = e.data().path(key);
                    if (v.isNumber() || v.isBoolean()) {
                        parts.add(key + " " + v.asString());
                    } else if (v.isString() && v.asString().matches("^[A-Za-z0-9_.:+\\-]{1,64}$")) {
                        parts.add(key + " " + v.asString());
                    }
                }
            }
            if (!parts.isEmpty()) {
                text.append(" (").append(String.join(", ", parts)).append(")");
            }
            if (e.type().equals("EFFECT_DEFERRED") && e.data() != null) {
                String key = e.data().path("connectionKey").asString(null);
                if (key != null) {
                    refs.put("deferred:" + key, "F" + (facts.size() + 1));
                }
            }
            add("event:" + e.seq(), "event", text + ".", e.nodeId(), e.at(), e.seq());
        }
    }

    // ================================================================== actions

    private List<ExplainDtos.PermittedAction> actions() {
        RuntimeDtos.RunDetail run = in.run();
        Set<Permission> perms = in.permissions();
        List<ExplainDtos.PermittedAction> actions = new ArrayList<>();
        if (!perms.contains(Permission.RUN_OPERATE)) {
            return actions;
        }
        RuntimeDtos.AllowedRunActions allowed = run.allowedActions();
        String stuck = run.attention() == null ? null : run.attention().nodeId();
        // Someone else must fix the binding first: the caller's own action, or a request to an administrator.
        for (String key : problemConnections) {
            if (perms.contains(Permission.CONNECTION_MANAGE)) {
                actions.add(new ExplainDtos.PermittedAction("updateConnectionBinding:" + key, "putConnectionBinding",
                        "open", "Check the binding of " + key, "Open Settings, Connections and check the endpoint,"
                        + " credential reference and authorization of " + key + " in this workspace.",
                        Permission.CONNECTION_MANAGE.value(), stuck, key));
            } else {
                actions.add(new ExplainDtos.PermittedAction("askAdministrator:connection:" + key, null,
                        "askAdministrator", "Ask a connection administrator to check " + key,
                        "Someone with connection.manage must check the endpoint and credential of " + key
                                + " (for example authorize it again after a token rotation). You cannot change it.",
                        Permission.CONNECTION_MANAGE.value(), stuck, key));
            }
        }
        if (allowed != null && allowed.reconcile().allowed()) {
            for (RuntimeDtos.Effect e : nz(run.effects())) {
                if ("unknown".equals(e.state()) || "permanentFailure".equals(e.state())) {
                    actions.add(new ExplainDtos.PermittedAction("reconcileEffect:" + e.id(), "reconcileEffect",
                            "operate", "Record what happened in " + e.connectionKey(), "Check the other system and"
                            + " record with evidence whether the call of step " + label(e.nodeId()) + " took effect.",
                            Permission.RUN_OPERATE.value(), e.nodeId(), e.id().toString()));
                }
            }
        }
        if (allowed != null && allowed.retry().allowed()) {
            actions.add(new ExplainDtos.PermittedAction("retryRun", "retryRun", "operate", "Retry the failed step",
                    "Run step " + label(stuck) + " again once its cause is fixed.", Permission.RUN_OPERATE.value(),
                    stuck, null));
        }
        if ("needsAttention".equals(run.lifecycle()) && in.dataVisible() && nz(run.effects()).isEmpty()) {
            List<String> editable = new ArrayList<>();
            in.definition().path("inputSchema").path("properties").properties().forEach(p -> {
                if (p.getValue().path("incidentEditable").asBoolean(false)) {
                    editable.add(p.getKey());
                }
            });
            if (!editable.isEmpty()) {
                actions.add(new ExplainDtos.PermittedAction("correctRunInput", "correctRunInput", "operate",
                        "Correct request input", "Change the correctable fields (" + String.join(", ", editable)
                        + ") when the input caused the failure.", Permission.RUN_OPERATE.value(), stuck, null));
            }
        }
        if (run.attention() != null && "RUN_WAITING_ON_NOTHING".equals(run.attention().reason())) {
            actions.add(new ExplainDtos.PermittedAction("repairRun", "repairRun", "operate", "Check consistency",
                    "Compare the request with the workflow engine and rebuild missing steps.",
                    Permission.RUN_OPERATE.value(), stuck, null));
        }
        if (allowed != null && allowed.resume().allowed()) {
            actions.add(new ExplainDtos.PermittedAction("resumeRun", "resumeRun", "operate", "Resume the request",
                    "Let the request continue where it was suspended.", Permission.RUN_OPERATE.value(), null, null));
        }
        if (in.waits() != null) {
            for (WaitDtos.RunWait w : in.waits().items()) {
                WaitDtos.PollCheck c = w.check();
                if (c == null || !c.canOperate() || c.endedAt() != null) {
                    continue;
                }
                String target = c.waitId().toString();
                actions.add(new ExplainDtos.PermittedAction("checkWaitNow:" + target, "checkWaitNow", "operate",
                        "Check now", "Run the check of step " + label(w.nodeId(), w.label()) + " once now.",
                        Permission.RUN_OPERATE.value(), w.nodeId(), target));
                actions.add(new ExplainDtos.PermittedAction("extendWaitDeadline:" + target, "extendWaitDeadline",
                        "operate", "Extend the deadline", "Give the check of step " + label(w.nodeId(), w.label())
                        + " more time.", Permission.RUN_OPERATE.value(), w.nodeId(), target));
                actions.add(new ExplainDtos.PermittedAction("stopWaiting:" + target, "stopWaiting", "operate",
                        "Stop waiting", "End the check of step " + label(w.nodeId(), w.label()) + " on its time-out"
                        + " path.", Permission.RUN_OPERATE.value(), w.nodeId(), target));
            }
        }
        int tasks = 0;
        for (RuntimeDtos.TaskSummary t : nz(run.tasks())) {
            if ("open".equals(t.status()) && tasks++ < 3) {
                actions.add(new ExplainDtos.PermittedAction("reassignTask:" + t.id(), "reassignTask", "operate",
                        "Reassign the task", "Give task " + label(t.nodeId(), t.label()) + " to another eligible"
                        + " person.", Permission.RUN_OPERATE.value(), t.nodeId(), t.id()));
            }
        }
        if (allowed != null && allowed.cancel().allowed()) {
            actions.add(new ExplainDtos.PermittedAction("cancelRun", "cancelRun", "operate", "Cancel the request",
                    "Stop the request for good. Work already done in other systems is not undone.",
                    Permission.RUN_OPERATE.value(), null, null));
        }
        return List.copyOf(actions);
    }

    // ================================================================== helpers

    private void add(String ref, String kind, String text, String nodeId, Instant at, Long seq) {
        if (facts.size() >= MAX_FACTS || refs.containsKey(ref)) {
            return;
        }
        String id = "F" + (facts.size() + 1);
        refs.put(ref, id);
        String bounded = text.length() <= 400 ? text : text.substring(0, 397) + "...";
        facts.add(new ExplainDtos.Fact(id, kind, bounded, nodeId, at == null ? null : at.truncatedTo(ChronoUnit.SECONDS),
                seq));
    }

    static boolean isCredentialProblem(String code, Integer status) {
        return (code != null && CREDENTIAL_CODES.contains(code)) || (status != null && (status == 401 || status == 403));
    }

    private java.util.Optional<String> connectionOf(String nodeId) {
        JsonNode node = node(nodeId);
        return node == null ? java.util.Optional.empty()
                : java.util.Optional.ofNullable(node.path("config").path("connectionRef").asString(null));
    }

    private JsonNode node(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        for (JsonNode node : in.definition().path("nodes")) {
            if (nodeId.equals(node.path("id").asString(null))) {
                return node;
            }
        }
        return null;
    }

    private String label(String nodeId) {
        return label(nodeId, null);
    }

    private String label(String nodeId, String known) {
        if (nodeId == null) {
            return "(unknown)";
        }
        String label = known;
        if (label == null) {
            JsonNode node = node(nodeId);
            label = node == null ? null : node.path("label").asString(null);
        }
        return label == null ? nodeId : "\"" + label + "\"";
    }

    static String time(Instant at) {
        return at == null ? "an unknown time" : HM.format(at);
    }

    /** Codes are UPPER_SNAKE or camelCase identifiers; anything else is not echoed. */
    static String code(String code) {
        return code != null && code.matches("^[A-Za-z0-9_.\\-]{1,64}$") ? code : "an error";
    }

    static String lifecycleText(String lifecycle) {
        return switch (lifecycle == null ? "" : lifecycle) {
            case "needsAttention" -> "needs attention";
            case "running" -> "running";
            case "waiting" -> "waiting";
            case "completed" -> "completed";
            case "cancelled" -> "cancelled";
            default -> "in an unknown state";
        };
    }

    private static String effectState(String state) {
        return switch (state == null ? "" : state) {
            case "permanentFailure" -> "permanent failure";
            case "retryableFailure" -> "failing (will be retried)";
            case "unknown" -> "outcome unknown";
            case "inFlight", "pending" -> "in progress";
            default -> state;
        };
    }

    private static <T> List<T> nz(List<T> list) {
        return list == null ? List.of() : list;
    }
}
