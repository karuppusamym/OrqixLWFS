package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.SubjectService;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Actionable task messages of CG-T01 (docs/build/S141). When a task is created (or moved by an escalation level) and
 * the workspace has the feature on, one message per person and enabled channel is queued: to the assignee when the
 * task has one, otherwise to the eligible people of its queue (at most {@value #MAX_CANDIDATES}). The worker sends a
 * queued message only after rechecking, at that moment, that the task is open, the person is still eligible (and still
 * the assignee or the queue is still open), the channel is on and the person can be reached there (email domain
 * approved, chat identity mapped). Every message carries only the task's label, workflow name, request reference and
 * due date, never request values. Email gets a signed action link; chat cards carry buttons whose presses are
 * authenticated by the platform signature and the mapped chat identity.
 */
@Service
@Profile("!migrate")
public class TaskActionOutbox {

    static final int MAX_CANDIDATES = 25;

    private final JdbcClient jdbc;
    private final TaskStore tasks;
    private final TaskService taskService;
    private final RunStore runs;
    private final TaskActionSettings settings;
    private final TaskActionLinks links;
    private final TaskActionTransport transport;
    private final SubjectService subjects;
    private final AuditWriter audit;
    private final Json json;
    private final Clock clock;
    private final String appBaseUrl;

    public TaskActionOutbox(JdbcClient jdbc, TaskStore tasks, @org.springframework.context.annotation.Lazy TaskService taskService,
            RunStore runs, TaskActionSettings settings, TaskActionLinks links, TaskActionTransport transport,
            SubjectService subjects, AuditWriter audit, Json json, Clock clock,
            @Value("${orqix.task-actions.app-base-url:http://localhost:5173}") String appBaseUrl) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.taskService = taskService;
        this.runs = runs;
        this.settings = settings;
        this.links = links;
        this.transport = transport;
        this.subjects = subjects;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.appBaseUrl = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
    }

    /** Queues the messages of a new task (nothing when the feature is off or no channel is on). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int enqueueOnCreate(UUID workspaceId, String taskId) {
        return enqueueForCandidates(workspaceId, taskId, "created");
    }

    /** Queues messages for the task's assignee, or the eligible people of its queue, under {@code reason}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int enqueueForCandidates(UUID workspaceId, String taskId, String reason) {
        TaskActionSettings.Effective effective = settings.effective(workspaceId);
        List<String> channels = TaskActionSettings.CHANNELS.stream().filter(c -> effective.channel(c).isPresent()).toList();
        if (channels.isEmpty()) {
            return 0;
        }
        TaskStore.TaskRow task = tasks.find(workspaceId, taskId).orElse(null);
        if (task == null || !task.isOpen()) {
            return 0;
        }
        List<UUID> people = task.assignee() != null ? List.of(task.assignee())
                : taskService.groupMembers(workspaceId, task.candidateGroupKey(), TaskDispatcher.MAX_CANDIDATES).stream()
                        .filter(s -> taskService.eligibleSubject(workspaceId, task, s.id())).limit(MAX_CANDIDATES)
                        .map(SubjectService.Subject::id).toList();
        int queued = 0;
        Instant now = clock.instant();
        for (UUID person : people) {
            for (String channel : channels) {
                queued += jdbc.sql("""
                        INSERT INTO task_action_message (id, workspace_id, task_id, subject_id, channel, reason, created_at)
                        VALUES (:id, :ws, :task, :subject, :channel, :reason, :now) ON CONFLICT DO NOTHING
                        """).param("id", Ids.newId()).param("ws", workspaceId).param("task", taskId).param("subject", person)
                        .param("channel", channel).param("reason", reason).param("now", Sql.ts(now)).update();
            }
        }
        return queued;
    }

    @Scheduled(fixedDelayString = "${orqix.task-actions.poll-delay:15s}")
    @Transactional
    public void dispatch() {
        sendQueued();
    }

    private record Queued(UUID id, UUID workspace, String task, UUID subject, String channel, String reason) {
    }

    /** Sends (or skips with a reason) the queued messages; returns how many were settled. */
    @Transactional
    public int sendQueued() {
        List<Queued> queued = jdbc.sql("""
                SELECT id, workspace_id, task_id, subject_id, channel, reason FROM task_action_message
                WHERE status = 'queued' ORDER BY created_at, id LIMIT 50 FOR UPDATE SKIP LOCKED
                """).query((rs, n) -> new Queued(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), rs.getString("task_id"),
                        Sql.uuid(rs, "subject_id"), rs.getString("channel"), rs.getString("reason"))).list();
        int settled = 0;
        for (Queued q : queued) {
            TaskStore.TaskRow task = tasks.find(q.workspace(), q.task()).orElse(null);
            String skip = skipCode(q, task);
            if (skip != null) {
                jdbc.sql("UPDATE task_action_message SET status = 'skipped', skip_code = :code, sent_at = :now WHERE id = :id")
                        .param("code", skip).param("now", Sql.ts(clock.instant())).param("id", q.id()).update();
                settled++;
                continue;
            }
            TaskActionSettings.Effective effective = settings.effective(q.workspace());
            String recipient = recipient(q, effective);
            TaskActionLinks.Issued link = links.issue(q.workspace(), q.task(), q.subject(), q.channel(),
                    Duration.ofHours(effective.linkHours()));
            ObjectNode message = render(q, task, link);
            transport.deliver(q.channel(), q.workspace(), recipient, message);
            jdbc.sql("UPDATE task_action_message SET status = 'sent', link_id = :link, sent_at = :now WHERE id = :id")
                    .param("link", link.id()).param("now", Sql.ts(clock.instant()))
                    .param("id", q.id()).update();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("taskId", q.task());
            metadata.put("channel", q.channel());
            metadata.put("reason", q.reason());
            metadata.put("linkId", link.id().toString());
            audit.write(new AuditWriter.Entry(q.workspace(), AuditWriter.ActorType.SYSTEM, null, "task.action-sent", "task",
                    q.task(), AuditWriter.Outcome.SUCCESS, metadata));
            settled++;
        }
        return settled;
    }

    /** Why the message must not be sent now, or null. Every check is made against current state. */
    private String skipCode(Queued q, TaskStore.TaskRow task) {
        if (task == null || !task.isOpen()) {
            return "TASK_NOT_OPEN";
        }
        if (task.assignee() != null && !task.assignee().equals(q.subject())) {
            return "TASK_ALREADY_CLAIMED";
        }
        if (!taskService.eligibleSubject(q.workspace(), task, q.subject())) {
            return "NOT_ELIGIBLE";
        }
        TaskActionSettings.Effective effective = settings.effective(q.workspace());
        if (effective.channel(q.channel()).isEmpty()) {
            return "CHANNEL_OFF";
        }
        return recipient(q, effective) == null ? ("email".equals(q.channel()) ? "RECIPIENT_NOT_ALLOWED" : "NO_CHAT_IDENTITY") : null;
    }

    private String recipient(Queued q, TaskActionSettings.Effective effective) {
        if ("email".equals(q.channel())) {
            String email = subjects.find(q.subject()).map(SubjectService.Subject::email).orElse(null);
            if (email == null || email.indexOf('@') < 0) {
                return null;
            }
            String domain = email.substring(email.lastIndexOf('@') + 1).toLowerCase(java.util.Locale.ROOT);
            boolean approved = false;
            for (var d : effective.channel("email").orElseThrow().config().path("recipientDomains")) {
                approved |= d.asString().equals(domain);
            }
            return approved ? email : null;
        }
        return settings.chatUser(q.workspace(), q.channel(), q.subject()).orElse(null);
    }

    private ObjectNode render(Queued q, TaskStore.TaskRow task, TaskActionLinks.Issued link) {
        RunStore.RunRow run = runs.find(q.workspace(), task.runId()).orElseThrow();
        String reference = run.reference();
        String due = task.dueAt() == null ? null : task.dueAt().toString();
        String open = appBaseUrl + "/act/" + link.token();
        String title = "Approval needed: " + task.label();
        String facts = "Request " + reference + (due == null ? "" : " · due " + due);
        ObjectNode message = json.object();
        message.put("taskId", q.task()).put("reason", q.reason());
        switch (q.channel()) {
            case "email" -> {
                message.put("subject", title);
                message.put("text", title + "\n" + facts + "\n\nOpen, claim or decide (you confirm before anything"
                        + " happens): " + open + "\nThe link is for you only and expires at " + link.expiresAt() + ".");
                message.put("actionUrl", open);
            }
            case "teams" -> {
                ObjectNode card = message.putObject("card");
                card.put("type", "AdaptiveCard").put("version", "1.5");
                ArrayNode body = card.putArray("body");
                body.addObject().put("type", "TextBlock").put("text", title).put("weight", "Bolder").put("wrap", true);
                body.addObject().put("type", "TextBlock").put("text", facts).put("wrap", true);
                body.addObject().put("type", "Input.Text").put("id", "reason").put("placeholder", "Reason (needed to reject)")
                        .put("isMultiline", true).put("maxLength", 1000);
                ArrayNode actions = card.putArray("actions");
                actions.addObject().put("type", "Action.OpenUrl").put("title", "Open").put("url", open);
                for (String action : List.of("claim", "approve", "reject")) {
                    ObjectNode data = actions.addObject().put("type", "Action.Submit")
                            .put("title", Character.toUpperCase(action.charAt(0)) + action.substring(1)).putObject("data");
                    data.put("orqixAction", action).put("taskId", q.task()).put("version", task.version());
                }
            }
            default -> {
                message.put("text", title);
                ArrayNode blocks = message.putArray("blocks");
                blocks.addObject().put("type", "section").putObject("text").put("type", "mrkdwn")
                        .put("text", "*" + title + "*\n" + facts);
                ObjectNode input = blocks.addObject().put("type", "input").put("block_id", "orqix-reason").put("optional", true);
                input.putObject("label").put("type", "plain_text").put("text", "Reason (needed to reject)");
                input.putObject("element").put("type", "plain_text_input").put("action_id", "reason").put("multiline", true);
                ArrayNode elements = blocks.addObject().put("type", "actions").putArray("elements");
                elements.addObject().put("type", "button").put("action_id", "orqix-open").put("url", open)
                        .putObject("text").put("type", "plain_text").put("text", "Open");
                for (String action : List.of("claim", "approve", "reject")) {
                    ObjectNode button = elements.addObject().put("type", "button").put("action_id", "orqix-" + action)
                            .put("value", json.write(json.object().put("taskId", q.task()).put("version", task.version())));
                    button.putObject("text").put("type", "plain_text")
                            .put("text", Character.toUpperCase(action.charAt(0)) + action.substring(1));
                }
            }
        }
        return message;
    }
}
