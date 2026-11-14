package com.orqix.runtime.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.catalog.ReleaseQueries;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;

/**
 * CG-O04 (docs/build/S142): the indexed projection of searchable request fields ({@code run_search_value}). Rows are
 * replaced in the transaction that stores or corrects a request's data (start, child start, incident and approver
 * corrections, migration to another release), so the run list and bulk dry runs filter on exactly what the run holds.
 * The searchable fields come from the run's pinned release; a workflow declares at most {@value #MAX_FIELDS}.
 */
@Component
public class RunSearchIndex {

    public static final int MAX_FIELDS = 10;
    public static final int MAX_TEXT = 200;
    public static final int MAX_CONDITIONS = 5;

    /** kind: text, choice, number, integer, boolean or date. */
    public record Field(String key, String title, String kind, List<String> options) {

        boolean numeric() {
            return "number".equals(kind) || "integer".equals(kind);
        }
    }

    /** One filter condition. op: eq, prefix (text), gte or lte (numbers and dates). */
    public record Condition(String key, String op, String text, BigDecimal number) {

        @Override
        public String toString() {
            return key + ":" + op + ":" + (number != null ? number.toPlainString() : text);
        }
    }

    private static final Pattern CONDITION = Pattern.compile("^([A-Za-z][A-Za-z0-9_]{0,63}):(eq|prefix|gte|lte):(.{1,200})$",
            Pattern.DOTALL);
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final JdbcClient jdbc;
    private final ReleaseQueries releases;
    private final Json json;

    public RunSearchIndex(JdbcClient jdbc, ReleaseQueries releases, Json json) {
        this.jdbc = jdbc;
        this.releases = releases;
        this.json = json;
    }

    /** The searchable fields of one release, in form order, at most {@value #MAX_FIELDS}. */
    public static List<Field> fields(ReleaseQueries.ReleaseView release) {
        return fields(release.inputSchema(), release.inputUi());
    }

