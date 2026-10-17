package com.orqix.compiler;

/**
 * One validation finding. Codes are the WF_* catalog in S4 section 5.
 *
 * @param path         JSON Pointer into the definition
 * @param nodeId       related node, or null
 * @param transitionId related transition, or null
 */
public record ValidationIssue(String code, Severity severity, String message, String path, String nodeId,
                              String transitionId) {
}
