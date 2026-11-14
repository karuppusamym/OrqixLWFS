package com.orqix.compiler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.orqix.compiler.internal.json.StrictJson;
import com.orqix.compiler.internal.model.Enums.NodeKind;

import tools.jackson.databind.JsonNode;

/**
 * Workspace facts the compiler needs for binding checks (S4 section 5.3). Supplied by the caller; the compiler never
 * reads a database.
 *
 * @param selfApprovalAllowed       true only when the workspace permits self-approval workflows (test workspaces)
 * @param groups                    workspace task-assignment groups by key
 * @param connectionVersions        every registered connection definition version in the workspace
 * @param enabledConnectionBindings connection keys with an enabled binding in this workspace
 * @param workflows                 workflows of the workspace by key with the facts needed to start them as child
 *                                  requests (S12); the active release of each is what a publish pins
 * @param adapters                  outbound adapters of the workspace by key (S73); a step may only send through one
 *                                  listed here, to recipients or topics its configuration approves
 * @param writeToolApprovals        active, unexpired agent write-tool approvals of the workspace (IN04, S75; expiry
 *                                  S85); an agent step whose
 *                                  operation declares write tools can only be published when each is approved for
 *                                  this workflow. Empty means none is approved (fail closed).
 * @param aiSkills                  AI skills of the workspace by key (CG-A01, S134): their versions with approval status,
 *                                  contract facts and whether a passing evaluation run on the pinned profile exists
 *                                  within 30 days (the publish gate)
 */
