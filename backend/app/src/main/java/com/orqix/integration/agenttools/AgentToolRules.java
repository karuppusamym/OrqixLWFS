package com.orqix.integration.agenttools;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.orqix.integration.AgentExecutor;
import com.orqix.integration.ConnectionDefinition;
import com.orqix.integration.AgentWaitTools;
import com.orqix.integration.ConnectionService;
import com.orqix.shared.problem.ApiException;

import tools.jackson.databind.JsonNode;

/**
 * Registration rules of S135 that need the workspace (doc 29 §8.3-8.5). Each refusal is a field error whose keyword is
 * the rule code and whose pointer is under {@code /definition}:
 *
 * <ul>
 * <li>{@code MCP_SERVER_TOOLS}: tool and resource names of a tool server are unique.</li>
 * <li>{@code AGENT_TOOL_SERVER}: {@code server.tool} names a registered tool server and a tool of its catalog;
 * {@code AGENT_RESOURCE}: a resource reference names a resource of a registered server.</li>
 * <li>{@code AGENT_TOOL_NAME}: the name the model sees ({@code server__tool}) is unique and at most 64 characters.</li>
 * <li>{@code AGENT_TOOL_SOURCE}: an operation tool names an HTTP operation; {@code nonIdempotent} needs {@code write}
 * and a write tool name an S75 approval can carry; a decision tool names an existing table version; a workflow tool
 * names an existing release.</li>
 * <li>{@code AGENT_LOOP_REQUIRED}: workflow and ask-a-person tools, approvals and elicitation need {@code agent.loop}.</li>
 * <li>{@code AGENT_LOOP_LIMITS}: {@code maxDuration} is PT1M to P7D.</li>
 * <li>{@code AGENT_GROUP}: groups named for approvals, questions and elicitation exist in the workspace.</li>
 * <li>{@code AGENT_DATA_CLASS}: data classes are declared for request fields only.</li>
 * </ul>
 */
@Component
public class AgentToolRules {

    static final Duration MAX_DURATION = Duration.ofDays(7);

    private final ConnectionService connections;
    private final AgentDecisionTools decisions;
    private final JdbcClient jdbc;
    private final org.springframework.beans.factory.ObjectProvider<AgentWaitTools> waits;

    public AgentToolRules(ConnectionService connections, AgentDecisionTools decisions, JdbcClient jdbc,
            org.springframework.beans.factory.ObjectProvider<AgentWaitTools> waits) {
        this.connections = connections;
        this.decisions = decisions;
        this.jdbc = jdbc;
        this.waits = waits;
    }

