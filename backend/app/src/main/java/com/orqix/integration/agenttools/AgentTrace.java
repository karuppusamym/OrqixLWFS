package com.orqix.integration.agenttools;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Opt-in conversation trace (CG-M05, S135). The default is metadata only (S13): {@link #recorder} returns a recorder
 * that writes nothing unless the workspace enabled the trace. When enabled, the input, every model turn and every tool
 * call (arguments and result) are kept after redaction (built-in rules for e-mail addresses, long digit runs and
 * tokens, plus the workspace's field names and patterns), linked to the run and step, for the retention (default 7
 * days, at most 30); the hourly sweep deletes older rows. Recording never fails the agent.
 */
@Service
public class AgentTrace {

    private static final Logger log = LoggerFactory.getLogger(AgentTrace.class);

    static final int MAX_TEXT = 4000;
    static final int MAX_PATTERNS = 20;
    static final int MAX_FIELDS = 50;
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}");
    private static final Pattern DIGITS = Pattern.compile("\\b(?:\\d[ -]?){12,19}\\b");
    private static final Pattern TOKEN = Pattern.compile("(?i)\\b(?:bearer\\s+[A-Za-z0-9._~+/=-]{8,}|sk-[A-Za-z0-9_-]{8,}"
            + "|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,})");
    /** A quantified group that is itself quantified (catastrophic backtracking): refused for workspace patterns. */
    private static final Pattern NESTED_QUANTIFIER = Pattern.compile("\\([^)]*[+*}][^)]*\\)\\s*[+*{]");

    public record Settings(boolean enabled, int retentionDays, List<String> redactFields, List<String> redactPatterns,
            UUID changedBy, Instant changedAt, int version) {

        static final Settings OFF = new Settings(false, 7, List.of(), List.of(), null, null, 0);
    }

    public record SettingsRequest(@NotNull Boolean enabled, @Min(1) @Max(30) Integer retentionDays,
            @Size(max = MAX_FIELDS) List<@Size(min = 1, max = 64) String> redactFields,
            @Size(max = MAX_PATTERNS) List<@Size(min = 1, max = 200) String> redactPatterns,
            @Min(0) Integer expectedVersion) {
    }

    public record Event(int seq, String kind, String toolName, String model, String outcome, Long durationMs,
            BigDecimal costUsd, JsonNode content, Instant createdAt) {
    }

    public record Trace(UUID runId, String nodeId, boolean enabled, List<Event> events) {
    }

    /** Collects the events of one execution or turn and writes them in one independent transaction. */
    public interface Recorder {

        Recorder NONE = new Recorder() {
        };

        default boolean enabled() {
            return false;
        }

        default void record(String kind, String toolName, String model, String outcome, Long durationMs,
                BigDecimal costUsd, JsonNode content) {
        }

        default void flush() {
        }
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final TransactionTemplate independent;

    public AgentTrace(JdbcClient jdbc, Json json, Clock clock, PolicyService policy, AuditWriter audit,
            PlatformTransactionManager tx) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.policy = policy;
        this.audit = audit;
        this.independent = new TransactionTemplate(tx);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Settings settings(UUID workspaceId) {
        return jdbc.sql("""
                SELECT enabled, retention_days, redact_fields::text AS fields, redact_patterns::text AS patterns,
                    changed_by, changed_at, version
                FROM agent_trace_setting WHERE workspace_id = :ws
                """).param("ws", workspaceId)
                .query((rs, n) -> new Settings(rs.getBoolean("enabled"), rs.getInt("retention_days"),
                        strings(rs.getString("fields")), strings(rs.getString("patterns")), Sql.uuid(rs, "changed_by"),
                        Sql.instant(rs, "changed_at"), rs.getInt("version")))
                .optional().orElse(Settings.OFF);
    }

    /** A recorder for one agent step of a run; writes nothing when the trace is off or there is no run. */
    public Recorder recorder(UUID workspaceId, UUID runId, String nodeId, UUID effectId) {
        if (runId == null) {
            return Recorder.NONE;
        }
        Settings settings;
        try {
            settings = settings(workspaceId);
        } catch (RuntimeException e) {
            return Recorder.NONE;
        }
        if (!settings.enabled()) {
            return Recorder.NONE;
        }
        Redactor redactor = new Redactor(settings);
        Instant expires = clock.instant().plus(Duration.ofDays(settings.retentionDays()));
        List<Object[]> pending = new ArrayList<>();
        return new Recorder() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public void record(String kind, String toolName, String model, String outcome, Long durationMs,
                    BigDecimal costUsd, JsonNode content) {
                pending.add(new Object[] {kind, toolName, model, outcome, durationMs, costUsd,
                        content == null ? json.object() : redactor.redact(content)});
            }

            @Override
            public void flush() {
                if (pending.isEmpty()) {
                    return;
                }
                try {
                    independent.executeWithoutResult(status -> {
                        int seq = jdbc.sql("SELECT coalesce(max(seq), 0) FROM agent_trace_event WHERE workspace_id = :ws "
                                + "AND run_id = :run AND node_id = :node").param("ws", workspaceId).param("run", runId)
                                .param("node", nodeId).query(Integer.class).single();
                        for (Object[] e : pending) {
                            jdbc.sql("""
                                    INSERT INTO agent_trace_event (id, workspace_id, run_id, node_id, effect_id, seq, kind,
                                        tool_name, model, outcome, duration_ms, cost_usd, content, created_at, expires_at)
                                    VALUES (:id, :ws, :run, :node, :effect, :seq, :kind, :tool, :model, :outcome, :ms,
                                        :cost, CAST(:content AS jsonb), :at, :expires)
                                    """).param("id", Ids.newId()).param("ws", workspaceId).param("run", runId)
                                    .param("node", nodeId).param("effect", effectId).param("seq", ++seq)
                                    .param("kind", e[0]).param("tool", e[1]).param("model", e[2]).param("outcome", e[3])
                                    .param("ms", e[4]).param("cost", e[5]).param("content", json.write((JsonNode) e[6]))
                                    .param("at", Sql.ts(clock.instant())).param("expires", Sql.ts(expires)).update();
                        }
                    });
                } catch (RuntimeException ex) {
                    log.warn("Agent trace could not be written: {}", ex.getClass().getSimpleName());
                } finally {
                    pending.clear();
                }
            }
        };
    }

    /** Deletes expired trace rows; returns the count. */
    public int purge(Instant now) {
        return jdbc.sql("DELETE FROM agent_trace_event WHERE expires_at < :now").param("now", Sql.ts(now)).update();
    }

    // ------------------------------------------------------------------ API

    @Transactional(readOnly = true)
    public Settings getSettings(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.access(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.WORKSPACE_MANAGE_ACCESS, "getAgentTraceSettings", false);
        return settings(workspaceId);
    }

    @Transactional
    public Settings setSettings(Caller caller, UUID workspaceId, SettingsRequest request) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.WORKSPACE_MANAGE_ACCESS, "setAgentTraceSettings", true);
        List<String> fields = request.redactFields() == null ? List.of() : request.redactFields().stream()
                .map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
        List<String> patterns = request.redactPatterns() == null ? List.of() : request.redactPatterns();
        for (int i = 0; i < patterns.size(); i++) {
            String problem = patternProblem(patterns.get(i));
            if (problem != null) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, problem).withFieldErrors(List.of(
                        new ApiException.FieldError("/redactPatterns/" + i, "pattern", problem)));
            }
        }
        Settings current = settings(workspaceId);
        int expected = request.expectedVersion() == null ? 0 : request.expectedVersion();
        if (expected != current.version()) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT,
                    "The trace settings were changed by someone else. Reload and try again.");
        }
        int retention = request.retentionDays() == null ? 7 : request.retentionDays();
        jdbc.sql("""
                INSERT INTO agent_trace_setting (workspace_id, enabled, retention_days, redact_fields, redact_patterns,
                    changed_by, changed_at)
                VALUES (:ws, :enabled, :days, CAST(:fields AS jsonb), CAST(:patterns AS jsonb), :by, :at)
                ON CONFLICT (workspace_id) DO UPDATE SET enabled = EXCLUDED.enabled,
                    retention_days = EXCLUDED.retention_days, redact_fields = EXCLUDED.redact_fields,
                    redact_patterns = EXCLUDED.redact_patterns, changed_by = EXCLUDED.changed_by,
                    changed_at = EXCLUDED.changed_at, version = agent_trace_setting.version + 1
                """).param("ws", workspaceId).param("enabled", request.enabled()).param("days", retention)
                .param("fields", json.write(fields)).param("patterns", json.write(patterns))
                .param("by", access.subjectId()).param("at", Sql.ts(clock.instant())).update();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("enabled", request.enabled());
        metadata.put("retentionDays", retention);
        metadata.put("redactFields", fields.size());
        metadata.put("redactPatterns", patterns.size());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, access.subjectId(),
                "agent.trace.settings-changed", "workspace", workspaceId.toString(), AuditWriter.Outcome.SUCCESS,
                metadata));
        return settings(workspaceId);
    }

    @Transactional(readOnly = true)
    public Trace trace(Caller caller, UUID workspaceId, UUID runId, String nodeId) {
        WorkspaceAccess access = policy.access(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.RUN_OPERATE, "getAgentTrace", false);
        int runs = jdbc.sql("SELECT count(*) FROM workflow_run WHERE workspace_id = :ws AND id = :id")
                .param("ws", workspaceId).param("id", runId).query(Integer.class).single();
        if (runs == 0) {
            throw ApiException.notFound();
        }
        List<Event> events = jdbc.sql("""
                SELECT seq, kind, tool_name, model, outcome, duration_ms, cost_usd, content::text AS content, created_at
                FROM agent_trace_event WHERE workspace_id = :ws AND run_id = :run AND (CAST(:node AS text) IS NULL OR node_id = :node)
                  AND expires_at >= now()
                ORDER BY node_id, seq LIMIT 500
                """).param("ws", workspaceId).param("run", runId).param("node", nodeId)
                .query((rs, n) -> new Event(rs.getInt("seq"), rs.getString("kind"), rs.getString("tool_name"),
                        rs.getString("model"), rs.getString("outcome"), (Long) rs.getObject("duration_ms", Long.class),
                        rs.getBigDecimal("cost_usd"), json.parse(rs.getString("content")), Sql.instant(rs, "created_at")))
                .list();
        return new Trace(runId, nodeId, settings(workspaceId).enabled(), events);
    }

    // ------------------------------------------------------------------ redaction

    static String patternProblem(String pattern) {
        if (pattern == null || pattern.isBlank() || pattern.length() > 200) {
            return "A pattern has 1 to 200 characters.";
        }
        if (NESTED_QUANTIFIER.matcher(pattern).find()) {
            return "A repeated group inside a repetition is not allowed (it can make matching very slow).";
        }
        try {
            Pattern.compile(pattern);
            return null;
        } catch (PatternSyntaxException e) {
            return "The pattern is not a valid regular expression.";
        }
    }

    /** Redaction of one workspace's settings; values are never logged. */
    final class Redactor {

        private final List<String> fields;
        private final List<Pattern> patterns = new ArrayList<>();

        Redactor(Settings settings) {
            this.fields = settings.redactFields().stream().map(f -> f.toLowerCase(Locale.ROOT)).toList();
            for (String p : settings.redactPatterns()) {
                if (patternProblem(p) == null) {
                    patterns.add(Pattern.compile(p));
                }
            }
        }

        JsonNode redact(JsonNode node) {
            if (node.isObject()) {
                ObjectNode out = json.object();
                for (Map.Entry<String, JsonNode> e : node.properties()) {
                    out.set(e.getKey(), fields.contains(e.getKey().toLowerCase(Locale.ROOT))
                            ? json.mapper().getNodeFactory().stringNode("[redacted]") : redact(e.getValue()));
                }
                return out;
            }
            if (node.isArray()) {
                ArrayNode out = json.mapper().createArrayNode();
                node.forEach(item -> out.add(redact(item)));
                return out;
            }
            if (node.isString()) {
                String text = node.asString();
                if (text.length() > MAX_TEXT) {
                    text = text.substring(0, MAX_TEXT) + "...";
                }
                // A string that holds JSON (tool arguments, tool results) is redacted field by field.
                String trimmed = text.strip();
                if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                    try {
                        return json.mapper().getNodeFactory().stringNode(json.write(redact(json.parse(trimmed))));
                    } catch (IllegalArgumentException e) {
                        // plain text after all
                    }
                }
                return json.mapper().getNodeFactory().stringNode(text(text));
            }
            return node;
        }

        String text(String value) {
            String out = TOKEN.matcher(value).replaceAll("[secret]");
            out = EMAIL.matcher(out).replaceAll("[email]");
            out = DIGITS.matcher(out).replaceAll("[number]");
            for (Pattern p : patterns) {
                out = p.matcher(out).replaceAll("[redacted]");
            }
            return out;
        }
    }

    private List<String> strings(String array) {
        List<String> values = new ArrayList<>();
        json.parse(array).forEach(v -> values.add(v.asString()));
        return List.copyOf(values);
    }
}
