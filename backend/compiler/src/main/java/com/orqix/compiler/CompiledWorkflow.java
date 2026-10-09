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
        List<String> groups) {

    public record DecisionRef(String key, int version) {
    }

    public record ConnectionRef(String key, int definitionVersion, String definitionHash) {
    }
}
