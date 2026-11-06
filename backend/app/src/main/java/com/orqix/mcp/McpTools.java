package com.orqix.mcp;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ToolWorkflows;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.runtime.AssistantRuntime;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Orqix MCP tools (S136 section 3). Reads call the request, task and catalog services with the person as caller;
 * writes ({@code start_<workflow>}, {@code decide_task}, {@code send_message}) are prepared first: the person sees a
 * summary and confirms it (MCP elicitation, or the assistant calls {@code confirm} with the confirmation ID after the
 * person agreed). Only workspaces where the MCP server is switched on take part; any other workspace, including one
 * the person does not belong to, answers exactly like an unknown ID. Every call is audited ({@code mcp.tool-called}:
 * tool, step, outcome, ids; never arguments or results) and rate-limited per person.
 */
@Service
public class McpTools {

    public static final String CONFIRM = "confirm";
    static final int MAX_START_TOOLS = 200;
    static final int MAX_PAGE = 50;
    private static final java.util.regex.Pattern TASK_ID = java.util.regex.Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Set<String> LIFECYCLES = Set.of("running", "waiting", "needsAttention", "completed", "cancelled");

    /** Who calls: the person, and the OAuth client they use (for the audit record). */
    public record Context(Caller caller, String client) {
    }

    /**
     * A tools/call answer. {@code pending} is set when a write was prepared and waits for confirmation, so the transport
     * can ask the person through elicitation.
     */
    public record Outcome(ObjectNode result, McpConfirmations.Confirmation pending) {
    }

    /** A refused tool call, answered as a tool error so the assistant can correct itself. */
    static final class ToolError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code;
        final transient List<ApiException.FieldError> fieldErrors;

        ToolError(String code, String message) {
            this(code, message, List.of());
        }

