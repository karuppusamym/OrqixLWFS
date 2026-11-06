package com.orqix.runtime.internal;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * CG-T01 (docs/build/S141): delivery port for actionable task messages. Only {@link LocalTaskActionStub} exists in this
 * build: no SMTP client, Microsoft Graph/Bot Framework or Slack Web API client is linked, so nothing leaves Orqix.
 *
 * @see LocalTaskActionStub
 */
public interface TaskActionTransport {

    /**
     * @param channel   email, teams or slack
     * @param recipient the email address, Teams AAD object id or Slack user id
     * @param message   the rendered message: email {@code subject}/{@code text}, Teams Adaptive Card, Slack blocks
     * @return the transport outcome ({@code stubbed} for the local stub)
     */
    String deliver(String channel, UUID workspaceId, String recipient, JsonNode message);
}
