package com.orqix.compiler;

import java.util.List;
import java.util.Map;

/**
 * Deterministic compiler output for one definition (S4 sections 6-9).
 *
 * @param processId     BPMN process id (equals workflowKey)
 * @param bpmnXml       process.bpmn20.xml content (UTF-8, LF)
 * @param dmnResources  resource name ("decisions/KEY.dmn") to DMN XML, in canonical order
 * @param sourceMapJson {"nodes":{...}, "transitions":{...}, "bpmnToCanonical":{...}}
 * @param dmnHashes     resource name to SHA-256 hex of the exact bytes
 * @param warnings      warnings that remained (the caller enforces acknowledgement)
 * @param decisions     decision tables used, as key/version
 * @param connections   connections used, with the pinned definition hash
 * @param groups        candidate group keys used by approval steps
 * @param children      child workflows started by "Start another workflow" steps, pinned to the release active at
 *                      compile time (S12)
 * @param callClosure   keys of every workflow this one starts, directly or through its children (sorted)
 * @param callHeight    0 when it starts no workflow, otherwise 1 + the largest call height of its children
 */
public record CompiledWorkflow(
        String compilerVersion,
        String processId,
        String bpmnXml,
        Map<String, String> dmnResources,
        String sourceMapJson,
        String sourceHash,
        String semanticHash,
        String bpmnHash,
        Map<String, String> dmnHashes,
        List<ValidationIssue> warnings,
        List<DecisionRef> decisions,
        List<ConnectionRef> connections,
        List<String> groups,
        List<ChildRef> children,
        List<String> callClosure,
        int callHeight) {

    public record DecisionRef(String key, int version) {
    }

    public record ConnectionRef(String key, int definitionVersion, String definitionHash) {
    }

    /** A child workflow pinned by a "Start another workflow" step. */
    public record ChildRef(String nodeId, String workflowKey, String releaseId, int releaseNumber,
            String semanticHash) {
    }
}