    public List<ApiException.FieldError> check(UUID workspaceId, JsonNode definition) {
        List<ApiException.FieldError> errors = new ArrayList<>();
        String kind = definition.path("kind").asString("http");
        if (ConnectionDefinition.KIND_MCP_SERVER.equals(kind)) {
            unique(definition.path("mcpServer").path("tools"), "/definition/mcpServer/tools", errors);
            unique(definition.path("mcpServer").path("resources"), "/definition/mcpServer/resources", errors);
            return errors;
        }
        if (!ConnectionDefinition.KIND_AGENT.equals(kind)) {
            return errors;
        }
        for (Map.Entry<String, JsonNode> operation : definition.path("operations").properties()) {
            String base = "/definition/operations/" + operation.getKey() + "/agent";
            JsonNode agent = operation.getValue().path("agent");
            boolean loop = agent.path("loop").isObject();
            Set<String> modelNames = new HashSet<>();
            JsonNode tools = agent.path("tools");
            for (int i = 0; i < tools.size(); i++) {
                checkTool(workspaceId, tools.get(i), base + "/tools/" + i, loop, modelNames, errors);
            }
            JsonNode writeTools = agent.path("writeTools");
            for (int i = 0; i < writeTools.size(); i++) {
                if (!modelNames.add(writeTools.get(i).path("name").asString())) {
                    errors.add(error(base + "/writeTools/" + i + "/name", "AGENT_TOOL_NAME",
                            "The model would see two tools with this name."));
                }
            }
            JsonNode resources = agent.path("resources");
            for (int i = 0; i < resources.size(); i++) {
                String reference = resources.get(i).asString();
                int dot = reference.indexOf('.');
                ConnectionDefinition server = server(workspaceId, reference.substring(0, dot));
                if (server == null || server.mcpServer().resource(reference.substring(dot + 1)) == null) {
                    errors.add(error(base + "/resources/" + i, "AGENT_RESOURCE",
                            "No registered tool server offers the resource " + reference + "."));
                }
            }
            if (agent.has("elicitation")) {
                if (!loop) {
                    errors.add(error(base + "/elicitation", "AGENT_LOOP_REQUIRED",
                            "Elicitation becomes a task for a person, so the agent needs a durable loop."));
                }
                group(workspaceId, agent.path("elicitation").path("group").asString(), base + "/elicitation/group",
                        errors);
            }
            if (loop) {
                try {
                    Duration duration = Duration.parse(agent.path("loop").path("maxDuration").asString());
                    if (duration.compareTo(Duration.ofMinutes(1)) < 0 || duration.compareTo(MAX_DURATION) > 0) {
                        errors.add(error(base + "/loop/maxDuration", "AGENT_LOOP_LIMITS",
                                "A durable loop runs for at least one minute and at most 7 days (P7D)."));
                    }
                } catch (DateTimeParseException e) {
                    errors.add(error(base + "/loop/maxDuration", "AGENT_LOOP_LIMITS",
                            "Write the duration as ISO-8601, for example PT2H or P3D."));
                }
            }
            Set<String> fields = new HashSet<>();
            operation.getValue().path("requestSchema").path("properties").propertyNames().forEach(fields::add);
            for (String field : agent.path("inputDataClasses").propertyNames()) {
                if (!fields.contains(field)) {
                    errors.add(error(base + "/inputDataClasses/" + field, "AGENT_DATA_CLASS",
                            field + " is not a request field of this operation."));
                }
            }
        }
        return errors;
    }

