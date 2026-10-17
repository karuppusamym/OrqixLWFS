package com.orqix.catalog.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.orqix.compiler.CompileContext;
import com.orqix.compiler.Severity;
import com.orqix.compiler.ValidationIssue;
import com.orqix.integration.AgentExecutor;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * Publish-time warning for workflows that call an AI agent while AI is not available in this environment (S13 section
 * 7). It is a warning, not a blocker: the key is deployment configuration that can change at any time and releases are
 * portable, so a workflow may be published where AI is off. Runs that reach the step stop with a clear message that an
 * operator can retry after an administrator has fixed the AI settings. The warning must be acknowledged to publish.
 */
@Component
public class AgentAvailabilityCheck {

    public static final String CODE = "WF_AGENT_AI_UNAVAILABLE";

    private final AgentExecutor agents;
    private final Json json;

    public AgentAvailabilityCheck(AgentExecutor agents, Json json) {
        this.agents = agents;
        this.json = json;
    }

    /** Warnings to add to the compiler's own; empty when AI is available or no step uses an agent. */
    public List<ValidationIssue> warnings(String definitionJson, CompileContext context) {
        String reason = agents.unavailableReason();
        if (reason == null) {
            return List.of();
        }
        Set<String> agentKeys = new LinkedHashSet<>();
        for (CompileContext.ConnectionVersion version : context.connectionVersions()) {
            try {
                if ("agent".equals(json.parse(version.definitionJson()).path("kind").asString("http"))) {
                    agentKeys.add(version.key());
                }
            } catch (IllegalArgumentException e) {
                // malformed definitions are reported by the compiler
            }
        }
        if (agentKeys.isEmpty()) {
            return List.of();
        }
        List<ValidationIssue> warnings = new ArrayList<>();
        JsonNode nodes;
        try {
            nodes = json.parse(definitionJson).path("nodes");
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        for (int i = 0; i < nodes.size(); i++) {
            JsonNode node = nodes.get(i);
            if ("http".equals(node.path("type").asString())
                    && agentKeys.contains(node.path("config").path("connectionRef").asString())) {
                String label = node.path("label").asString(node.path("id").asString());
                warnings.add(new ValidationIssue(CODE, Severity.WARNING, "\"" + label + "\" asks an AI agent, but AI is not "
                        + "available in this environment: " + reason + " Requests that reach this step will need "
                        + "attention until an administrator fixes the AI settings.", "/nodes/" + i + "/config/connectionRef",
                        node.path("id").asString(), null));
            }
        }
        return warnings;
    }
}
