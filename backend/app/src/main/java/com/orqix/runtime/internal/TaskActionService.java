package com.orqix.runtime.internal;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.SubjectService;
import com.orqix.integration.ChannelSecrets;
import com.orqix.runtime.api.RuntimeDtos;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Acting on a task from an email link or a Teams/Slack card (CG-T01, docs/build/S141). Every action runs as the person:
 * a signed link is bound to one task and one person; a chat press is verified with the platform's signature and its
 * user mapped to an Orqix subject. The person is rebuilt from the groups of their latest sign-in (as delegation and
 * dispatch see them) and the action goes through {@link TaskService} exactly like the task API, so eligibility,
 * separation of duties, self-approval, versions, idempotency, suspension and the service's own audit records are the
 * same. Deciding always needs a confirmation: the link's confirmation page posts {@code confirm: true}; a chat press
 * stores a 5-minute confirmation that the person's second press executes once.
 */
@Service
public class TaskActionService {

    static final Duration CONFIRMATION_VALIDITY = Duration.ofMinutes(5);
    static final Duration SLACK_SKEW = Duration.ofMinutes(5);

    public record LinkActions(RuntimeDtos.ActionAvailability claim, RuntimeDtos.ActionAvailability approve,
            RuntimeDtos.ActionAvailability reject) {
    }

    public record LinkView(UUID workspaceId, String taskId, String label, String workflowName, String runReference,
            String status, Instant dueAt, int version, SubjectService.SubjectRef person, String channel,
            Instant expiresAt, boolean rejectReasonRequired, LinkActions actions) {
    }

    public record LinkActRequest(@NotNull @Pattern(regexp = "claim|approve|reject") String action,
            @NotNull @Min(1) Integer expectedVersion, @Size(max = 1000) String reason, @Size(max = 2000) String comment,
            Boolean confirm) {
    }

    public record ActionResult(String action, String outcome, String taskId, String taskStatus, int version,
            String decision) {
    }

    /** What a chat press asks for. */
    record Interaction(String action, String taskId, Integer version, UUID confirmationId, String reason) {
    }

    /** The answer shown in the chat: a text and buttons (label, action, value). */
    record Reply(String text, List<String[]> buttons) {
        static Reply of(String text) {
            return new Reply(text, List.of());
        }
    }

