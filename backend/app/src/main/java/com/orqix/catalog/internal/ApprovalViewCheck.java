package com.orqix.catalog.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import com.orqix.compiler.Severity;
import com.orqix.compiler.ValidationIssue;
import com.orqix.shared.json.Json;
import tools.jackson.databind.JsonNode;

/** Pins approval presentation without changing task eligibility, decision semantics or correction rights. */
@Component
public class ApprovalViewCheck {
    private final JdbcClient jdbc;
    private final Json json;
    public ApprovalViewCheck(JdbcClient jdbc, Json json) { this.jdbc = jdbc; this.json = json; }

    public List<ValidationIssue> issues(UUID workspace, JsonNode workflow) {
        List<ValidationIssue> result = new ArrayList<>();
        int index = 0;
        for (var node : workflow.path("nodes")) {
            String path = "/nodes/" + index++ + "/config/viewBinding";
            var binding = node.path("config").path("viewBinding");
            if (binding.isMissingNode()) continue;
            String nodeId = node.path("id").asString();
            if (!"approval".equals(node.path("type").asString()) || !binding.isObject()
                    || !binding.path("id").isString() || !binding.path("version").isIntegralNumber()
                    || !binding.path("version").canConvertToInt() || binding.path("version").asInt() < 1) {
                result.add(issue(AssetReferenceCheck.INVALID, Severity.BLOCKER, "The approval view pin is invalid.", path, nodeId));
                continue;
            }
            try {
                UUID id = UUID.fromString(binding.path("id").asString());
                int version = binding.path("version").asInt();
                var found = jdbc.sql("""
                        SELECT v.definition::text,a.published_version FROM application_view_version v
                        JOIN application_view a ON a.workspace_id=v.workspace_id AND a.id=v.view_id
                        WHERE v.workspace_id=:ws AND v.view_id=:id AND v.version=:version
                        """).param("ws", workspace).param("id", id).param("version", version)
                        .query((rs,n) -> java.util.Map.entry(rs.getString(1), rs.getInt(2))).optional();
                if (found.isEmpty()) {
                    result.add(issue(AssetReferenceCheck.MISSING, Severity.BLOCKER, "The pinned approval view version does not exist in this workspace.", path, nodeId));
                    continue;
                }
                var view = json.parse(found.get().getKey());
                String problem = compatibility(workflow, view);
                if (!view.equals(binding.path("definition"))) problem = "The approval presentation differs from its pinned view. Reapply the version or detach it.";
                if (problem != null) result.add(issue(AssetReferenceCheck.DIVERGED, Severity.BLOCKER, problem, path, nodeId));
                else if (found.get().getValue() > version) result.add(issue(AssetReferenceCheck.STALE, Severity.WARNING,
                        "A newer approval view version is available. Publishing retains version " + version + ".", path, nodeId));
            } catch (IllegalArgumentException ex) {
                result.add(issue(AssetReferenceCheck.INVALID, Severity.BLOCKER, "The approval view pin is invalid.", path, nodeId));
            }
        }
        return result;
    }

    public static String compatibility(JsonNode workflow, JsonNode view) {
        if (!"approval".equals(view.path("kind").asString())) return "Choose a published approval view.";
        Set<String> actions = new HashSet<>();
        for (var action : view.path("actions")) {
            String type = action.path("type").asString();
            if (!Set.of("approve", "reject", "navigate").contains(type) || !actions.add(type))
                return "Approval views support one Approve, one Reject and optionally one navigation action. Request correction actions are not supported.";
        }
        if (!actions.containsAll(Set.of("approve", "reject"))) return "The approval view must include both Approve and Reject actions.";
        var schema = workflow.path("inputSchema");
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(value -> required.add(value.asString()));
        Set<String> bound = new HashSet<>();
        for (var section : view.path("sections")) for (var field : section.path("fields")) {
            String binding = field.path("binding").asString();
            if (!binding.startsWith("request.")) return "Approval context fields must bind to request data. Task comment and correction controls remain managed by the task page.";
            String key = binding.substring(8);
            if (!bound.add(key) || !RequestViewCheck.fieldCompatible(field, schema.path("properties").path(key))
                    || required.contains(key) != field.path("required").asBoolean(false))
                return "Approval view bindings, field types or required flags do not match the workflow input schema.";
        }
        if (!bound.equals(new HashSet<>(schema.path("properties").propertyNames())))
            return "Every request field must appear exactly once in the approval view.";
        return null;
    }

    private static ValidationIssue issue(String code, Severity severity, String message, String path, String node) {
        return new ValidationIssue(code, severity, message, path, node, null);
    }
}
