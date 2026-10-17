package com.orqix.ai;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.ai.internal.AiLedger;
import com.orqix.ai.internal.ModelPolicy;
import com.orqix.ai.internal.ProviderException;
import com.orqix.ai.internal.ToolChat;
import com.orqix.integration.AgentModelPort;
import com.orqix.shared.problem.ApiException;

/**
 * The AI module's side of {@link AgentModelPort} (S13 section 4): agent steps use the same model profiles, price caps,
 * provider key, budgets and metering as every other AI feature. Usage is recorded in {@code ai_generation} with kind
 * {@code agent}, so the monthly budget is one number; a row commits in its own transaction because the job
 * transaction of a failed step rolls back.
 */
@Component
public class AgentModelAdapter implements AgentModelPort {

    private final AiProperties properties;
    private final ModelPolicy policy;
    private final AiLedger ledger;
    private final ToolChat chat;
    private final TransactionTemplate independentTx;

    public AgentModelAdapter(AiProperties properties, ModelPolicy policy, AiLedger ledger, ToolChat chat,
            PlatformTransactionManager transactions) {
        this.properties = properties;
        this.policy = policy;
        this.ledger = ledger;
        this.chat = chat;
        this.independentTx = new TransactionTemplate(transactions);
        this.independentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String unavailableReason() {
        return policy.disabledReason();
    }

    @Override
    public ResolvedModel resolve(String profileId) {
        try {
            ModelPolicy.ResolvedProfile profile = policy.resolve(profileId);
            return new ResolvedModel(profile.id(), profile.model());
        } catch (ApiException e) {
            throw new ModelNotAllowedException(e.getMessage());
        }
    }

    @Override
    public BigDecimal budgetLeftUsd() {
        BigDecimal monthly = properties.budget().monthlyUsd().subtract(ledger.monthToDate());
        return monthly.min(properties.budget().perRequestMaxUsd());
    }

    @Override
    public Completion complete(CompletionRequest request) {
        Completion completion;
        try {
            completion = chat.complete(request);
        } catch (ProviderException e) {
            if (e.kind() == ProviderException.Kind.CREDENTIALS_REJECTED) {
                policy.credentialsRejected();
            }
            throw new ModelException(switch (e.kind()) {
                case UNAVAILABLE -> FailureKind.UNAVAILABLE;
                case CREDENTIALS_REJECTED -> FailureKind.CREDENTIALS_REJECTED;
                case PAYMENT_REQUIRED -> FailureKind.PAYMENT_REQUIRED;
                case REJECTED -> FailureKind.REJECTED;
            }, e.retryAfterSeconds(), e.getMessage());
        }
        // A router profile may route to a model the policy refuses (S10 section 1): refuse its answer.
        if (policy.routedViolation(request.model(), completion.model()).isPresent()) {
            throw new ModelException(FailureKind.REJECTED, null, "The provider routed the request to a refused model");
        }
        return completion;
    }

    @Override
    public void record(UsageRecord usage) {
        independentTx.executeWithoutResult(status -> ledger.record(new AiLedger.Entry(usage.workspaceId(),
                usage.subjectId(), "agent", usage.profile(), usage.model(), usage.requestedModel(),
                usage.promptVersion(), usage.turns(), false, usage.inputTokens(), usage.outputTokens(), usage.costUsd(),
                usage.durationMs(), outcome(usage.outcome()), usage.workflowId(), null, null)));
    }

    private static AiLedger.Outcome outcome(String value) {
        return switch (value) {
            case "success" -> AiLedger.Outcome.SUCCESS;
            case "invalidOutput" -> AiLedger.Outcome.INVALID_OUTPUT;
            case "credentialsRejected" -> AiLedger.Outcome.CREDENTIALS_REJECTED;
            case "modelNotAllowed" -> AiLedger.Outcome.MODEL_NOT_ALLOWED;
            default -> AiLedger.Outcome.PROVIDER_ERROR;
        };
    }
}
