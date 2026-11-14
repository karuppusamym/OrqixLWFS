package com.orqix.governance.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.governance.AuditChains;
import com.orqix.governance.AuditExports;
import com.orqix.identity.CallerResolver;

/** Tamper-evident audit (CG-I06, docs/build/S151; contracts/openapi.yaml tag SecurityAndAudit). */
@RestController
@RequestMapping("/api/v1")
public class AuditGovernanceController {

    private final CallerResolver callers;
    private final AuditChains chains;
    private final AuditExports exports;

    public AuditGovernanceController(CallerResolver callers, AuditChains chains, AuditExports exports) {
        this.callers = callers;
        this.chains = chains;
        this.exports = exports;
    }

    @PostMapping("/workspaces/{workspaceId}/audit/chain-verifications")
    public AuditChains.Result verifyAuditChain(@PathVariable UUID workspaceId) {
        return chains.verifyWorkspace(callers.current(), workspaceId);
    }

    @GetMapping("/workspaces/{workspaceId}/audit/chain-verifications")
    public AuditChains.ResultList listAuditChainChecks(@PathVariable UUID workspaceId) {
        return chains.latest(callers.current(), workspaceId);
    }

    @GetMapping("/workspaces/{workspaceId}/audit/export")
    public AuditExports.Export exportAuditLog(@PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "0") long afterSeq, @RequestParam(required = false) Integer limit) {
        return exports.export(callers.current(), workspaceId, afterSeq, limit);
    }

    @GetMapping("/workspaces/{workspaceId}/audit/signing-keys")
    public AuditExports.SigningKeys listAuditSigningKeys(@PathVariable UUID workspaceId) {
        return exports.signingKeys(callers.current(), workspaceId);
    }
}
