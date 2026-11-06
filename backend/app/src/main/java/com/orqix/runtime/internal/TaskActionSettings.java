package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.SubjectService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.ChannelSecrets;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Workspace settings of actionable tasks (CG-T01, docs/build/S141): on or off (off until switched on), link validity,
 * channels, and the chat identities mapped to people. Managed with {@code workspace.manage-access}, read on every use
 * without a cache, audited.
 */
@Service
public class TaskActionSettings {

    public static final List<String> CHANNELS = List.of("email", "teams", "slack");
    private static final Pattern DOMAIN = Pattern.compile("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[a-z0-9.-]{1,253}$");

    public record ChannelView(String channel, boolean enabled, JsonNode config, String secretRef) {
    }

    public record SettingsView(boolean enabled, int linkHours, int version, List<ChannelView> channels,
            SubjectService.SubjectRef updatedBy, Instant updatedAt) {
    }

    public record ChannelRequest(@NotNull @jakarta.validation.constraints.Pattern(regexp = "email|teams|slack") String channel,
            boolean enabled, JsonNode config,
            @jakarta.validation.constraints.Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}$") String secretRef) {
    }

    public record SettingsRequest(boolean enabled, @NotNull @Min(1) @Max(168) Integer linkHours,
            @NotNull @Min(0) Integer expectedVersion, @NotNull @Size(max = 3) List<@Valid ChannelRequest> channels) {
    }

    public record IdentityView(String platform, String platformUserId, SubjectService.SubjectRef person,
            SubjectService.SubjectRef linkedBy, Instant linkedAt) {
    }

    public record IdentityList(List<IdentityView> items) {
    }

    public record IdentityRequest(@NotNull @jakarta.validation.constraints.Pattern(regexp = "teams|slack") String platform,
            @NotBlank @Size(max = 128) @jakarta.validation.constraints.Pattern(regexp = "^[A-Za-z0-9:._@-]{1,128}$") String platformUserId,
            @NotNull UUID subjectId) {
    }

    /** What the runtime needs from the settings at the moment of use. */
    record Effective(boolean enabled, int linkHours, Map<String, ChannelView> channels) {
        Optional<ChannelView> channel(String name) {
            return enabled ? Optional.ofNullable(channels.get(name)).filter(ChannelView::enabled) : Optional.empty();
        }
    }

    private final PolicyService policy;
    private final SubjectService subjects;
    private final AuditWriter audit;
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;

    public TaskActionSettings(PolicyService policy, SubjectService subjects, AuditWriter audit, JdbcClient jdbc,
            Json json, Clock clock) {
        this.policy = policy;
        this.subjects = subjects;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ administration

    @Transactional(readOnly = true)
    public SettingsView get(Caller caller, UUID workspaceId) {
        manager(caller, workspaceId, "getTaskActionSettings", false);
        return view(workspaceId);
    }

    @Transactional
    public SettingsView update(Caller caller, UUID workspaceId, SettingsRequest request) {
        manager(caller, workspaceId, "updateTaskActionSettings", true);
        Integer current = jdbc.sql("SELECT version FROM task_action_setting WHERE workspace_id = :ws FOR UPDATE")
                .param("ws", workspaceId).query(Integer.class).optional().orElse(0);
        if (current.intValue() != request.expectedVersion()) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT, "The settings changed since you loaded them.")
                    .with("currentVersion", current);
        }
        List<ApiException.FieldError> errors = new ArrayList<>();
        Map<String, ObjectNode> configs = new LinkedHashMap<>();
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < request.channels().size(); i++) {
            ChannelRequest c = request.channels().get(i);
            String at = "/channels/" + i;
            if (!seen.add(c.channel())) {
                errors.add(new ApiException.FieldError(at + "/channel", "uniqueItems", "Each channel appears once."));
                continue;
            }
            configs.put(c.channel(), config(c, at, errors));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Some channel settings are not valid.").withFieldErrors(errors);
        }
        Instant now = clock.instant();
        int version = current + 1;
        if (current == 0) {
            jdbc.sql("""
                    INSERT INTO task_action_setting (workspace_id, enabled, link_hours, version, updated_by, updated_at)
                    VALUES (:ws, :enabled, :hours, 1, :by, :now)
                    """).param("ws", workspaceId).param("enabled", request.enabled()).param("hours", request.linkHours())
                    .param("by", caller.subjectId()).param("now", Sql.ts(now)).update();
        } else {
            jdbc.sql("""
                    UPDATE task_action_setting SET enabled = :enabled, link_hours = :hours, version = :version,
                           updated_by = :by, updated_at = :now WHERE workspace_id = :ws
                    """).param("ws", workspaceId).param("enabled", request.enabled()).param("hours", request.linkHours())
                    .param("version", version).param("by", caller.subjectId()).param("now", Sql.ts(now)).update();
        }
        jdbc.sql("DELETE FROM task_action_channel WHERE workspace_id = :ws").param("ws", workspaceId).update();
        for (ChannelRequest c : request.channels()) {
            jdbc.sql("""
                    INSERT INTO task_action_channel (workspace_id, channel, enabled, config, secret_ref)
                    VALUES (:ws, :channel, :enabled, CAST(:config AS jsonb), :ref)
                    """).param("ws", workspaceId).param("channel", c.channel()).param("enabled", c.enabled())
                    .param("config", json.write(configs.get(c.channel())))
                    .param("ref", "email".equals(c.channel()) ? null : c.secretRef()).update();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("enabled", request.enabled());
        metadata.put("linkHours", request.linkHours());
        metadata.put("channels", request.channels().stream().filter(ChannelRequest::enabled).map(ChannelRequest::channel).toList());
        metadata.put("version", version);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "task.action-settings-updated", "taskActionSettings", workspaceId.toString(), AuditWriter.Outcome.SUCCESS,
                metadata));
        return view(workspaceId);
    }

    private ObjectNode config(ChannelRequest c, String at, List<ApiException.FieldError> errors) {
        ObjectNode out = json.object();
        JsonNode in = c.config() == null ? json.object() : c.config();
        if ("email".equals(c.channel())) {
            if (c.secretRef() != null) {
                errors.add(new ApiException.FieldError(at + "/secretRef", "not", "Email needs no secret reference."));
            }
            for (String name : in.propertyNames()) {
                if (!Set.of("sender", "recipientDomains").contains(name)) {
                    errors.add(new ApiException.FieldError(at + "/config/" + name, "additionalProperties", "Unknown setting."));
                }
            }
            String sender = in.path("sender").asString("").trim().toLowerCase(java.util.Locale.ROOT);
            if (!EMAIL.matcher(sender).matches()) {
                errors.add(new ApiException.FieldError(at + "/config/sender", "format", "Give the sender address."));
            }
            JsonNode domains = in.path("recipientDomains");
            var list = out.put("sender", sender).putArray("recipientDomains");
            if (!domains.isArray() || domains.isEmpty() || domains.size() > 20) {
                errors.add(new ApiException.FieldError(at + "/config/recipientDomains", "minItems",
                        "List 1 to 20 exact recipient domains."));
            } else {
                for (JsonNode d : domains) {
                    String domain = d.asString("").trim().toLowerCase(java.util.Locale.ROOT);
                    if (!DOMAIN.matcher(domain).matches()) {
                        errors.add(new ApiException.FieldError(at + "/config/recipientDomains", "format",
                                "Use exact domains such as example.com; wildcards are not allowed."));
                    } else {
                        list.add(domain);
                    }
                }
            }
        } else {
            if (!in.isEmpty()) {
                errors.add(new ApiException.FieldError(at + "/config", "maxProperties",
                        "Teams and Slack take only the secret that signs their interactions."));
            }
            if (c.secretRef() == null) {
                errors.add(new ApiException.FieldError(at + "/secretRef", "required",
                        "Name the secret that signs interactions from " + c.channel() + "."));
            } else {
                try {
                    ChannelSecrets.refuseReserved(c.secretRef(), at + "/secretRef");
                } catch (ApiException e) {
                    errors.add(new ApiException.FieldError(at + "/secretRef", "not", "This secret namespace is reserved."));
                }
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public IdentityList identities(Caller caller, UUID workspaceId) {
        manager(caller, workspaceId, "listChatIdentities", false);
        List<IdentityRow> rows = jdbc.sql("""
                SELECT platform, platform_user_id, subject_id, linked_by, linked_at FROM chat_identity
                WHERE workspace_id = :ws ORDER BY platform, platform_user_id LIMIT 500
                """).param("ws", workspaceId)
                .query((rs, n) -> new IdentityRow(rs.getString("platform"), rs.getString("platform_user_id"),
                        Sql.uuid(rs, "subject_id"), Sql.uuid(rs, "linked_by"), Sql.instant(rs, "linked_at")))
                .list();
        List<UUID> ids = new ArrayList<>();
        rows.forEach(r -> {
            ids.add(r.subject());
            ids.add(r.by());
        });
        Map<UUID, String> names = subjects.displayNames(ids);
        return new IdentityList(rows.stream().map(r -> new IdentityView(r.platform(), r.user(), subjects.ref(r.subject(), names),
                subjects.ref(r.by(), names), r.at())).toList());
    }

    private record IdentityRow(String platform, String user, UUID subject, UUID by, Instant at) {
    }

    @Transactional
    public IdentityView link(Caller caller, UUID workspaceId, IdentityRequest request) {
        manager(caller, workspaceId, "linkChatIdentity", true);
        SubjectService.Subject person = subjects.find(request.subjectId()).filter(s -> !s.disabled())
                .orElseThrow(() -> new ApiException(ProblemCode.VALIDATION_FAILED, "Choose an active person.")
                        .withFieldErrors(List.of(new ApiException.FieldError("/subjectId", "enum", "Unknown person"))));
        Instant now = clock.instant();
        // One platform user per person and one person per platform user: a new mapping replaces both old ones.
        jdbc.sql("DELETE FROM chat_identity WHERE workspace_id = :ws AND platform = :p AND (platform_user_id = :u OR subject_id = :s)")
                .param("ws", workspaceId).param("p", request.platform()).param("u", request.platformUserId())
                .param("s", person.id()).update();
        jdbc.sql("""
                INSERT INTO chat_identity (workspace_id, platform, platform_user_id, subject_id, linked_by, linked_at)
                VALUES (:ws, :p, :u, :s, :by, :now)
                """).param("ws", workspaceId).param("p", request.platform()).param("u", request.platformUserId())
                .param("s", person.id()).param("by", caller.subjectId()).param("now", Sql.ts(now)).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "task.chat-identity-linked", "chatIdentity", request.platform() + ":" + request.platformUserId(),
                AuditWriter.Outcome.SUCCESS, Map.of("platform", request.platform(), "subjectId", person.id().toString())));
        Map<UUID, String> names = subjects.displayNames(List.of(person.id(), caller.subjectId()));
        return new IdentityView(request.platform(), request.platformUserId(), subjects.ref(person.id(), names),
                subjects.ref(caller.subjectId(), names), now);
    }

    @Transactional
    public void unlink(Caller caller, UUID workspaceId, String platform, String platformUserId) {
        manager(caller, workspaceId, "unlinkChatIdentity", true);
        UUID subject = jdbc.sql("""
                DELETE FROM chat_identity WHERE workspace_id = :ws AND platform = :p AND platform_user_id = :u
                RETURNING subject_id
                """).param("ws", workspaceId).param("p", platform).param("u", platformUserId)
                .query(UUID.class).optional().orElseThrow(ApiException::notFound);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "task.chat-identity-unlinked", "chatIdentity", platform + ":" + platformUserId,
                AuditWriter.Outcome.SUCCESS, Map.of("platform", platform, "subjectId", subject.toString())));
    }

    public record MessageView(UUID id, String taskId, SubjectService.SubjectRef person, String channel, String reason,
            String status, String skipCode, Instant createdAt, Instant sentAt) {
    }

    public record MessageList(List<MessageView> items) {
    }

    /** The latest deliveries (newest first): who, which channel, sent or why skipped. Never message content. */
    @Transactional(readOnly = true)
    public MessageList messages(Caller caller, UUID workspaceId, Integer limit) {
        manager(caller, workspaceId, "listTaskActionMessages", false);
        int size = limit == null ? 50 : Math.max(1, Math.min(200, limit));
        record Row(UUID id, String task, UUID subject, String channel, String reason, String status, String skip,
                Instant created, Instant sent) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT id, task_id, subject_id, channel, reason, status, skip_code, created_at, sent_at
                FROM task_action_message WHERE workspace_id = :ws ORDER BY created_at DESC, id DESC LIMIT :limit
                """).param("ws", workspaceId).param("limit", size)
                .query((rs, n) -> new Row(Sql.uuid(rs, "id"), rs.getString("task_id"), Sql.uuid(rs, "subject_id"),
                        rs.getString("channel"), rs.getString("reason"), rs.getString("status"), rs.getString("skip_code"),
                        Sql.instant(rs, "created_at"), Sql.instant(rs, "sent_at")))
                .list();
        Map<UUID, String> names = subjects.displayNames(rows.stream().map(Row::subject).toList());
        return new MessageList(rows.stream().map(r -> new MessageView(r.id(), r.task(), subjects.ref(r.subject(), names),
                r.channel(), r.reason(), r.status(), r.skip(), r.created(), r.sent())).toList());
    }

    // ------------------------------------------------------------------ runtime reads

    /** The settings as they are now (no row: off). */
    @Transactional(readOnly = true)
    Effective effective(UUID workspaceId) {
        var head = jdbc.sql("SELECT enabled, link_hours FROM task_action_setting WHERE workspace_id = :ws")
                .param("ws", workspaceId).query((rs, n) -> new int[] {rs.getBoolean("enabled") ? 1 : 0, rs.getInt("link_hours")})
                .optional().orElse(null);
        if (head == null) {
            return new Effective(false, 48, Map.of());
        }
        Map<String, ChannelView> channels = new LinkedHashMap<>();
        channels(workspaceId).forEach(c -> channels.put(c.channel(), c));
        return new Effective(head[0] == 1, head[1], channels);
    }

    /** The person mapped to a chat user, or empty. */
    @Transactional(readOnly = true)
    Optional<UUID> person(UUID workspaceId, String platform, String platformUserId) {
        return jdbc.sql("SELECT subject_id FROM chat_identity WHERE workspace_id = :ws AND platform = :p AND platform_user_id = :u")
                .param("ws", workspaceId).param("p", platform).param("u", platformUserId).query(UUID.class).optional();
    }

    /** The chat user of a person, or empty. */
    @Transactional(readOnly = true)
    Optional<String> chatUser(UUID workspaceId, String platform, UUID subjectId) {
        return jdbc.sql("SELECT platform_user_id FROM chat_identity WHERE workspace_id = :ws AND platform = :p AND subject_id = :s")
                .param("ws", workspaceId).param("p", platform).param("s", subjectId).query(String.class).optional();
    }

    private SettingsView view(UUID workspaceId) {
        record Head(boolean enabled, int hours, int version, UUID by, Instant at) {
        }
        Head head = jdbc.sql("SELECT enabled, link_hours, version, updated_by, updated_at FROM task_action_setting WHERE workspace_id = :ws")
                .param("ws", workspaceId).query((rs, n) -> new Head(rs.getBoolean("enabled"), rs.getInt("link_hours"),
                        rs.getInt("version"), Sql.uuid(rs, "updated_by"), Sql.instant(rs, "updated_at")))
                .optional().orElse(null);
        if (head == null) {
            return new SettingsView(false, 48, 0, List.of(), null, null);
        }
        return new SettingsView(head.enabled(), head.hours(), head.version(), channels(workspaceId),
                subjects.ref(head.by()), head.at());
    }

    private List<ChannelView> channels(UUID workspaceId) {
        return jdbc.sql("""
                SELECT channel, enabled, config::text AS config, secret_ref FROM task_action_channel
                WHERE workspace_id = :ws ORDER BY array_position(ARRAY['email','teams','slack'], channel)
                """).param("ws", workspaceId)
                .query((rs, n) -> new ChannelView(rs.getString("channel"), rs.getBoolean("enabled"),
                        json.parse(rs.getString("config")), rs.getString("secret_ref")))
                .list();
    }

    private WorkspaceAccess manager(Caller caller, UUID workspaceId, String operationId, boolean write) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.WORKSPACE_MANAGE_ACCESS, operationId, write);
        return access;
    }
}