    /** Form order: {@code inputUi.order} first, then the remaining fields by key (jsonb keeps no key order). */
    static List<Field> fields(JsonNode inputSchema, JsonNode inputUi) {
        List<String> order = new ArrayList<>();
        inputUi.path("order").forEach(k -> order.add(k.asString()));
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(inputSchema.path("properties").properties());
        entries.sort(java.util.Comparator.<Map.Entry<String, JsonNode>>comparingInt(e -> {
            int i = order.indexOf(e.getKey());
            return i < 0 ? Integer.MAX_VALUE : i;
        }).thenComparing(Map.Entry::getKey));
        List<Field> fields = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : entries) {
            JsonNode f = e.getValue();
            if (!f.path("searchable").asBoolean(false)) {
                continue;
            }
            String kind = kind(f);
            if (kind == null) {
                continue;
            }
            List<String> options = new ArrayList<>();
            f.path("enum").forEach(v -> options.add(v.asString()));
            fields.add(new Field(e.getKey(), f.path("title").asString(e.getKey()), kind, List.copyOf(options)));
            if (fields.size() == MAX_FIELDS) {
                break;
            }
        }
        return fields;
    }

    private static String kind(JsonNode f) {
        String type = f.path("type").asString("");
        String format = f.path("format").asString(null);
        return switch (type) {
            case "number", "integer", "boolean" -> type;
            case "string" -> format == null ? (f.has("enum") ? "choice" : "text") : "date".equals(format) ? "date" : null;
            default -> null;
        };
    }

    /**
     * The searchable fields of a workflow across its latest releases (newest release wins for the title and kind), for
     * filter forms and to check conditions. Empty when no release declares any.
     */
    @Transactional(readOnly = true)
    public List<Field> forWorkflow(UUID workspaceId, UUID workflowId) {
        Map<String, Field> byKey = new LinkedHashMap<>();
        for (UUID releaseId : releases.releaseIds(workspaceId, workflowId, 20)) {
            releases.find(workspaceId, releaseId).ifPresent(release -> {
                for (Field f : fields(release)) {
                    byKey.putIfAbsent(f.key(), f);
                }
            });
        }
        return List.copyOf(byKey.values());
    }

    /**
     * Parses {@code key:op:value} conditions against the workflow's searchable fields. Unknown fields, operators that
     * do not fit the field and values of the wrong type are 422 with a field error on {@code /field/<i>}.
     */
    public List<Condition> parse(UUID workspaceId, UUID workflowId, List<String> raw) {
        return parse(workspaceId, workflowId, raw, "/field");
    }

    /** As {@link #parse(UUID, UUID, List)} with field errors under {@code pointer}. */
    public List<Condition> parse(UUID workspaceId, UUID workflowId, List<String> raw, String pointer) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > MAX_CONDITIONS) {
            throw ApiException.malformed("At most " + MAX_CONDITIONS + " field conditions are allowed.");
        }
        if (workflowId == null) {
            throw ApiException.malformed("Choose a workflow to filter by its request fields.");
        }
        Map<String, Field> fields = new LinkedHashMap<>();
        forWorkflow(workspaceId, workflowId).forEach(f -> fields.put(f.key(), f));
        List<Condition> conditions = new ArrayList<>();
        List<ApiException.FieldError> errors = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String path = pointer + "/" + i;
            var m = CONDITION.matcher(raw.get(i) == null ? "" : raw.get(i));
            if (!m.matches()) {
                errors.add(new ApiException.FieldError(path, "pattern", "Write field conditions as key:op:value."));
                continue;
            }
            Field field = fields.get(m.group(1));
            String op = m.group(2);
            String value = m.group(3).strip();
            if (field == null) {
                errors.add(new ApiException.FieldError(path, "enum", "\"" + m.group(1)
                        + "\" is not a searchable field of this workflow."));
                continue;
            }
            boolean ranged = "gte".equals(op) || "lte".equals(op);
            if (field.numeric()) {
                if ("prefix".equals(op)) {
                    errors.add(new ApiException.FieldError(path, "enum", field.title() + " is a number; use eq, gte or lte."));
                    continue;
                }
                try {
                    conditions.add(new Condition(field.key(), op, null, new BigDecimal(value)));
                } catch (NumberFormatException e) {
                    errors.add(new ApiException.FieldError(path, "type", field.title() + " needs a number."));
                }
            } else if ("date".equals(field.kind())) {
                if ("prefix".equals(op) || !DATE.matcher(value).matches()) {
                    errors.add(new ApiException.FieldError(path, "format", field.title()
                            + " needs a date written YYYY-MM-DD with eq, gte or lte."));
                    continue;
                }
                conditions.add(new Condition(field.key(), op, value, null));
            } else if ("boolean".equals(field.kind())) {
                if (!"eq".equals(op) || !("true".equals(value) || "false".equals(value))) {
                    errors.add(new ApiException.FieldError(path, "type", field.title() + " is yes or no: use eq:true or eq:false."));
                    continue;
                }
                conditions.add(new Condition(field.key(), op, value, null));
            } else {
                if (ranged) {
                    errors.add(new ApiException.FieldError(path, "enum", field.title() + " is text; use eq or prefix."));
                    continue;
                }
                conditions.add(new Condition(field.key(), op, value.toLowerCase(Locale.ROOT), null));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Check the field conditions.").withFieldErrors(errors);
        }
        return List.copyOf(conditions);
    }

    /** Replaces the projection rows of one run from its stored data and pinned release. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refresh(UUID workspaceId, UUID runId) {
        var row = jdbc.sql("SELECT workflow_id, release_id, input::text AS input FROM workflow_run WHERE workspace_id = :ws AND id = :id")
                .param("ws", workspaceId).param("id", runId)
                .query((rs, n) -> new Object[] {Sql.uuid(rs, "workflow_id"), Sql.uuid(rs, "release_id"), rs.getString("input")})
                .optional().orElse(null);
        if (row == null) {
            return;
        }
        jdbc.sql("DELETE FROM run_search_value WHERE workspace_id = :ws AND run_id = :id")
                .param("ws", workspaceId).param("id", runId).update();
        ReleaseQueries.ReleaseView release = releases.find(workspaceId, (UUID) row[1]).orElse(null);
        if (release == null) {
            return;
        }
        List<Field> fields = fields(release);
        if (fields.isEmpty()) {
            return;
        }
        JsonNode input = json.parse((String) row[2]);
        for (Field field : fields) {
            JsonNode value = input.path(field.key());
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            String text = null;
            BigDecimal number = null;
            if (field.numeric()) {
                if (!value.isNumber()) {
                    continue;
                }
                number = value.decimalValue();
            } else if ("boolean".equals(field.kind())) {
                if (!value.isBoolean()) {
                    continue;
                }
                text = Boolean.toString(value.asBoolean());
            } else {
                if (!value.isString()) {
                    continue;
                }
                text = "date".equals(field.kind()) ? value.asString() : value.asString().strip().toLowerCase(Locale.ROOT);
                if (text.isEmpty() || text.length() > MAX_TEXT) {
                    continue;
                }
            }
            jdbc.sql("""
                    INSERT INTO run_search_value (workspace_id, run_id, workflow_id, field_key, value_text, value_number)
                    VALUES (:ws, :run, :wf, :key, :text, :number)
                    """)
                    .param("ws", workspaceId).param("run", runId).param("wf", row[0]).param("key", field.key())
                    .param("text", text).param("number", number).update();
        }
    }

    /**
     * SQL predicates (joined with AND) for the conditions on {@code workflow_run}; parameters are added to
     * {@code params} under names prefixed {@code sv}.
     */
    static String predicates(List<Condition> conditions, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < conditions.size(); i++) {
            Condition c = conditions.get(i);
            String k = "svk" + i;
            String v = "svv" + i;
            params.put(k, c.key());
            String compare;
            if (c.number() != null) {
                params.put(v, c.number());
                compare = "v.value_number " + switch (c.op()) {
                    case "gte" -> ">=";
                    case "lte" -> "<=";
                    default -> "=";
                } + " :" + v;
            } else if ("prefix".equals(c.op())) {
                params.put(v, c.text().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
                compare = "v.value_text LIKE :" + v;
            } else {
                params.put(v, c.text());
                compare = "v.value_text " + switch (c.op()) {
                    case "gte" -> ">=";
                    case "lte" -> "<=";
                    default -> "=";
                } + " :" + v;
            }
            sql.append(" AND EXISTS (SELECT 1 FROM run_search_value v WHERE v.workspace_id = workflow_run.workspace_id")
                    .append(" AND v.run_id = workflow_run.id AND v.field_key = :").append(k).append(" AND ")
                    .append(compare).append(")");
        }
        return sql.toString();
    }
}
