package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.orqix.catalog.StartableWorkflows;
import com.orqix.catalog.StartableWorkflows.Form;
import com.orqix.identity.Caller;
import com.orqix.integration.AgentModelPort;
import com.orqix.runtime.internal.ConversationIntentService;
import com.orqix.shared.json.Json;

import tools.jackson.databind.json.JsonMapper;

class ConversationIntentServiceTest {

    private final Json json = new Json(JsonMapper.builder().build());
    private final StartableWorkflows workflows = org.mockito.Mockito.mock(StartableWorkflows.class);
    private final AgentModelPort model = org.mockito.Mockito.mock(AgentModelPort.class);
    private final ConversationIntentService intents = new ConversationIntentService(workflows, model, json);
    private final Caller caller = new Caller(UUID.randomUUID(), "Requester", null, "issuer", "subject", List.of(), false);
    private final UUID workspace = UUID.randomUUID();

    @Test
    void routesNaturalRequestOnlyToAnAuthorizedWorkflowAndMetersIt() {
        var purchase = new StartableWorkflows.Choice(UUID.randomUUID(), "purchase-approval", "Purchase approval");
        when(workflows.find(caller, workspace, null)).thenReturn(List.of(purchase));
        when(model.unavailableReason()).thenReturn(null);
        when(model.budgetLeftUsd()).thenReturn(BigDecimal.ONE);
        when(model.resolve(null)).thenReturn(new AgentModelPort.ResolvedModel("economy", "deepseek/model"));
        when(workflows.form(caller, workspace, purchase.id())).thenReturn(form());
        when(model.complete(any())).thenReturn(completion("{\"workflowIds\":[\"" + purchase.id() + "\"]}"),
                completion("{\"title\":\"Laptop\"}"));

        var result = intents.interpret(caller, workspace, json.parse("{\"message\":\"I need a laptop\"}"));

        assertThat(result.choices()).containsExactly(purchase);
        assertThat(result.message()).isEmpty();
        assertThat(result.input()).isEqualTo(json.parse("{\"title\":\"Laptop\"}"));
        ArgumentCaptor<AgentModelPort.CompletionRequest> request = ArgumentCaptor.forClass(
                AgentModelPort.CompletionRequest.class);
        verify(model, org.mockito.Mockito.times(2)).complete(request.capture());
        assertThat(request.getAllValues().getFirst().messages().toString()).contains("I need a laptop",
                "Purchase approval");
        assertThat(request.getAllValues().getLast().messages().toString()).contains("I need a laptop",
                "Purchase title");
        assertThat(request.getAllValues().getLast().responseSchema().toString()).contains("title", "string");
        assertThat(request.getAllValues()).allSatisfy(call -> assertThat(call.tools()).isNull());
        verify(model).record(org.mockito.ArgumentMatchers.argThat(usage -> usage.outcome().equals("success")
                && usage.promptVersion().equals("conversation-intent-v1") && usage.costUsd().equals(
                        new BigDecimal("0.0001"))));
        verify(model).record(org.mockito.ArgumentMatchers.argThat(usage -> usage.outcome().equals("success")
                && usage.promptVersion().equals("conversation-fields-v1")));
    }

    @Test
    void rejectsModelSuggestionsThatAreNotInTheAuthorizedWorkflowList() {
        var purchase = new StartableWorkflows.Choice(UUID.randomUUID(), "purchase-approval", "Purchase approval");
        when(workflows.find(caller, workspace, null)).thenReturn(List.of(purchase));
        when(model.unavailableReason()).thenReturn(null);
        when(model.budgetLeftUsd()).thenReturn(BigDecimal.ONE);
        when(model.resolve(null)).thenReturn(new AgentModelPort.ResolvedModel("economy", "deepseek/model"));
        when(model.complete(any())).thenReturn(completion("{\"workflowIds\":[\""
                + UUID.randomUUID() + "\"]}"));

        var result = intents.interpret(caller, workspace, json.parse("{\"message\":\"I need a laptop\"}"));

        // The model's unknown id is ignored; the person is shown only what they may start, never the unknown workflow.
        assertThat(result.choices()).containsExactly(purchase);
        assertThat(result.message()).contains("Here is what you can start");
        verify(model).record(org.mockito.ArgumentMatchers.argThat(usage -> usage.outcome().equals("invalidOutput")));
    }

    @Test
    void withoutAiItMatchesWordsToTheNamesOfWorkflowsTheCallerMayStart() {
        var purchase = new StartableWorkflows.Choice(UUID.randomUUID(), "purchase-approval", "Purchase approval");
        var onboarding = new StartableWorkflows.Choice(UUID.randomUUID(), "vendor-onboarding", "Vendor onboarding check");
        when(workflows.find(caller, workspace, null)).thenReturn(List.of(purchase, onboarding));
        when(model.unavailableReason()).thenReturn("AI is not configured: no provider key is set.");

        var byName = intents.interpret(caller, workspace, json.parse("{\"message\":\"I need a purchase\"}"));
        assertThat(byName.choices()).containsExactly(purchase);
        assertThat(byName.message()).isEmpty();

        var nothing = intents.interpret(caller, workspace, json.parse("{\"message\":\"I need a laptop\"}"));
        assertThat(nothing.choices()).containsExactly(purchase, onboarding);
        assertThat(nothing.message()).doesNotContain("unavailable").contains("Here is what you can start");
        org.mockito.Mockito.verify(model, org.mockito.Mockito.never()).complete(any());
    }

    @Test
    void leavesExplicitCommandsOnTheDeterministicPath() {
        assertThat(intents.interpret(caller, workspace, json.parse("{\"message\":\"start Purchase approval\"}")))
                .isNull();
        org.mockito.Mockito.verifyNoInteractions(workflows, model);
    }

    private AgentModelPort.Completion completion(String content) {
        return new AgentModelPort.Completion(json.object().put("content", content), "stop", "deepseek/model",
                100L, 20L, new BigDecimal("0.0001"));
    }

    private Form form() {
        return new Form("Purchase approval", UUID.randomUUID(), json.parse("""
                {"type":"object","additionalProperties":false,"required":["title","purpose"],
                 "properties":{"title":{"type":"string","title":"Purchase title","minLength":1},
                 "purpose":{"type":"string","title":"Purpose","minLength":1}}}
                """), null);
    }
}
