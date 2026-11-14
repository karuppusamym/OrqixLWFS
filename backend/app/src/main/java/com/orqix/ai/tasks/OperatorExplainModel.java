package com.orqix.ai.tasks;

import java.math.BigDecimal;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.ai.AiProperties;
import com.orqix.ai.internal.AiLedger;
import com.orqix.ai.internal.ProviderException;
import com.orqix.ai.provider.ModelProvider;
import com.orqix.ai.provider.ProviderRegistry;
import com.orqix.ai.skills.AiSkills;
import com.orqix.integration.OperatorExplainPort;

/**
 * The model side of "Explain this request" (S146 part C, CG-A07): provider SPI, workspace residency rule, monthly
 * budget, temperature 0 with the strict answer schema, and one {@code ai_generation} row of kind {@code aiExplain}
 * (metering facts only: never the facts, the prompt or the answer). Called outside any transaction.
 */
@Component
public class OperatorExplainModel implements OperatorExplainPort {

    private static final Logger log = LoggerFactory.getLogger(OperatorExplainModel.class);

    private final ProviderRegistry providers;
    private final AiSkills skills;
    private final AiLedger ledger;
    private final AiProperties ai;
    private final TransactionTemplate tx;

    public OperatorExplainModel(ProviderRegistry providers, AiSkills skills, AiLedger ledger, AiProperties ai,
            PlatformTransactionManager transactions) {
        this.providers = providers;
        this.skills = skills;
        this.ledger = ledger;
        this.ai = ai;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public Result complete(Request r) {
        ProviderRegistry.Resolved profile;
        try {
            profile = providers.resolve(r.profile() == null || r.profile().isBlank() ? null : r.profile(),
                    skills.policy(r.workspaceId()).residency());
        } catch (ProviderRegistry.ProfileRefusedException e) {
            return Result.unavailable("residency".equals(e.code()) ? "residency" : "notConfigured");
        }
        if (ai.budget().monthlyUsd().subtract(ledger.monthToDate()).compareTo(BigDecimal.ZERO) <= 0) {
            return Result.unavailable("budget");
        }
        long started = System.nanoTime();
        ModelProvider.ModelResponse response;
        try {
            response = providers.complete(profile, new ModelProvider.ModelRequest(profile.model(), r.system(),
                    List.of(new ModelProvider.Turn("user", r.user())), r.schemaName(), r.schema(), r.maxTokens(), 0.0,
                    r.timeout()), r.workspaceId());
        } catch (ProviderException e) {
            meter(r, profile, null, started, e.kind() == ProviderException.Kind.CREDENTIALS_REJECTED
                    ? AiLedger.Outcome.CREDENTIALS_REJECTED : AiLedger.Outcome.PROVIDER_ERROR);
            return Result.unavailable("unavailable");
        } catch (RuntimeException e) {
            log.warn("AI run explanation failed: {}", e.getClass().getSimpleName());
            return Result.unavailable("unavailable");
        }
        boolean usable = r.usable() == null || r.usable().test(response.content());
        meter(r, profile, response, started, usable ? AiLedger.Outcome.SUCCESS : AiLedger.Outcome.INVALID_OUTPUT);
        return new Result(response.content(), profile.profileId(),
                response.model() == null ? profile.model() : response.model(), null);
    }

    private void meter(Request r, ProviderRegistry.Resolved profile, ModelProvider.ModelResponse response,
            long started, AiLedger.Outcome outcome) {
        try {
            tx.executeWithoutResult(status -> ledger.record(new AiLedger.Entry(r.workspaceId(), r.subjectId(),
                    "aiExplain", AiSkillsText.cut(profile.profileId(), 32),
                    response == null ? profile.model() : response.model(), profile.model(), r.promptVersion(), 1,
                    false, response == null ? null : response.inputTokens(),
                    response == null ? null : response.outputTokens(), response == null ? null : response.costUsd(),
                    (System.nanoTime() - started) / 1_000_000, outcome, r.workflowId(), null, null)));
        } catch (RuntimeException e) {
            log.warn("AI run explanation metering failed: {}", e.getClass().getSimpleName());
        }
    }
}