public record CompileContext(
        boolean selfApprovalAllowed,
        Map<String, GroupInfo> groups,
        List<ConnectionVersion> connectionVersions,
        Set<String> enabledConnectionBindings,
        Map<String, WorkflowInfo> workflows,
        Map<String, AdapterInfo> adapters,
        Set<WriteToolApproval> writeToolApprovals,
        Map<String, AiSkillInfo> aiSkills) {

    public CompileContext {
        aiSkills = aiSkills == null ? Map.of() : Map.copyOf(aiSkills);
        groups = Map.copyOf(groups);
        connectionVersions = List.copyOf(connectionVersions);
        enabledConnectionBindings = Set.copyOf(enabledConnectionBindings);
        workflows = workflows == null ? Map.of() : Map.copyOf(workflows);
        adapters = adapters == null ? Map.of() : Map.copyOf(adapters);
        writeToolApprovals = writeToolApprovals == null ? Set.of() : Set.copyOf(writeToolApprovals);
    }

    /** A context without AI skills (no AI task can be published). */
    public CompileContext(boolean selfApprovalAllowed, Map<String, GroupInfo> groups,
            List<ConnectionVersion> connectionVersions, Set<String> enabledConnectionBindings,
            Map<String, WorkflowInfo> workflows, Map<String, AdapterInfo> adapters,
            Set<WriteToolApproval> writeToolApprovals) {
        this(selfApprovalAllowed, groups, connectionVersions, enabledConnectionBindings, workflows, adapters,
                writeToolApprovals, Map.of());
    }

    /** @return the same facts with these AI skills */
    public CompileContext withAiSkills(Map<String, AiSkillInfo> skills) {
        return new CompileContext(selfApprovalAllowed, groups, connectionVersions, enabledConnectionBindings, workflows,
                adapters, writeToolApprovals, skills);
    }

    /** An AI skill (S134) and its versions by number. */
    public record AiSkillInfo(String key, String name, Map<Integer, AiSkillVersion> versions) {
        public AiSkillInfo {
            versions = Map.copyOf(versions);
        }

        /** @return the highest approved version, or null */
        public AiSkillVersion latestApproved() {
            return versions.values().stream().filter(AiSkillVersion::approved)
                    .max(java.util.Comparator.comparingInt(AiSkillVersion::version)).orElse(null);
        }
    }

    /**
     * One version of an AI skill. {@code inputs} and {@code fields} map a name to its scalar type ({@code string},
     * {@code number}, {@code integer}, {@code boolean}, {@code date}); {@code requiredInputs} and {@code requiredFields}
     * name the required ones.
     *
     * @param status              {@code draft}, {@code submitted}, {@code approved} or {@code retired}
     * @param passingEvaluationId the passing evaluation run on {@code modelProfile} within 30 days, or null
     * @param autonomyCeiling     the highest autonomy this version has been promoted to ({@code suggest} until promoted)
     */
    public record AiSkillVersion(int version, String status, String mode, String contentHash, String modelProfile,
            List<AiLabel> labels, Map<String, String> inputs, Set<String> requiredInputs, Map<String, String> fields,
            Set<String> requiredFields, java.math.BigDecimal scoreMin, java.math.BigDecimal scoreMax,
            List<String> outcomes, String passingEvaluationId, String autonomyCeiling) {
        public AiSkillVersion {
            labels = List.copyOf(labels);
            inputs = Map.copyOf(inputs);
            requiredInputs = Set.copyOf(requiredInputs);
            fields = Map.copyOf(fields);
            requiredFields = Set.copyOf(requiredFields);
            outcomes = List.copyOf(outcomes);
        }

        public boolean approved() {
            return "approved".equals(status);
        }
    }

    /** A classify label: {@code risk} is low, medium or high; {@code adverse} needs a person in regulated workspaces. */
    public record AiLabel(String key, String risk, boolean adverse) {
    }

    /** A context without write-tool approvals (no agent write tool can be published). */
    public CompileContext(boolean selfApprovalAllowed, Map<String, GroupInfo> groups,
            List<ConnectionVersion> connectionVersions, Set<String> enabledConnectionBindings,
            Map<String, WorkflowInfo> workflows, Map<String, AdapterInfo> adapters) {
        this(selfApprovalAllowed, groups, connectionVersions, enabledConnectionBindings, workflows, adapters, Set.of());
    }

    /** An active approval of one agent write tool for one workflow key (IN04, S75). */
    public record WriteToolApproval(String workflowKey, String connectionKey, int connectionVersion, String operation,
            String toolName) {
    }

    /** @return the same facts with these write-tool approvals */
    public CompileContext withWriteToolApprovals(Set<WriteToolApproval> approvals) {
        return new CompileContext(selfApprovalAllowed, groups, connectionVersions, enabledConnectionBindings, workflows,
                adapters, approvals, aiSkills);
    }

    /** A context without adapter facts (no adapter step can be valid). */
    public CompileContext(boolean selfApprovalAllowed, Map<String, GroupInfo> groups,
            List<ConnectionVersion> connectionVersions, Set<String> enabledConnectionBindings,
            Map<String, WorkflowInfo> workflows) {
        this(selfApprovalAllowed, groups, connectionVersions, enabledConnectionBindings, workflows, Map.of());
    }

    /** @return the same facts with these adapters */
    public CompileContext withAdapters(Map<String, AdapterInfo> adapters) {
        return new CompileContext(selfApprovalAllowed, groups, connectionVersions, enabledConnectionBindings, workflows,
                adapters, writeToolApprovals, aiSkills);
    }

    /**
     * An outbound adapter (S73). {@code configJson} is the stored allowlist configuration (never a secret): approved
     * recipient domains and the recipient limit for email, approved topics for Kafka, declared parameters for a
     * database read.
     */
    public record AdapterInfo(String key, String kind, String name, boolean enabled, String configJson) {
    }

    /** A context without child-workflow facts (no other workflow can be started). */
    public CompileContext(boolean selfApprovalAllowed, Map<String, GroupInfo> groups,
            List<ConnectionVersion> connectionVersions, Set<String> enabledConnectionBindings) {
        this(selfApprovalAllowed, groups, connectionVersions, enabledConnectionBindings, Map.of(), Map.of());
    }

    /** @param configuredMemberCount configured member principals (IdP group membership cannot be counted) */
    public record GroupInfo(String key, String name, int configuredMemberCount) {
    }

    /** @param definitionJson the connection definition document (connection-definition-v0.schema.json) */
    public record ConnectionVersion(String key, int version, String definitionJson, String definitionHash) {
    }

    /**
     * Facts about a workflow that another workflow may start (S12). {@code published} is false when the workflow has
     * no active release; the other members are then empty.
     *
     * @param releaseId        active release (pinned by a publish); null when not published
     * @param inputSchemaJson  request form of the active release
     * @param outputSchemaJson result form of the active release
     * @param guaranteedOutputs result fields present on every outcome (mapped as required)
     * @param outcomes         business outcome labels of the Finish steps, sorted
     * @param outcomeKinds     outcome kinds ({@code success}, {@code rejected}) of the Finish steps
     * @param callClosure      keys of every workflow this release starts, directly or through its own children
     * @param callHeight       0 when it starts no workflow, otherwise 1 + the largest height of the workflows it starts
     */
    public record WorkflowInfo(String key, String name, boolean published, String releaseId, int releaseNumber,
            String semanticHash, String inputSchemaJson, String outputSchemaJson, Set<String> guaranteedOutputs,
            List<String> outcomes, Set<String> outcomeKinds, Set<String> callClosure, int callHeight) {

        public WorkflowInfo {
            guaranteedOutputs = Set.copyOf(guaranteedOutputs);
            outcomes = List.copyOf(outcomes);
            outcomeKinds = Set.copyOf(outcomeKinds);
            callClosure = Set.copyOf(callClosure);
        }

        /** A workflow that exists but has no active release. */
        public static WorkflowInfo unpublished(String key, String name) {
            return new WorkflowInfo(key, name, false, null, 0, null, null, null, Set.of(), List.of(), Set.of(),
                    Set.of(), 0);
        }

        /**
         * Derives the facts of a published release from its canonical definition (already validated when it was
         * published).
         *
         * @param callClosure keys of the workflows the release starts (from the release manifest)
         * @param callHeight  call height of the release (from the release manifest)
         */
        public static WorkflowInfo of(String key, String name, String releaseId, int releaseNumber,
                String semanticHash, String definitionJson, Set<String> callClosure, int callHeight) {
            JsonNode root = StrictJson.parse(definitionJson);
            Set<String> guaranteed = new TreeSet<>();
            for (var entry : root.path("outputMapping").properties()) {
                if (entry.getValue().path("required").asBoolean(false)) {
                    guaranteed.add(entry.getKey());
                }
            }
            Set<String> outcomes = new TreeSet<>();
            Set<String> kinds = new LinkedHashSet<>();
            for (JsonNode node : root.path("nodes")) {
                if (NodeKind.END.json().equals(node.path("type").asString(""))) {
                    outcomes.add(node.path("config").path("businessOutcome").asString());
                    kinds.add(node.path("config").path("outcomeKind").asString());
                }
            }
            return new WorkflowInfo(key, name, true, releaseId, releaseNumber, semanticHash,
                    StrictJson.write(root.path("inputSchema")), StrictJson.write(root.path("outputSchema")), guaranteed,
                    new ArrayList<>(outcomes), kinds, callClosure, callHeight);
        }
    }
}