    private final TaskActionLinks links;
    private final TaskActionSettings settings;
    private final TaskService taskService;
    private final TaskStore tasks;
    private final ChannelSecrets secrets;
    private final AuditWriter audit;
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public TaskActionService(TaskActionLinks links, TaskActionSettings settings, TaskService taskService,
            TaskStore tasks, ChannelSecrets secrets, AuditWriter audit, JdbcClient jdbc, Json json, Clock clock,
            PlatformTransactionManager transactions) {
        this.links = links;
        this.settings = settings;
        this.taskService = taskService;
        this.tasks = tasks;
        this.secrets = secrets;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ signed links

    /** The confirmation page of a link: what the person may do now. Nothing changes except the use counter. */
    public LinkView link(String token) {
        return tx.execute(status -> {
            TaskActionLinks.Link link = links.verify(token).orElseThrow(ApiException::notFound);
            Caller person = person(link);
            RuntimeDtos.TaskDetail detail = taskService.getTask(person, link.workspaceId(), link.taskId());
            links.used(link.id());
            return view(link, person, detail);
        });
    }

    /** Executes the confirmed action of a link as the person. */
    public ActionResult act(String token, LinkActRequest request) {
        TaskActionLinks.Link link = links.verify(token).orElseThrow(ApiException::notFound);
        if (!Boolean.TRUE.equals(request.confirm())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Confirm the action first.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/confirm", "const", "Must be true")));
        }
        try {
            ActionResult result = tx.execute(status -> {
                Caller person = person(link);
                String decided = jdbc.sql("SELECT decided_action FROM task_action_link WHERE id = :id FOR UPDATE")
                        .param("id", link.id()).query(String.class).optional().orElse(null);
                links.used(link.id());
                if ("claim".equals(request.action())) {
                    RuntimeDtos.TaskDetail detail = taskService.claim(person, link.workspaceId(), link.taskId(),
                            request.expectedVersion());
                    return new ActionResult("claim", "done", link.taskId(), detail.status(), detail.version(), null);
                }
                if (decided != null) {
                    if (!decided.equals(request.action())) {
                        throw new ApiException(ProblemCode.TASK_NOT_OPEN, "This link was already used to " + decided + ".");
                    }
                    TaskStore.TaskRow row = tasks.find(link.workspaceId(), link.taskId()).orElseThrow();
                    return new ActionResult(decided, "replayed", link.taskId(), row.status(), row.version(),
                            "approve".equals(decided) ? "approved" : "rejected");
                }
                RuntimeDtos.TaskDecision decision = decide(person, link.workspaceId(), link.taskId(),
                        request.expectedVersion(), request.action(), request.reason(), request.comment(),
                        "task-link:" + link.id());
                jdbc.sql("UPDATE task_action_link SET decided_action = :a, decided_at = :now WHERE id = :id")
                        .param("a", request.action()).param("now", Sql.ts(clock.instant())).param("id", link.id()).update();
                TaskStore.TaskRow row = tasks.find(link.workspaceId(), link.taskId()).orElseThrow();
                return new ActionResult(request.action(), "done", link.taskId(), row.status(), row.version(),
                        decision.action() == null ? null : "approve".equals(decision.action()) ? "approved" : "rejected");
            });
            used(link.workspaceId(), link.subjectId(), link.taskId(), link.channel(), request.action(), result.outcome(),
                    link.id(), null, null);
            return result;
        } catch (ApiException e) {
            used(link.workspaceId(), link.subjectId(), link.taskId(), link.channel(), request.action(), "refused",
                    link.id(), null, e.code().name());
            throw e;
        }
    }

    private LinkView view(TaskActionLinks.Link link, Caller person, RuntimeDtos.TaskDetail detail) {
        RuntimeDtos.AllowedTaskActions allowed = detail.allowedActions();
        RuntimeDtos.ActionAvailability decide = allowed.decide().allowed() ? allowed.decide()
                : "TASK_NOT_CLAIMED_BY_CALLER".equals(allowed.decide().reasonCode()) ? allowed.claim() : allowed.decide();
        return new LinkView(link.workspaceId(), link.taskId(), detail.label(), detail.workflowName(),
                detail.runReference(), detail.status(), detail.dueAt(), detail.version(),
                new SubjectService.SubjectRef(person.subjectId(), person.displayName()), link.channel(),
                link.expiresAt(), detail.form().rejectReasonRequired(), new LinkActions(allowed.claim(), decide, decide));
    }

    /** Claims first when the task waits in the person's queue (as the task page does), then decides. */
    private RuntimeDtos.TaskDecision decide(Caller person, UUID workspaceId, String taskId, int expectedVersion,
            String action, String reason, String comment, String idempotencyKey) {
        TaskStore.TaskRow row = tasks.find(workspaceId, taskId).orElseThrow(ApiException::notFound);
        int version = expectedVersion;
        if (row.isOpen() && row.assignee() == null) {
            version = taskService.claim(person, workspaceId, taskId, expectedVersion).version();
        }
        return taskService.decide(person, workspaceId, taskId, idempotencyKey,
                new RuntimeDtos.DecisionRequest(version, action, blank(reason) ? null : reason.strip(),
                        blank(comment) ? null : comment.strip())).decision();
    }

    private Caller person(TaskActionLinks.Link link) {
        return taskService.storedCaller(link.subjectId()).orElseThrow(ApiException::notFound);
    }

    // ------------------------------------------------------------------ chat interactions

    /** A Slack interaction (Block Kit button press), verified with the Slack signing secret. */
    public JsonNode slack(UUID workspaceId, String timestamp, String signature, byte[] body) {
        String secret = channelSecret(workspaceId, "slack");
        long ts;
        try {
            ts = Long.parseLong(timestamp == null ? "" : timestamp.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED, "The interaction is not signed.");
        }
        if (Math.abs(clock.instant().getEpochSecond() - ts) > SLACK_SKEW.toSeconds()) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED, "The interaction is too old.");
        }
        String expected = "v0=" + HexFormat.of().formatHex(hmac(secret.getBytes(StandardCharsets.UTF_8),
                concat(("v0:" + ts + ":").getBytes(StandardCharsets.UTF_8), body)));
        if (signature == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.trim().getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED, "The interaction signature does not match.");
        }
        String text = new String(body, StandardCharsets.UTF_8);
        JsonNode payload = json.parse(text.startsWith("payload=")
                ? URLDecoder.decode(text.substring("payload=".length()), StandardCharsets.UTF_8) : text);
        String user = payload.path("user").path("id").asString(null);
        JsonNode pressed = payload.path("actions").path(0);
        String actionId = pressed.path("action_id").asString("");
        JsonNode value = pressed.path("value").isString() ? safeParse(pressed.path("value").asString()) : json.object();
        String reason = null;
        for (JsonNode block : payload.path("state").path("values")) {
            for (JsonNode element : block) {
                if (element.path("value").isString()) {
                    reason = element.path("value").asString();
                }
            }
        }
        Interaction interaction = new Interaction(actionId.startsWith("orqix-") ? actionId.substring(6) : actionId,
                value.path("taskId").asString(null), value.path("version").isInt() ? value.path("version").asInt() : null,
                uuid(value.path("confirmationId").asString(null)), reason);
        Reply reply = interact(workspaceId, "slack", user, interaction);
        ObjectNode out = json.object().put("response_type", "ephemeral").put("replace_original", false)
                .put("text", reply.text());
        ArrayNode blocks = out.putArray("blocks");
        blocks.addObject().put("type", "section").putObject("text").put("type", "mrkdwn").put("text", reply.text());
        if (!reply.buttons().isEmpty()) {
            ArrayNode elements = blocks.addObject().put("type", "actions").putArray("elements");
            for (String[] b : reply.buttons()) {
                ObjectNode button = elements.addObject().put("type", "button").put("action_id", "orqix-" + b[1]).put("value", b[2]);
                button.putObject("text").put("type", "plain_text").put("text", b[0]);
            }
        }
        return out;
    }

    /** A Teams interaction (Adaptive Card submit through the outgoing webhook), verified with its HMAC key. */
    public JsonNode teams(UUID workspaceId, String authorization, byte[] body) {
        String secret = channelSecret(workspaceId, "teams");
        byte[] key;
        try {
            key = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE, "The Teams signing secret is not usable.");
        }
        String expected = "HMAC " + Base64.getEncoder().encodeToString(hmac(key, body));
        if (authorization == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                authorization.trim().getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED, "The interaction signature does not match.");
        }
        JsonNode activity = json.parse(body);
        String user = activity.path("from").path("aadObjectId").asString(activity.path("from").path("id").asString(null));
        JsonNode value = activity.path("value");
        Interaction interaction = new Interaction(value.path("orqixAction").asString(""), value.path("taskId").asString(null),
                value.path("version").isInt() ? value.path("version").asInt() : null,
                uuid(value.path("confirmationId").asString(null)), value.path("reason").asString(null));
        Reply reply = interact(workspaceId, "teams", user, interaction);
        ObjectNode out = json.object().put("type", "message").put("text", reply.text());
        if (!reply.buttons().isEmpty()) {
            ObjectNode card = out.putArray("attachments").addObject()
                    .put("contentType", "application/vnd.microsoft.card.adaptive").putObject("content");
            card.put("type", "AdaptiveCard").put("version", "1.5");
            card.putArray("body").addObject().put("type", "TextBlock").put("text", reply.text()).put("wrap", true);
            ArrayNode actions = card.putArray("actions");
            for (String[] b : reply.buttons()) {
                ObjectNode data = actions.addObject().put("type", "Action.Submit").put("title", b[0]).putObject("data");
                data.put("orqixAction", b[1]);
                safeParse(b[2]).properties().forEach(e -> data.set(e.getKey(), e.getValue()));
            }
        }
        return out;
    }

    /** One press: claim at once; approve or reject prepares a confirmation; confirm executes it once. */
    Reply interact(UUID workspaceId, String platform, String platformUser, Interaction in) {
        UUID subject = platformUser == null ? null : settings.person(workspaceId, platform, platformUser).orElse(null);
        Caller person = subject == null ? null : taskService.storedCaller(subject).orElse(null);
        if (person == null) {
            used(workspaceId, null, in.taskId(), platform, in.action(), "refused", null, null, "NO_CHAT_IDENTITY");
            return Reply.of("Your " + platform + " account is not linked to an Orqix person, so nothing was done."
                    + " Ask your Orqix administrator to link it.");
        }
        return switch (in.action()) {
            case "claim" -> claim(workspaceId, platform, person, in);
            case "approve", "reject" -> prepare(workspaceId, platform, person, in);
            case "confirm" -> confirm(workspaceId, platform, person, in);
            case "cancel" -> cancel(workspaceId, platform, person, in);
            default -> Reply.of("That button is not an Orqix action.");
        };
    }

    private Reply claim(UUID workspaceId, String platform, Caller person, Interaction in) {
        if (in.taskId() == null || in.version() == null) {
            return Reply.of("That button is not an Orqix action.");
        }
        try {
            RuntimeDtos.TaskDetail detail = tx.execute(s -> taskService.claim(person, workspaceId, in.taskId(), in.version()));
            used(workspaceId, person.subjectId(), in.taskId(), platform, "claim", "done", null, null, null);
            String value = json.write(json.object().put("taskId", in.taskId()).put("version", detail.version()));
            return new Reply("You claimed \"" + detail.label() + "\" of request " + detail.runReference() + ".",
                    List.of(new String[] {"Approve", "approve", value}, new String[] {"Reject", "reject", value}));
        } catch (ApiException e) {
            used(workspaceId, person.subjectId(), in.taskId(), platform, "claim", "refused", null, null, e.code().name());
            return refused(e);
        }
    }

    private Reply prepare(UUID workspaceId, String platform, Caller person, Interaction in) {
        if (in.taskId() == null || in.version() == null) {
            return Reply.of("That button is not an Orqix action.");
        }
        try {
            return tx.execute(s -> {
                RuntimeDtos.TaskDetail detail = taskService.getTask(person, workspaceId, in.taskId());
                RuntimeDtos.AllowedTaskActions allowed = detail.allowedActions();
                RuntimeDtos.ActionAvailability can = allowed.decide().allowed() ? allowed.decide()
                        : "TASK_NOT_CLAIMED_BY_CALLER".equals(allowed.decide().reasonCode()) ? allowed.claim() : allowed.decide();
                if (!can.allowed()) {
                    throw new ApiException(ProblemCode.valueOf(can.reasonCode()));
                }
                if (detail.version() != in.version()) {
                    throw new ApiException(ProblemCode.TASK_VERSION_CONFLICT, "The task changed since this card was sent.");
                }
                if ("reject".equals(in.action()) && detail.form().rejectReasonRequired() && blank(in.reason())) {
                    return Reply.of("Type a reason in the box, then press Reject again.");
                }
                UUID id = Ids.newId();
                Instant now = clock.instant();
                jdbc.sql("""
                        INSERT INTO task_action_confirmation (id, workspace_id, task_id, subject_id, channel, action,
                                                              expected_version, reason, status, created_at, expires_at)
                        VALUES (:id, :ws, :task, :subject, :channel, :action, :version, :reason, 'pending', :now, :expires)
                        """).param("id", id).param("ws", workspaceId).param("task", in.taskId())
                        .param("subject", person.subjectId()).param("channel", platform).param("action", in.action())
                        .param("version", in.version()).param("reason", blank(in.reason()) ? null : in.reason().strip())
                        .param("now", Sql.ts(now)).param("expires", Sql.ts(now.plus(CONFIRMATION_VALIDITY))).update();
                used(workspaceId, person.subjectId(), in.taskId(), platform, in.action(), "confirmationRequired", null, id, null);
                String value = json.write(json.object().put("confirmationId", id.toString()));
                return new Reply("Confirm: " + in.action() + " \"" + detail.label() + "\" of request "
                        + detail.runReference() + "? This confirmation expires in 5 minutes.",
                        List.of(new String[] {"Confirm " + in.action(), "confirm", value}, new String[] {"Cancel", "cancel", value}));
            });
        } catch (ApiException e) {
            used(workspaceId, person.subjectId(), in.taskId(), platform, in.action(), "refused", null, null, e.code().name());
            return refused(e);
        }
    }

    private record Pending(String taskId, UUID subject, String action, int version, String reason, String status,
            Instant expiresAt) {
    }

    private Reply confirm(UUID workspaceId, String platform, Caller person, Interaction in) {
        if (in.confirmationId() == null) {
            return Reply.of("That button is not an Orqix action.");
        }
        String[] task = new String[1];
        String[] action = new String[1];
        try {
            return tx.execute(s -> {
                Pending p = lockConfirmation(workspaceId, in.confirmationId(), person);
                task[0] = p.taskId();
                action[0] = p.action();
                if ("confirmed".equals(p.status())) {
                    used(workspaceId, person.subjectId(), p.taskId(), platform, p.action(), "replayed", null, in.confirmationId(), null);
                    return Reply.of("Already done: you " + ("approve".equals(p.action()) ? "approved" : "rejected") + " this task.");
                }
                if (!"pending".equals(p.status()) || !clock.instant().isBefore(p.expiresAt())) {
                    jdbc.sql("UPDATE task_action_confirmation SET status = 'expired', reason = NULL WHERE id = :id AND status = 'pending'")
                            .param("id", in.confirmationId()).update();
                    used(workspaceId, person.subjectId(), p.taskId(), platform, p.action(), "expired", null, in.confirmationId(), null);
                    return Reply.of("This confirmation is no longer valid. Press the button on the task card again.");
                }
                decide(person, workspaceId, p.taskId(), p.version(), p.action(), p.reason(), null,
                        "task-chat:" + in.confirmationId());
                jdbc.sql("UPDATE task_action_confirmation SET status = 'confirmed', reason = NULL, decided_at = :now WHERE id = :id")
                        .param("now", Sql.ts(clock.instant())).param("id", in.confirmationId()).update();
                used(workspaceId, person.subjectId(), p.taskId(), platform, p.action(), "done", null, in.confirmationId(), null);
                return Reply.of("Done: you " + ("approve".equals(p.action()) ? "approved" : "rejected") + " the task.");
            });
        } catch (ApiException e) {
            if (e.code() != ProblemCode.NOT_FOUND) {
                tx.executeWithoutResult(s -> jdbc.sql("""
                        UPDATE task_action_confirmation SET status = 'failed', error_code = :code, reason = NULL
                        WHERE id = :id AND status = 'pending'
                        """).param("code", e.code().name()).param("id", in.confirmationId()).update());
            }
            used(workspaceId, person.subjectId(), task[0], platform, action[0] == null ? "confirm" : action[0], "refused",
                    null, in.confirmationId(), e.code().name());
            return refused(e);
        }
    }

    private Reply cancel(UUID workspaceId, String platform, Caller person, Interaction in) {
        if (in.confirmationId() == null) {
            return Reply.of("Nothing was done.");
        }
        try {
            tx.executeWithoutResult(s -> {
                Pending p = lockConfirmation(workspaceId, in.confirmationId(), person);
                jdbc.sql("""
                        UPDATE task_action_confirmation SET status = 'failed', error_code = 'DECLINED', reason = NULL
                        WHERE id = :id AND status = 'pending'
                        """).param("id", in.confirmationId()).update();
                used(workspaceId, person.subjectId(), p.taskId(), platform, p.action(), "declined", null, in.confirmationId(), null);
            });
        } catch (ApiException e) {
            return refused(e);
        }
        return Reply.of("Nothing was done.");
    }

    /** The person's own confirmation in this workspace, locked; anyone else's answers like an unknown one. */
    private Pending lockConfirmation(UUID workspaceId, UUID id, Caller person) {
        Pending p = jdbc.sql("""
                SELECT task_id, subject_id, action, expected_version, reason, status, expires_at
                FROM task_action_confirmation WHERE workspace_id = :ws AND id = :id FOR UPDATE
                """).param("ws", workspaceId).param("id", id)
                .query((rs, n) -> new Pending(rs.getString("task_id"), Sql.uuid(rs, "subject_id"), rs.getString("action"),
                        rs.getInt("expected_version"), rs.getString("reason"), rs.getString("status"),
                        Sql.instant(rs, "expires_at")))
                .optional().orElse(null);
        if (p == null || !p.subject().equals(person.subjectId())) {
            throw ApiException.notFound();
        }
        return p;
    }

    private static Reply refused(ApiException e) {
        String why = switch (e.code()) {
            case NOT_FOUND -> "This task is not available to you.";
            case NOT_ELIGIBLE -> "You are not eligible for this task.";
            case SELF_APPROVAL_DENIED -> "You cannot approve your own request.";
            case SEPARATION_OF_DUTIES -> "You decided an earlier step of this request, so another person must decide this one.";
            case TASK_ALREADY_CLAIMED -> "Someone else already claimed this task.";
            case TASK_NOT_OPEN -> "This task is no longer open.";
            case TASK_VERSION_CONFLICT -> "The task changed since this card was sent. Open it in Orqix to see its current state.";
            case RUN_SUSPENDED -> "The request is suspended.";
            case REASON_REQUIRED -> "Give a reason to reject.";
            case FORBIDDEN -> "You are not allowed to do this.";
            default -> "Orqix refused this (" + e.code().name() + "). Open the task in Orqix.";
        };
        return Reply.of("Nothing was done: " + why);
    }

    private String channelSecret(UUID workspaceId, String channel) {
        TaskActionSettings.ChannelView view = settings.effective(workspaceId).channel(channel)
                .orElseThrow(ApiException::notFound);
        return secrets.resolve(view.secretRef(), workspaceId).orElseThrow(() -> new ApiException(
                ProblemCode.DEPENDENCY_UNAVAILABLE, "The " + channel + " signing secret is not available."));
    }

    // ------------------------------------------------------------------ helpers

    /** Records an action in its own transaction (a refused action rolls back everything else). */
    private void used(UUID workspaceId, UUID person, String taskId, String channel, String action, String outcome,
            UUID linkId, UUID confirmationId, String errorCode) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("taskId", taskId);
        metadata.put("channel", channel);
        metadata.put("action", action);
        metadata.put("outcome", outcome);
        metadata.put("linkId", linkId == null ? null : linkId.toString());
        metadata.put("confirmationId", confirmationId == null ? null : confirmationId.toString());
        metadata.put("errorCode", errorCode);
        audit.writeSeparately(new AuditWriter.Entry(workspaceId, person == null ? AuditWriter.ActorType.SYSTEM
                : AuditWriter.ActorType.USER, person, "task.action-used", "task", taskId == null ? "unknown" : taskId,
                "refused".equals(outcome) ? AuditWriter.Outcome.DENIED : AuditWriter.Outcome.SUCCESS, metadata));
    }

    private JsonNode safeParse(String text) {
        try {
            JsonNode node = json.parse(text);
            return node.isObject() ? node : json.object();
        } catch (RuntimeException e) {
            return json.object();
        }
    }

    private static UUID uuid(String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** For tests: the Slack signature of a body. */
    public static String slackSignature(String secret, long timestamp, byte[] body) {
        return "v0=" + HexFormat.of().formatHex(hmac(secret.getBytes(StandardCharsets.UTF_8),
                concat(("v0:" + timestamp + ":").getBytes(StandardCharsets.UTF_8), body)));
    }

    /** For tests: the Teams outgoing-webhook authorization header of a body. */
    public static String teamsAuthorization(String base64Secret, byte[] body) {
        return "HMAC " + Base64.getEncoder().encodeToString(hmac(Base64.getDecoder().decode(base64Secret), body));
    }
}
