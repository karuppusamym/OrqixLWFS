package com.orqix.governance;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.orqix.shared.config.ProtectionSettings;

/** CG-I06 (S151): verifies every audit chain every {@code orqix.protection.audit-chain.interval} (default 6 h). */
@Component
@Profile("!migrate")
public class AuditChainScheduling {

    private final AuditChains chains;
    private final ProtectionSettings settings;

    public AuditChainScheduling(AuditChains chains, ProtectionSettings settings) {
        this.chains = chains;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${orqix.protection.audit-chain.interval:6h}",
            initialDelayString = "${orqix.protection.audit-chain.interval:6h}")
    public void verify() {
        if (settings.auditChain().scheduled()) {
            chains.verifyAll();
        }
    }
}
