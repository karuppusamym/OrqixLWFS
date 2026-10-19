package com.orqix.integration;

import java.util.List;
import java.util.UUID;

import com.orqix.integration.ConnectionDefinition.WriteTool;

import tools.jackson.databind.node.ObjectNode;

/**
 * Approval-gated write tools of agent operations (IN04, docs/build/S75). The agent executor asks this port before it
 * offers a write tool to the model and again before every use; the implementation journals every write as its own
 * {@code nonIdempotent} effect with a stable key and delivers only through the local stub transport.
 *
 * <p>Rules: a tool declared in a connection definition, or an available adapter, authorizes nothing; only an active
 * approval for exactly this workflow, connection version, operation and tool does. The approver never triggers a write
 * through the tool they approved (separation of duties). An unknown outcome is never sent again automatically.
 */
public interface AgentWriteTools {

    /**
     * One agent execution of a workflow run that may write. Connection tests have no scope and can never write.
     *
     * @param agentEffectId the journal entry of the agent step itself; write keys derive from it
     * @param requestedBy   the run initiator: the person on whose behalf the write happens
     */
    record Scope(UUID workspaceId, UUID runId, UUID agentEffectId, String nodeId, UUID requestedBy,
            ConnectionDefinition definition, String operation) {
    }

    /**
     * @param refusal null when allowed; otherwise a stable code ({@code WRITE_NOT_APPROVED}, {@code WRITE_SELF_APPROVAL})
     */
    record Authorization(UUID approvalId, UUID approvedBy, String refusal) {

        public boolean allowed() {
            return refusal == null;
        }
    }

    enum Outcome {
        /** Delivered by the transport now. */
        SUCCEEDED,
        /** An earlier attempt already succeeded for this slot: nothing was sent again. */
        REUSED,
        /** Not approved, separation of duties, or the adapter refused it: nothing was sent. */
        REFUSED,
        /** It is not known whether it was delivered: never sent again until an operator reconciles it. */
        UNKNOWN,
        /** Another executor holds the slot's lease. */
        BUSY
    }

    /** @param message plain-language reason for an operator (never the arguments) */
    record WriteResult(Outcome outcome, UUID effectId, String errorCode, String message) {
    }

    /** An active approval, as the compiler needs it. */
    record ApprovalFact(String workflowKey, String connectionKey, int connectionVersion, String operation,
            String toolName) {
    }

    Authorization authorize(Scope scope, WriteTool tool);

    /**
     * Journals and delivers one use of a write tool.
     *
     * @param callIndex 1-based use of this tool in this execution; with the agent effect it forms the stable key
     * @param arguments validated by {@link WriteToolArguments}; never logged, stored or audited
     */
    WriteResult write(Scope scope, WriteTool tool, int callIndex, ObjectNode arguments);

    List<ApprovalFact> activeApprovals(UUID workspaceId);

    /** Refuses everything (used where no database exists, e.g. unit tests of the executor). */
    AgentWriteTools NONE = new AgentWriteTools() {
        @Override
        public Authorization authorize(Scope scope, WriteTool tool) {
            return new Authorization(null, null, "WRITE_NOT_APPROVED");
        }

        @Override
        public WriteResult write(Scope scope, WriteTool tool, int callIndex, ObjectNode arguments) {
            return new WriteResult(Outcome.REFUSED, null, "WRITE_NOT_APPROVED", "Write tools are not available.");
        }

        @Override
        public List<ApprovalFact> activeApprovals(UUID workspaceId) {
            return List.of();
        }
    };
}