        ToolError(String code, String message, List<ApiException.FieldError> fieldErrors) {
            super(message);
            this.code = code;
            this.fieldErrors = fieldErrors;
        }
    }

    /** A generated start tool bound to one workflow of one workspace. */
    record StartTool(String name, WorkspaceAccess.Workspace workspace, ToolWorkflows.Startable workflow,
            ObjectNode inputSchema) {
    }

    private final PolicyService policy;
    private final McpServerSwitch switches;
    private final ToolWorkflows workflows;
    private final AssistantRuntime runtime;
    private final McpConfirmations confirmations;
    private final McpRateLimiter limiter;
    private final AuditWriter audit;
    private final TransactionTemplate transactions;
    private final Json json;
    private final Clock clock;

    public McpTools(PolicyService policy, McpServerSwitch switches, ToolWorkflows workflows, AssistantRuntime runtime,
            McpConfirmations confirmations, McpRateLimiter limiter, AuditWriter audit, TransactionTemplate transactions,
            Json json, Clock clock) {
        this.policy = policy;
        this.switches = switches;
        this.workflows = workflows;
        this.runtime = runtime;
        this.confirmations = confirmations;
        this.limiter = limiter;
        this.audit = audit;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- tools/list

    /** Tool definitions for this person: the fixed tools and one start tool per exposed workflow they may start. */
    public ArrayNode list(Context ctx) {
        ArrayNode tools = json.mapper().createArrayNode();
        tools.add(tool("list_workflows", "List workflows you can start",
                "Lists the workflows you may start now in the workspaces where the Orqix MCP server is switched on, with "
                        + "the name of the start tool when the workflow is offered to assistants. At most 200 per workspace; "
                        + "truncated is true when there are more.",
                schema(workspaceProperty()), true, false));
        tools.add(tool("search_requests", "Search requests",
                "Finds requests (workflow runs). With run.read you search every request of the workspace; otherwise, or "
                        + "with mine=true, only your own requests. Results show what the Requests page shows you.",
                schema(workspaceProperty(),
                        prop("mine", "boolean", "Only your own requests."),
                        enumProp("lifecycle", "Request state.", LIFECYCLES.stream().sorted().toList()),
                        prop("workflowId", "string", "Only requests of this workflow (ID from list_workflows)."),
                        maxLen(prop("businessKey", "string", "Exact business key."), 100),
                        prop("createdFrom", "string", "Created at or after this RFC 3339 UTC time."),
                        prop("createdTo", "string", "Created before this RFC 3339 UTC time."),
                        prop("overdue", "boolean", "Only requests with an overdue step."),
                        prop("cursor", "string", "nextCursor of the previous page."),
                        limitProp()), true, false));
        tools.add(tool("get_request", "Get a request",
                "One request with its current steps, tasks and, when you may see them, its values.",
                schema(List.of("requestId"), workspaceProperty(), prop("requestId", "string", "Request ID.")), true, false));
        tools.add(tool("explain_request", "Explain where a request is",
                "A plain explanation of a request: where it is now, who it waits for, what happened recently and why it "
                        + "needs attention, from what you may see of it.",
                schema(List.of("requestId"), workspaceProperty(), prop("requestId", "string", "Request ID.")), true, false));
        tools.add(tool("list_my_tasks", "List my tasks",
                "Your tasks: assigned (claimed by you, the default), claimable (in your queues) or completed (decided by you).",
                schema(workspaceProperty(), enumProp("view", "Which tasks.", List.of("assigned", "claimable", "completed")),
                        maxLen(prop("q", "string", "Words matched across the task summary."), 200),
                        prop("cursor", "string", "nextCursor of the previous page."), limitProp()), true, false));
        tools.add(tool("get_task", "Get a task",
                "One task with the request it belongs to (as the task page shows it) and the actions you may take now.",
                schema(List.of("taskId"), workspaceProperty(), prop("taskId", "string", "Task ID.")), true, false));
        tools.add(tool("decide_task", "Approve or reject a task",
                "Prepares a decision on a task you may decide (claiming it first when it is in your queue). Returns a "
                        + "summary and a confirmationId: nothing is decided until the person confirms. Show the summary "
                        + "and call confirm only after they agree.",
                schema(List.of("taskId", "action"), workspaceProperty(), prop("taskId", "string", "Task ID."),
                        enumProp("action", "The decision.", List.of("approve", "reject")),
                        maxLen(prop("reason", "string", "Required when rejecting."), 1000),
                        maxLen(prop("comment", "string", "Optional comment."), 2000)), false, true));
        tools.add(tool("send_message", "Send a message to waiting requests",
                "Prepares a message (for example payment-received for order SO-1) for the requests waiting for it; needs "
                        + "message.send. Returns a summary and a confirmationId: nothing is sent until the person confirms.",
                schema(List.of("name", "correlationKey"), workspaceProperty(),
                        maxLen(prop("name", "string", "Message name, for example payment-received."), 64),
                        maxLen(prop("correlationKey", "string", "The value that identifies the request, for example an order number."), 200),
                        prop("payload", "object", "Optional flat object of the values the waiting step expects.")),
                false, true));
        tools.add(tool(CONFIRM, "Confirm a prepared action",
                "Carries out an action prepared by a start, decide_task or send_message tool. Call it only after the person "
                        + "saw the summary and agreed. Confirming the same ID again returns the same result.",
                schema(List.of("confirmationId"), prop("confirmationId", "string", "The confirmationId of the summary.")),
                false, true));
        for (StartTool start : startTools(ctx.caller())) {
            tools.add(tool(start.name(), "Start " + start.workflow().name(), startDescription(start),
                    start.inputSchema(), false, false));
        }
        return tools;
    }

    // ---------------------------------------------------------------- tools/call

    /** Runs one tool: rate limit, the tool itself, and the audit record whatever the outcome. */
    public Outcome call(Context ctx, String name, JsonNode arguments) {
        JsonNode args = arguments == null || arguments.isNull() ? json.object() : arguments;
        Audit record = new Audit(name);
        try {
            if (!args.isObject()) {
                throw new ToolError("MALFORMED_REQUEST", "arguments must be an object.");
            }
            OptionalLong wait = limiter.acquire(ctx.caller().subjectId());
            if (wait.isPresent()) {
                record.outcome = "rateLimited";
                record.errorCode = ProblemCode.RATE_LIMITED.name();
                return new Outcome(error(ProblemCode.RATE_LIMITED.name(), "You made too many Orqix calls in the last "
                        + "minute. Wait " + wait.getAsLong() + " seconds and try again.", List.of()), null);
            }
            Outcome outcome = dispatch(ctx, name, args, record);
            if (record.outcome == null) {
                record.outcome = outcome.pending() != null ? "confirmationRequired"
                        : outcome.result().path("isError").asBoolean(false) ? "failed" : "success";
            }
            return outcome;
        } catch (ToolError e) {
            record.fail(e.code);
            return new Outcome(error(e.code, e.getMessage(), e.fieldErrors), null);
        } catch (ApiException e) {
            record.fail(e.code().name());
            return new Outcome(error(e), null);
        } finally {
            write(ctx, record);
        }
    }

    /** The person answered an elicitation: accept runs the action, anything else declines it. */
    public ObjectNode answer(Context ctx, UUID confirmationId, boolean accepted) {
        Audit record = new Audit(CONFIRM);
        record.step = accepted ? "elicitationAccepted" : "elicitationDeclined";
        record.confirmationId = confirmationId;
        try {
            return accepted ? execute(ctx.caller(), confirmationId, record) : decline(ctx.caller(), confirmationId, record);
        } catch (ToolError e) {
            record.fail(e.code);
            return error(e.code, e.getMessage(), e.fieldErrors);
        } catch (ApiException e) {
            record.fail(e.code().name());
            return error(e);
        } finally {
            if (record.outcome == null) {
                record.outcome = "success";
            }
            write(ctx, record);
        }
    }

    /** The answer of a confirmation that is no longer pending (the elicitation stream reports it), else empty. */
    public Optional<ObjectNode> settled(Caller caller, UUID confirmationId) {
        return transactions.execute(status -> confirmations.lock(confirmationId, caller.subjectId())
                .filter(c -> c.status() != McpConfirmations.Status.PENDING)
                .map(this::replay));
    }

    private Outcome dispatch(Context ctx, String name, JsonNode args, Audit record) {
        Caller caller = ctx.caller();
        switch (name) {
            case "list_workflows":
                return read(listWorkflows(caller, args));
            case "search_requests":
                return read(searchRequests(caller, args, record));
            case "get_request": {
                WorkspaceAccess access = workspace(caller, args, record);
                UUID runId = uuid(args, "requestId", true);
                record.runId = runId;
                return read(object("request", runtime.request(caller, access.workspaceId(), runId)));
            }
            case "explain_request":
                return read(explain(caller, args, record));
            case "list_my_tasks": {
                WorkspaceAccess access = workspace(caller, args, record);
                String view = Optional.ofNullable(string(args, "view", false, 20)).orElse("assigned");
                if (!List.of("assigned", "claimable", "completed").contains(view)) {
                    throw new ToolError("MALFORMED_REQUEST", "view must be assigned, claimable or completed.");
                }
                return read((ObjectNode) runtime.tasks(caller, access.workspaceId(), view, string(args, "q", false, 200),
                        string(args, "cursor", false, 2000), limit(args)));
            }
            case "get_task": {
                WorkspaceAccess access = workspace(caller, args, record);
                String taskId = taskId(args);
                record.taskId = taskId;
                return read(object("task", runtime.task(caller, access.workspaceId(), taskId)));
            }
            case "decide_task":
                return prepareDecision(caller, args, record);
            case "send_message":
                return prepareMessage(caller, args, record);
            case CONFIRM: {
                UUID id = uuid(args, "confirmationId", true);
                record.step = "confirm";
                record.confirmationId = id;
                return new Outcome(execute(caller, id, record), null);
            }
            default:
                if (name != null && name.startsWith("start_")) {
                    return prepareStart(caller, name, args, record);
                }
                throw new UnknownTool(name);
        }
    }

    /** JSON-RPC -32602: the tool does not exist for this person. */
    public static final class UnknownTool extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnknownTool(String name) {
            super("Unknown tool: " + (name == null ? "" : name.length() > 128 ? name.substring(0, 128) : name));
        }
    }

    // ---------------------------------------------------------------- reads

    private ObjectNode listWorkflows(Caller caller, JsonNode args) {
        List<WorkspaceAccess> scope = args.hasNonNull("workspaceId") ? List.of(workspace(caller, args, new Audit("x")))
                : enabledWorkspaces(caller);
        Map<UUID, String> toolNames = new HashMap<>();
        for (StartTool start : startTools(caller)) {
            toolNames.put(start.workflow().id(), start.name());
        }
        ObjectNode out = json.object();
        ArrayNode list = out.putArray("workspaces");
        for (WorkspaceAccess access : scope) {
            ObjectNode ws = list.addObject();
            ws.put("workspaceId", access.workspaceId().toString());
            ws.put("key", access.workspace().key());
            ws.put("name", access.workspace().name());
            ws.put("isTest", access.workspace().isTest());
            ArrayNode items = ws.putArray("workflows");
            // One more than shown, so the assistant is told when the list is cut instead of seeing a silent subset.
            List<ToolWorkflows.Startable> startable = workflows.startable(caller, access.workspaceId(), MAX_START_TOOLS + 1);
            boolean truncated = startable.size() > MAX_START_TOOLS;
            ws.put("truncated", truncated);
            if (truncated) {
                ws.put("note", "Only the first " + MAX_START_TOOLS + " workflows you may start are listed.");
            }
            for (ToolWorkflows.Startable w : startable.subList(0, Math.min(startable.size(), MAX_START_TOOLS))) {
                ObjectNode item = items.addObject();
                item.put("workflowId", w.id().toString());
                item.put("key", w.key());
                item.put("name", w.name());
                if (w.description() != null) {
                    item.put("description", w.description());
                }
                item.put("releaseNumber", w.releaseNumber());
                String tool = toolNames.get(w.id());
                if (tool != null) {
                    item.put("tool", tool);
                } else {
                    item.put("toolNote", !w.exposeAsTool() ? "Not offered to assistants; start it in Orqix."
                            : "Needs a file on its form; start it in Orqix.");
                }
            }
        }
        if (scope.isEmpty()) {
            out.put("note", "The Orqix MCP server is not switched on in any workspace you belong to. A workspace "
                    + "administrator can switch it on in Settings, MCP server.");
        }
        return out;
    }

    private ObjectNode searchRequests(Caller caller, JsonNode args, Audit record) {
        WorkspaceAccess access = workspace(caller, args, record);
        String lifecycle = string(args, "lifecycle", false, 20);
        if (lifecycle != null && !LIFECYCLES.contains(lifecycle)) {
            throw new ToolError("MALFORMED_REQUEST", "Unknown lifecycle.");
        }
        int limit = limit(args);
        String cursor = string(args, "cursor", false, 2000);
        boolean mine = args.path("mine").asBoolean(false) || !access.has(Permission.RUN_READ);
        ObjectNode page;
        if (mine) {
            page = (ObjectNode) runtime.myRequests(caller, access.workspaceId(), lifecycle, cursor, limit);
        } else {
            UUID workflowId = uuid(args, "workflowId", false);
            record.workflowId = workflowId;
            page = (ObjectNode) runtime.searchRequests(caller, access.workspaceId(), workflowId, lifecycle, null,
                    string(args, "businessKey", false, 100), instant(args, "createdFrom"), instant(args, "createdTo"),
                    args.path("overdue").asBoolean(false), cursor, limit);
        }
        page.put("scope", mine ? "mine" : "workspace");
        return page;
    }

    private ObjectNode explain(Caller caller, JsonNode args, Audit record) {
        WorkspaceAccess access = workspace(caller, args, record);
        UUID runId = uuid(args, "requestId", true);
        record.runId = runId;
        JsonNode run = runtime.request(caller, access.workspaceId(), runId);
        JsonNode events = runtime.timeline(caller, access.workspaceId(), runId, 100);
        return McpExplanations.explain(json, run, events);
    }

    // ---------------------------------------------------------------- prepared writes

    private Outcome prepareStart(Caller caller, String name, JsonNode args, Audit record) {
        record.step = "prepare";
        StartTool start = startTools(caller).stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new UnknownTool(name));
        UUID workspaceId = start.workspace().id();
        record.workspaceId = workspaceId;
        record.workflowId = start.workflow().id();
        JsonNode input = args.path("input");
        if (!input.isObject()) {
            input = json.object();
        }
        String businessKey = string(args, "businessKey", false, 100);
        List<ApiException.FieldError> errors = runtime.checkStart(caller, workspaceId, start.workflow().id(), input);
        if (!errors.isEmpty()) {
            throw new ToolError(ProblemCode.VALIDATION_FAILED.name(), "Some values are not valid; correct them and call "
                    + name + " again.", errors);
        }
        StringBuilder summary = new StringBuilder("Start a new \"" + start.workflow().name() + "\" request (release "
                + start.workflow().releaseNumber() + ") in " + start.workspace().name() + ".");
        if (businessKey != null) {
            summary.append(" Business key: ").append(businessKey).append('.');
        }
        summary.append(McpExplanations.values(start.workflow().inputSchema(), input));
        ObjectNode stored = json.object();
        stored.put("workflowId", start.workflow().id().toString());
        stored.put("releaseId", start.workflow().releaseId().toString());
        stored.set("input", input);
        if (businessKey != null) {
            stored.put("businessKey", businessKey);
        }
        return prepared(caller, workspaceId, name, "startRequest", start.workflow().id().toString(), stored,
                summary.toString(), record);
    }

    private Outcome prepareDecision(Caller caller, JsonNode args, Audit record) {
        record.step = "prepare";
        WorkspaceAccess access = workspace(caller, args, record);
        String taskId = taskId(args);
        record.taskId = taskId;
        String action = string(args, "action", true, 10);
        if (!List.of("approve", "reject").contains(action)) {
            throw new ToolError("MALFORMED_REQUEST", "action must be approve or reject.");
        }
        String reason = string(args, "reason", false, 1000);
        String comment = string(args, "comment", false, 2000);
        if ("reject".equals(action) && (reason == null || reason.isBlank())) {
            throw new ToolError(ProblemCode.REASON_REQUIRED.name(), "Give a reason for the rejection.",
                    List.of(new ApiException.FieldError("/reason", "required", "A reason is required")));
        }
        JsonNode task = runtime.task(caller, access.workspaceId(), taskId);
        if (!"open".equals(task.path("status").asString())) {
            throw new ToolError(ProblemCode.TASK_NOT_OPEN.name(), "This task was already decided.");
        }
        JsonNode allowed = task.path("allowedActions");
        boolean canDecide = allowed.path("decide").path("allowed").asBoolean(false);
        boolean canClaim = allowed.path("claim").path("allowed").asBoolean(false);
        if (!canDecide && !canClaim) {
            String why = allowed.path("decide").path("reasonCode").asString(
                    allowed.path("claim").path("reasonCode").asString("NOT_ELIGIBLE"));
            throw new ToolError(why, "You cannot decide this task now (" + why + ").");
        }
        record.runId = uuidOrNull(task.path("runId").asString(null));
        String summary = ("approve".equals(action) ? "Approve" : "Reject") + " the task \"" + task.path("label").asString("")
                + "\" of request " + task.path("runReference").asString("") + reference(task.path("requestTitle"))
                + " (" + task.path("workflowName").asString("") + ", requested by "
                + task.path("requester").path("displayName").asString("someone") + ")."
                + (canDecide ? "" : " The task is claimed for you first.")
                + (reason == null ? "" : " Reason: " + reason + ".")
                + (comment == null ? "" : " Comment: " + comment + ".");
        ObjectNode stored = json.object();
        stored.put("taskId", taskId);
        stored.put("version", task.path("version").asInt());
        stored.put("action", action);
        if (reason != null) {
            stored.put("reason", reason);
        }
        if (comment != null) {
            stored.put("comment", comment);
        }
        return prepared(caller, access.workspaceId(), "decide_task", "decideTask", taskId, stored, summary, record);
    }

    private Outcome prepareMessage(Caller caller, JsonNode args, Audit record) {
        record.step = "prepare";
        WorkspaceAccess access = workspace(caller, args, record);
        policy.require(access, Permission.MESSAGE_SEND, "sendMessage", true);
        String name = string(args, "name", true, 64);
        if (!name.matches("^[a-z][a-zA-Z0-9]*(-[a-z0-9][a-zA-Z0-9]*)*$") || name.length() < 2) {
            throw new ToolError("MALFORMED_REQUEST", "name must look like payment-received.");
        }
        String correlationKey = string(args, "correlationKey", true, 200);
        JsonNode payload = args.path("payload");
        if (!payload.isMissingNode() && !payload.isNull() && !payload.isObject()) {
            throw new ToolError("MALFORMED_REQUEST", "payload must be an object.");
        }
        StringBuilder summary = new StringBuilder("Send the message \"" + name + "\" for \"" + correlationKey + "\" in "
                + access.workspace().name() + " to the requests waiting for it.");
        if (payload.isObject() && !payload.isEmpty()) {
            summary.append(" Values: ");
            List<String> parts = new ArrayList<>();
            payload.properties().forEach(e -> parts.add(e.getKey() + " = " + McpExplanations.scalar(e.getValue())));
            summary.append(String.join(", ", parts)).append('.');
        }
        ObjectNode stored = json.object();
        stored.put("name", name);
        stored.put("correlationKey", correlationKey);
        if (payload.isObject()) {
            stored.set("payload", payload);
        }
        return prepared(caller, access.workspaceId(), "send_message", "sendMessage", null, stored, summary.toString(),
                record);
    }

    private Outcome prepared(Caller caller, UUID workspaceId, String tool, String action, String targetId,
            ObjectNode stored, String summary, Audit record) {
        McpConfirmations.Confirmation c = transactions.execute(status ->
                confirmations.create(workspaceId, caller.subjectId(), tool, action, targetId, stored, summary));
        record.confirmationId = c.id();
        ObjectNode body = json.object();
        body.put("status", "confirmationRequired");
        body.put("confirmationId", c.id().toString());
        body.put("expiresAt", c.expiresAt().toString());
        body.put("summary", summary);
        body.put("next", "Show this summary to the person. Only if they agree, call confirm with this confirmationId "
                + "before " + c.expiresAt() + ". Nothing has been done yet.");
        return new Outcome(result(body, summary + "\n\nNothing has been done yet. Ask the person to confirm, then call "
                + "confirm with confirmationId " + c.id() + "."), c);
    }

    // ---------------------------------------------------------------- confirm

    private ObjectNode execute(Caller caller, UUID id, Audit record) {
        try {
            return transactions.execute(status -> {
                McpConfirmations.Confirmation c = confirmations.lock(id, caller.subjectId())
                        .orElseThrow(() -> new ToolError(ProblemCode.NOT_FOUND.name(), "No such confirmation."));
                record.workspaceId = c.workspaceId();
                ids(record, c);
                if (c.status() != McpConfirmations.Status.PENDING) {
                    record.outcome = c.status() == McpConfirmations.Status.CONFIRMED ? "replayed" : c.status().value();
                    return replay(c);
                }
                if (c.expired(clock.instant())) {
                    confirmations.finish(c.id(), McpConfirmations.Status.EXPIRED, null);
                    record.outcome = "expired";
                    return expired();
                }
                if (!switches.enabled(c.workspaceId())) {
                    throw new ToolError("MCP_DISABLED", "The Orqix MCP server was switched off in this workspace.");
                }
                ObjectNode body = run(caller, c, record);
                confirmations.finish(c.id(), McpConfirmations.Status.CONFIRMED, body);
                return result(body, McpExplanations.done(body));
            });
        } catch (ApiException e) {
            // Retryable refusals (busy, quota, version race) keep the confirmation open; others end it.
            int code = e.code().status();
            if (code != 429 && code != 503) {
                ObjectNode failure = errorBody(e.code().name(), e.detail() == null ? e.code().title() : e.detail(),
                        fieldErrors(e));
                transactions.executeWithoutResult(status -> confirmations.finish(id, McpConfirmations.Status.FAILED, failure));
            }
            throw e;
        }
    }

    private ObjectNode decline(Caller caller, UUID id, Audit record) {
        return transactions.execute(status -> {
            McpConfirmations.Confirmation c = confirmations.lock(id, caller.subjectId())
                    .orElseThrow(() -> new ToolError(ProblemCode.NOT_FOUND.name(), "No such confirmation."));
            record.workspaceId = c.workspaceId();
            ids(record, c);
            if (c.status() == McpConfirmations.Status.PENDING) {
                ObjectNode body = json.object();
                body.put("status", "declined");
                body.put("summary", c.summary());
                confirmations.finish(c.id(), McpConfirmations.Status.DECLINED, body);
                record.outcome = "declined";
                return result(body, "The person declined. Nothing was done.");
            }
            return replay(c);
        });
    }

    private ObjectNode run(Caller caller, McpConfirmations.Confirmation c, Audit record) {
        JsonNode a = c.arguments();
        String key = "mcp:" + c.id();
        ObjectNode body = json.object();
        body.put("status", "done");
        switch (c.action()) {
            case "startRequest": {
                UUID workflowId = UUID.fromString(a.path("workflowId").asString());
                AssistantRuntime.Written w = runtime.start(caller, c.workspaceId(), key, workflowId,
                        a.path("businessKey").asString(null), a.path("input"));
                record.runId = uuidOrNull(w.body().path("id").asString(null));
                body.put("action", "requestStarted");
                body.set("request", w.body());
                return body;
            }
            case "decideTask": {
                AssistantRuntime.Written w = runtime.decide(caller, c.workspaceId(), a.path("taskId").asString(), key,
                        a.path("version").asInt(), a.path("action").asString(), a.path("reason").asString(null),
                        a.path("comment").asString(null));
                body.put("action", "taskDecided");
                body.set("decision", w.body());
                return body;
            }
            case "sendMessage": {
                AssistantRuntime.Written w = runtime.sendMessage(caller, c.workspaceId(), key, a.path("name").asString(),
                        a.path("correlationKey").asString(), a.path("payload").isObject() ? a.path("payload") : null);
                record.messageId = uuidOrNull(w.body().path("id").asString(null));
                body.put("action", "messageSent");
                body.set("message", w.body());
                return body;
            }
            default:
                throw new IllegalStateException("Unknown confirmation action " + c.action());
        }
    }

    private ObjectNode replay(McpConfirmations.Confirmation c) {
        return switch (c.status()) {
            case CONFIRMED -> result((ObjectNode) c.result(), McpExplanations.done((ObjectNode) c.result()));
            case DECLINED -> result(c.result() == null ? json.object().put("status", "declined") : (ObjectNode) c.result(),
                    "The person declined this action. Nothing was done.");
            case EXPIRED -> expired();
            case FAILED -> {
                ObjectNode body = c.result() == null ? errorBody("FAILED", "The action failed.", List.of())
                        : (ObjectNode) c.result();
                ObjectNode r = result(body, "The action failed: " + body.path("error").path("message").asString(""));
                r.put("isError", true);
                yield r;
            }
            case PENDING -> throw new IllegalStateException("pending");
        };
    }

    private ObjectNode expired() {
        return error("CONFIRMATION_EXPIRED", "This confirmation expired. Call the tool again to prepare a new summary.",
                List.of());
    }

    // ---------------------------------------------------------------- workspaces and start tools

    /** Workspaces the person may see where the MCP server is on. */
    List<WorkspaceAccess> enabledWorkspaces(Caller caller) {
        List<WorkspaceAccess> visible = policy.memberships(caller);
        Set<UUID> on = switches.enabledAmong(visible.stream().map(WorkspaceAccess::workspaceId).toList());
        return visible.stream().filter(a -> on.contains(a.workspaceId())).toList();
    }

    /** The workspace of a call; a workspace that is off, foreign or unknown is "not found", all alike. */
    private WorkspaceAccess workspace(Caller caller, JsonNode args, Audit record) {
        UUID requested = uuid(args, "workspaceId", false);
        if (requested != null) {
            Optional<WorkspaceAccess> access = policy.freshAccess(caller, requested).filter(WorkspaceAccess::visible);
            if (access.isEmpty() || !switches.enabled(requested)) {
                throw new ToolError(ProblemCode.NOT_FOUND.name(), "Not found.");
            }
            record.workspaceId = requested;
            return access.get();
        }
        List<WorkspaceAccess> on = enabledWorkspaces(caller);
        if (on.size() == 1) {
            record.workspaceId = on.get(0).workspaceId();
            return on.get(0);
        }
        if (on.isEmpty()) {
            throw new ToolError("MCP_DISABLED", "The Orqix MCP server is not switched on in any workspace you belong to.");
        }
        List<String> choices = on.stream().map(a -> a.workspaceId() + " (" + a.workspace().name() + ")").toList();
        throw new ToolError("WORKSPACE_REQUIRED", "Pass workspaceId: one of " + String.join(", ", choices) + ".");
    }

    /** Start tools of the person: exposed workflows they may start now, in MCP-enabled workspaces. */
    List<StartTool> startTools(Caller caller) {
        List<StartTool> tools = new ArrayList<>();
        Map<String, Integer> names = new HashMap<>();
        for (WorkspaceAccess access : enabledWorkspaces(caller)) {
            for (ToolWorkflows.Startable w : workflows.startable(caller, access.workspaceId(), MAX_START_TOOLS)) {
                Optional<ObjectNode> input = McpSchemas.toolInput(json, w.inputSchema());
                if (!w.exposeAsTool() || input.isEmpty() || tools.size() >= MAX_START_TOOLS) {
                    continue;
                }
                String base = "start_" + sanitize(w.key());
                names.merge(base, 1, Integer::sum);
                tools.add(new StartTool(base, access.workspace(), w, startSchema(input.get())));
            }
        }
        // The same workflow key in two workspaces: both names carry their workspace key.
        List<StartTool> named = new ArrayList<>();
        for (StartTool t : tools) {
            String name = names.get(t.name()) > 1 ? t.name() + "__" + sanitize(t.workspace().key()) : t.name();
            named.add(new StartTool(name.length() > 128 ? name.substring(0, 128) : name, t.workspace(), t.workflow(),
                    t.inputSchema()));
        }
        return named;
    }

    private ObjectNode startSchema(ObjectNode input) {
        ObjectNode schema = json.object();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.set("input", input);
        props.set("businessKey", maxLen(prop("businessKey", "string",
                "Optional business key, for example an order number."), 100).get("businessKey"));
        if (input.path("required").isArray() && !input.path("required").isEmpty()) {
            schema.putArray("required").add("input");
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static String startDescription(StartTool t) {
        String description = t.workflow().description() == null || t.workflow().description().isBlank() ? ""
                : " " + t.workflow().description().trim();
        return "Prepares a new \"" + t.workflow().name() + "\" request in " + t.workspace().name()
                + (t.workspace().isTest() ? " (test workspace)" : "") + "." + description
                + " Returns a summary and a confirmationId: nothing is started until the person confirms. Show the "
                + "summary and call confirm only after they agree.";
    }

    static String sanitize(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    // ---------------------------------------------------------------- results

    private Outcome read(ObjectNode structured) {
        return new Outcome(result(structured, json.write(structured)), null);
    }

    private ObjectNode result(ObjectNode structured, String text) {
        ObjectNode r = json.object();
        r.putArray("content").addObject().put("type", "text").put("text", text);
        r.set("structuredContent", structured);
        r.put("isError", false);
        return r;
    }

    private ObjectNode error(ApiException e) {
        String message = e.detail() != null ? e.detail() : e.code().title();
        if (e.code() == ProblemCode.NOT_FOUND) {
            message = "Not found.";
        }
        return error(e.code().name(), message, fieldErrors(e));
    }

    private ObjectNode error(String code, String message, List<ApiException.FieldError> fieldErrors) {
        ObjectNode r = result(errorBody(code, message, fieldErrors), message);
        r.put("isError", true);
        return r;
    }

    private ObjectNode errorBody(String code, String message, List<ApiException.FieldError> fieldErrors) {
        ObjectNode body = json.object();
        ObjectNode error = body.putObject("error");
        error.put("code", code);
        error.put("message", message);
        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            ArrayNode list = error.putArray("fieldErrors");
            for (ApiException.FieldError f : fieldErrors) {
                list.addObject().put("pointer", f.pointer()).put("code", f.code()).put("message", f.message());
            }
        }
        return body;
    }

    private ObjectNode object(String name, JsonNode value) {
        ObjectNode o = json.object();
        o.set(name, value);
        return o;
    }

    // ---------------------------------------------------------------- arguments

    private static String string(JsonNode args, String name, boolean required, int max) {
        JsonNode v = args.path(name);
        if (v.isMissingNode() || v.isNull()) {
            if (required) {
                throw new ToolError("MALFORMED_REQUEST", name + " is required.");
            }
            return null;
        }
        if (!v.isString()) {
            throw new ToolError("MALFORMED_REQUEST", name + " must be a string.");
        }
        String s = v.asString();
        if (s.length() > max) {
            throw new ToolError("MALFORMED_REQUEST", name + " must be at most " + max + " characters.");
        }
        if (required && s.isBlank()) {
            throw new ToolError("MALFORMED_REQUEST", name + " is required.");
        }
        return s;
    }

    private static UUID uuid(JsonNode args, String name, boolean required) {
        String s = string(args, name, required, 64);
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            // An ID that cannot exist is answered like an unknown one.
            throw new ToolError(ProblemCode.NOT_FOUND.name(), "Not found.");
        }
    }

    private static UUID uuidOrNull(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String taskId(JsonNode args) {
        String id = string(args, "taskId", true, 64);
        if (!TASK_ID.matcher(id).matches()) {
            throw new ToolError(ProblemCode.NOT_FOUND.name(), "Not found.");
        }
        return id;
    }

    private static int limit(JsonNode args) {
        JsonNode v = args.path("limit");
        if (v.isMissingNode() || v.isNull()) {
            return 20;
        }
        if (!v.isIntegralNumber() || v.asInt() < 1 || v.asInt() > MAX_PAGE) {
            throw new ToolError("MALFORMED_REQUEST", "limit must be an integer 1.." + MAX_PAGE + ".");
        }
        return v.asInt();
    }

    private static Instant instant(JsonNode args, String name) {
        String s = string(args, name, false, 40);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            throw new ToolError("MALFORMED_REQUEST", name + " must be an RFC 3339 UTC time.");
        }
    }

    private static String reference(JsonNode title) {
        return title.isString() && !title.asString().isBlank() ? " \"" + title.asString() + "\"" : "";
    }

    // ---------------------------------------------------------------- tool definitions

    private ObjectNode tool(String name, String title, String description, ObjectNode inputSchema, boolean readOnly,
            boolean idempotent) {
        ObjectNode t = json.object();
        t.put("name", name);
        t.put("title", title);
        t.put("description", description);
        t.set("inputSchema", inputSchema);
        ObjectNode annotations = t.putObject("annotations");
        annotations.put("title", title);
        annotations.put("readOnlyHint", readOnly);
        if (!readOnly) {
            annotations.put("destructiveHint", false);
            annotations.put("idempotentHint", idempotent);
        }
        annotations.put("openWorldHint", false);
        return t;
    }

    @SafeVarargs
    private ObjectNode schema(ObjectNode... properties) {
        return schema(List.of(), properties);
    }

    private ObjectNode schema(List<String> required, ObjectNode... properties) {
        ObjectNode s = json.object();
        s.put("type", "object");
        ObjectNode props = s.putObject("properties");
        for (ObjectNode p : properties) {
            p.properties().forEach(e -> props.set(e.getKey(), e.getValue()));
        }
        if (!required.isEmpty()) {
            ArrayNode r = s.putArray("required");
            required.forEach(r::add);
        }
        s.put("additionalProperties", false);
        return s;
    }

    private ObjectNode workspaceProperty() {
        return prop("workspaceId", "string", "Workspace ID from list_workflows. Optional when the Orqix MCP server is on "
                + "in only one of your workspaces.");
    }

    private ObjectNode limitProp() {
        ObjectNode p = prop("limit", "integer", "Page size, 1.." + MAX_PAGE + " (default 20).");
        ((ObjectNode) p.get("limit")).put("minimum", 1).put("maximum", MAX_PAGE);
        return p;
    }

    private ObjectNode prop(String name, String type, String description) {
        ObjectNode wrapper = json.object();
        ObjectNode p = wrapper.putObject(name);
        p.put("type", type);
        p.put("description", description);
        return wrapper;
    }

    private ObjectNode enumProp(String name, String description, List<String> values) {
        ObjectNode wrapper = prop(name, "string", description);
        ArrayNode e = ((ObjectNode) wrapper.get(name)).putArray("enum");
        values.forEach(e::add);
        return wrapper;
    }

    private static ObjectNode maxLen(ObjectNode wrapper, int max) {
        wrapper.properties().forEach(e -> ((ObjectNode) e.getValue()).put("maxLength", max));
        return wrapper;
    }

    // ---------------------------------------------------------------- audit

    /** What the audit record of one call says; filled while the call runs. */
    private static final class Audit {
        final String tool;
        String step = "read";
        String outcome;
        String errorCode;
        UUID workspaceId;
        UUID confirmationId;
        UUID runId;
        String taskId;
        UUID workflowId;
        UUID messageId;

        Audit(String tool) {
            this.tool = tool;
        }

        void fail(String code) {
            errorCode = code;
            outcome = ProblemCode.NOT_FOUND.name().equals(code) || ProblemCode.FORBIDDEN.name().equals(code)
                    ? "denied" : "failed";
        }
    }

    private void ids(Audit record, McpConfirmations.Confirmation c) {
        switch (c.action()) {
            case "startRequest" -> record.workflowId = uuidOrNull(c.targetId());
            case "decideTask" -> record.taskId = c.targetId();
            default -> { }
        }
    }

    private void write(Context ctx, Audit record) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        // Unknown tool names are the assistant's text: recorded only when they are one of ours or a start tool name.
        String tool = record.tool == null ? "" : record.tool.matches("^[a-z][a-z0-9_]{0,127}$") ? record.tool : "unknown";
        metadata.put("tool", tool);
        metadata.put("step", record.step);
        metadata.put("outcome", record.outcome == null ? "failed" : record.outcome);
        metadata.put("errorCode", record.errorCode);
        metadata.put("confirmationId", str(record.confirmationId));
        metadata.put("runId", str(record.runId));
        metadata.put("taskId", record.taskId);
        metadata.put("workflowId", str(record.workflowId));
        metadata.put("messageId", str(record.messageId));
        metadata.put("client", ctx.client() == null || ctx.client().length() > 100 ? null : ctx.client());
        AuditWriter.Outcome outcome = "denied".equals(metadata.get("outcome")) ? AuditWriter.Outcome.DENIED
                : List.of("success", "replayed", "confirmationRequired", "declined").contains(metadata.get("outcome"))
                        ? AuditWriter.Outcome.SUCCESS : AuditWriter.Outcome.FAILED;
        audit.writeSeparately(new AuditWriter.Entry(record.workspaceId, AuditWriter.ActorType.USER,
                ctx.caller().subjectId(), "mcp.tool-called", "mcpTool", tool, outcome, metadata));
    }

    @SuppressWarnings("unchecked")
    private static List<ApiException.FieldError> fieldErrors(ApiException e) {
        Object value = e.extensions().get("fieldErrors");
        return value instanceof List<?> list ? (List<ApiException.FieldError>) list : List.of();
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
