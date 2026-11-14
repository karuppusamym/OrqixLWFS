package com.orqix.integration.alerts;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * CG-O06 (docs/build/S144): delivery port for Slack incoming webhooks and PagerDuty Events API v2. Only
 * {@link LocalAlertHookStub} exists in this build: no Slack or PagerDuty client is linked and nothing leaves Orqix.
 * A live transport would resolve the secret itself; the body handed over never contains the secret value.
 */
public interface AlertHookTransport {

    /**
     * @param kind slack or pagerDuty
     * @param body the Slack message or the PagerDuty event without its routing key
     * @return the transport outcome ({@code stubbed} for the local stub)
     */
    String post(String kind, UUID workspaceId, String secretRef, JsonNode body);
}