    private void checkTool(UUID workspaceId, JsonNode tool, String at, boolean loop, Set<String> modelNames,
            List<ApiException.FieldError> errors) {
        String name = tool.path("name").asString();
        String modelName = AgentExecutor.modelName(name);
        if (modelName.length() > 64 || !modelNames.add(modelName)) {
            errors.add(error(at + "/name", "AGENT_TOOL_NAME", modelName.length() > 64
                    ? "The name the model sees (" + modelName + ") is longer than 64 characters."
                    : "The model would see two tools with the name " + modelName + "."));
        }
        JsonNode source = tool.path("source");
        if (tool.has("requiresApproval")) {
            if (!loop) {
                errors.add(error(at + "/requiresApproval", "AGENT_LOOP_REQUIRED",
                        "An approval pauses the agent until a person decides, so the agent needs a durable loop."));
            }
            group(workspaceId, tool.path("requiresApproval").path("group").asString(), at + "/requiresApproval/group",
                    errors);
        }
        boolean write = tool.path("write").asBoolean(false);
        if (!source.isObject()) {
            if (write) {
                errors.add(error(at + "/write", "AGENT_TOOL_SOURCE", "Only an operation tool can be a write tool."));
            }
            int dot = name.indexOf('.');
            if (dot > 0) {
                ConnectionDefinition server = server(workspaceId, name.substring(0, dot));
                if (server == null) {
                    errors.add(error(at + "/name", "AGENT_TOOL_SERVER", "There is no registered tool server '"
                            + name.substring(0, dot) + "' in this workspace."));
                } else if (server.mcpServer().tool(name.substring(dot + 1)) == null) {
                    errors.add(error(at + "/name", "AGENT_TOOL_SERVER", "The tool server '" + name.substring(0, dot)
                            + "' does not list the tool " + name.substring(dot + 1) + " in its catalog."));
                }
            }
            return;
        }
        if (name.contains(".")) {
            errors.add(error(at + "/name", "AGENT_TOOL_NAME", "An Orqix tool name has no dot (dots name server tools)."));
        }
        if (source.has("operation")) {
            String key = source.path("operation").path("connection").asString();
            String op = source.path("operation").path("operation").asString();
            ConnectionDefinition target = latest(workspaceId, key);
            ConnectionDefinition.Operation operation = target == null ? null : target.operations().get(op);
            if (operation == null || target.isAgent() || target.isMcpServer()) {
                errors.add(error(at + "/source/operation", "AGENT_TOOL_SOURCE",
                        "There is no HTTP operation " + key + "." + op + " in this workspace."));
                return;
            }
            boolean nonIdempotent = operation.effectClass() == ConnectionDefinition.EffectClass.NON_IDEMPOTENT;
            if (nonIdempotent && !write) {
                errors.add(error(at + "/write", "AGENT_TOOL_SOURCE", key + "." + op + " is nonIdempotent: mark the "
                        + "tool as a write tool, so it runs only under an approval (S75)."));
            }
            if (write && !nonIdempotent) {
                errors.add(error(at + "/write", "AGENT_TOOL_SOURCE",
                        "Only an operation whose effect class is nonIdempotent is a write tool."));
            }
            if (write && !name.matches("^[a-z][a-z0-9_]{0,47}$")) {
                errors.add(error(at + "/name", "AGENT_TOOL_SOURCE",
                        "A write tool name has lower-case letters, digits and underscores (at most 48)."));
            }
        } else if (write) {
            errors.add(error(at + "/write", "AGENT_TOOL_SOURCE", "Only an operation tool can be a write tool."));
        }
        if (source.has("decision")) {
            if (decisions.table(workspaceId, source.path("decision").path("key").asString(),
                    source.path("decision").path("version").asInt()).isEmpty()) {
                errors.add(error(at + "/source/decision", "AGENT_TOOL_SOURCE",
                        "There is no shared decision table with this key and version."));
            }
        }
        if (source.has("workflow") || source.has("askPerson")) {
            if (!loop) {
                errors.add(error(at + "/source", "AGENT_LOOP_REQUIRED", "A tool that waits for "
                        + (source.has("workflow") ? "a child request" : "a person") + " needs a durable loop."));
            }
        }
        if (source.has("workflow")) {
            AgentWaitTools lookup = waits.getIfAvailable(() -> AgentWaitTools.NONE);
            if (lookup.workflowInputSchema(workspaceId, source.path("workflow").path("key").asString(),
                    source.path("workflow").path("release").asInt()) == null) {
                errors.add(error(at + "/source/workflow", "AGENT_TOOL_SOURCE",
                        "There is no published release with this workflow key and release number."));
            }
        }
        if (source.has("askPerson")) {
            group(workspaceId, source.path("askPerson").path("group").asString(), at + "/source/askPerson/group",
                    errors);
        }
    }

    private void group(UUID workspaceId, String key, String at, List<ApiException.FieldError> errors) {
        int found = jdbc.sql("SELECT count(*) FROM workspace_group WHERE workspace_id = :ws AND key = :key")
                .param("ws", workspaceId).param("key", key).query(Integer.class).single();
        if (found == 0) {
            errors.add(error(at, "AGENT_GROUP", "There is no group '" + key + "' in this workspace."));
        }
    }

    private ConnectionDefinition server(UUID workspaceId, String key) {
        ConnectionDefinition definition = latest(workspaceId, key);
        return definition != null && definition.isMcpServer() ? definition : null;
    }

    private ConnectionDefinition latest(UUID workspaceId, String key) {
        return connections.definitions(workspaceId, key).stream()
                .max(java.util.Comparator.comparingInt(ConnectionService.DefinitionRecord::version))
                .map(ConnectionService.DefinitionRecord::definition).orElse(null);
    }

    private static void unique(JsonNode items, String at, List<ApiException.FieldError> errors) {
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            String name = items.get(i).path("name").asString();
            if (seen.putIfAbsent(name, i) != null) {
                errors.add(error(at + "/" + i + "/name", "MCP_SERVER_TOOLS", name + " is listed twice."));
            }
        }
    }

    private static ApiException.FieldError error(String pointer, String code, String message) {
        return new ApiException.FieldError(pointer, code, message);
    }
}
