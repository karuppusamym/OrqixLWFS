package com.orqix.integration;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * What the agent executor needs to know about workflows offered as tools (CG-M02, S135). Implemented by the runtime
 * module (allowed direction runtime to integration); without it workflow tools are not offered.
 */
public interface AgentWaitTools {

    /** The request form (JSON Schema) of a published release, or null when the workflow or release does not exist. */
    JsonNode workflowInputSchema(UUID workspaceId, String workflowKey, int releaseNumber);

    AgentWaitTools NONE = (workspaceId, workflowKey, releaseNumber) -> null;
}
