package com.orqix.integration.alerts;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.integration.AlertChannels;
import com.orqix.integration.ChannelSecrets;
import com.orqix.integration.SecretNamespaces;
import com.orqix.integration.adapters.AdapterRules;
import com.orqix.integration.adapters.AdapterTransport;
import com.orqix.integration.webhooks.WebhookService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link AlertChannels} over the IN02 adapters (email, Teams), the S99 outbound webhooks and the Slack/PagerDuty port.
 * The adapter is read again at every send, so a disabled adapter or a narrowed recipient allowlist stops sends it no
 * longer allows. Slack and PagerDuty secrets are resolved (and counted in the secret-access audit) at send time only to
 * check they exist; the value never reaches the transport, a log or the database.
 */
@Service
public class AlertChannelService implements AlertChannels {

    private final JdbcClient jdbc;
    private final AdapterTransport adapters;
    private final AlertHookTransport hooks;
    private final WebhookService webhooks;
    private final ChannelSecrets secrets;
    private final Json json;

    public AlertChannelService(JdbcClient jdbc, AdapterTransport adapters, AlertHookTransport hooks,
            WebhookService webhooks, ChannelSecrets secrets, Json json) {
        this.jdbc = jdbc;
        this.adapters = adapters;
        this.hooks = hooks;
        this.webhooks = webhooks;
        this.secrets = secrets;
        this.json = json;
    }

    private record Adapter(String kind, JsonNode config, boolean enabled) {
    }

    @Override
    @Transactional(readOnly = true)
    public void validate(UUID workspaceId, Target target) {
        switch (target.kind()) {
            case "email", "teams" -> {
                Adapter adapter = adapter(workspaceId, target.adapterKey());
                if (adapter == null || !adapter.kind().equals(target.kind())) {
                    throw invalid("/adapterKey", "Choose an existing " + label(target.kind()) + " adapter.");
                }
                if (!adapter.enabled()) {
                    throw invalid("/adapterKey", "The adapter is switched off.");
                }
                try {
                    AdapterRules.validateSend(AdapterRules.Kind.of(target.kind()), adapter.config(),
                            payload(target, sample()));
                } catch (AdapterRules.Invalid e) {
                    String pointer = e.pointer().startsWith("/payload/to") ? "/recipients" : "/adapterKey";
                    throw invalid(pointer, e.getMessage());
                }
            }
            case "slack", "pagerDuty" -> SecretNamespaces.refuseReserved(target.secretRef(), "/secretRef");
            case "webhook" -> {
                if (!webhooks.subscriptionExists(workspaceId, target.webhookSubscriptionId())) {
                    throw invalid("/webhookSubscriptionId", "Choose an existing webhook subscription.");
                }
            }
            default -> throw invalid("/kind", "Unknown destination kind.");
        }
    }

    @Override
    @Transactional
    public Outcome deliver(UUID workspaceId, Target target, Message message) {
        switch (target.kind()) {
            case "email", "teams" -> {
                Adapter adapter = adapter(workspaceId, target.adapterKey());
                if (adapter == null || !adapter.kind().equals(target.kind())) {
                    return new Outcome(false, null, "adapter_unknown");
                }
                if (!adapter.enabled()) {
                    return new Outcome(false, null, "adapter_disabled");
                }
                AdapterRules.Kind kind = AdapterRules.Kind.of(target.kind());
                ObjectNode payload;
                try {
                    payload = AdapterRules.validateSend(kind, adapter.config(), payload(target, message));
                } catch (AdapterRules.Invalid e) {
                    return new Outcome(false, null, "adapter_refused");
                }
                AdapterTransport.Result result = adapters.execute(kind, workspaceId, target.adapterKey(),
                        adapter.config(), payload);
                return new Outcome(true, result.outcome(), null);
            }
            case "slack", "pagerDuty" -> {
                if (secrets.resolve(target.secretRef(), workspaceId).isEmpty()) {
                    return new Outcome(false, null, "secret_missing");
                }
                String outcome = hooks.post(target.kind(), workspaceId, target.secretRef(),
                        "slack".equals(target.kind()) ? slack(message) : pagerDuty(message));
                return new Outcome(true, outcome, null);
            }
            case "webhook" -> {
                String error = webhooks.recordAlert(workspaceId, target.webhookSubscriptionId(), message.eventKey(),
                        "alert." + message.event(), alert(message), message.at());
                return error == null ? new Outcome(true, "queued", null) : new Outcome(false, null, error);
            }
            default -> {
                return new Outcome(false, null, "kind_unknown");
            }
        }
    }

    private Adapter adapter(UUID workspaceId, String key) {
        if (key == null) {
            return null;
        }
        return jdbc.sql("SELECT kind, config::text AS config, enabled FROM adapter_config WHERE workspace_id = :ws AND key = :key")
                .param("ws", workspaceId).param("key", key)
                .query((rs, n) -> new Adapter(rs.getString("kind"), json.parse(rs.getString("config")),
                        rs.getBoolean("enabled")))
                .optional().orElse(null);
    }

    private static Message sample() {
        return new Message("validate", "test", "Validation", "needsAttention", "info", 1, 1, 15, "all workflows",
                java.time.Instant.EPOCH);
    }

    static String summary(Message m) {
        String verb = switch (m.event()) {
            case "resolved" -> "Resolved";
            case "test" -> "Test";
            default -> "Firing";
        };
        return verb + ": " + m.ruleName() + " (" + m.severity() + ")";
    }

    static String text(Message m) {
        String body = switch (m.event()) {
            case "resolved" -> "The alert resolved: " + signalLabel(m.signal()) + " is " + m.value()
                    + ", below the threshold of " + m.threshold() + ".";
            case "test" -> "This is a test notification for an Orqix alert destination.";
            default -> signalLabel(m.signal()) + " is " + m.value() + ", at or above the threshold of " + m.threshold()
                    + ".";
        };
        return body + " Scope: " + m.scope() + ". Window: " + m.windowMinutes() + " minutes. At " + m.at() + ".";
    }

    static String signalLabel(String signal) {
        return switch (signal) {
            case "needsAttention" -> "Requests needing attention";
            case "slaBreaches" -> "SLA breaches";
            case "circuitOpen" -> "Paused connections (open circuits)";
            case "tokenRefreshFailing" -> "Credential refresh failures";
            case "deadLetters" -> "Dead letters";
            case "pollChecksFailing" -> "Failing poll checks";
            case "aiDriftFallback" -> "AI skills in drift fallback";
            default -> signal;
        };
    }

    private ObjectNode payload(Target target, Message m) {
        ObjectNode payload = json.object();
        if ("email".equals(target.kind())) {
            ArrayNode to = payload.putArray("to");
            (target.recipients() == null ? List.<String>of() : target.recipients()).forEach(to::add);
            payload.put("subject", summary(m));
            payload.put("body", text(m));
        } else {
            payload.put("title", summary(m));
            payload.put("text", text(m));
        }
        return payload;
    }

    private ObjectNode alert(Message m) {
        ObjectNode alert = json.object();
        alert.put("rule", m.ruleName()).put("signal", m.signal()).put("severity", m.severity())
                .put("value", m.value()).put("threshold", m.threshold()).put("windowMinutes", m.windowMinutes())
                .put("scope", m.scope()).put("event", m.event());
        return alert;
    }

    private ObjectNode slack(Message m) {
        ObjectNode body = json.object();
        body.put("text", summary(m) + " - " + text(m));
        ArrayNode blocks = body.putArray("blocks");
        blocks.addObject().put("type", "header").putObject("text").put("type", "plain_text").put("text", summary(m));
        blocks.addObject().put("type", "section").putObject("text").put("type", "mrkdwn").put("text", text(m));
        return body;
    }

    private ObjectNode pagerDuty(Message m) {
        ObjectNode body = json.object();
        body.put("event_action", "resolved".equals(m.event()) ? "resolve" : "trigger");
        // One PagerDuty incident per Orqix incident: the dedup key drops the event suffix.
        body.put("dedup_key", m.eventKey().replaceAll(":(fired|resolved|test)$", ""));
        ObjectNode payload = body.putObject("payload");
        payload.put("summary", summary(m) + " - " + signalLabel(m.signal()) + " " + m.value());
        payload.put("severity", switch (m.severity()) {
            case "critical" -> "critical";
            case "warning" -> "warning";
            default -> "info";
        });
        payload.put("source", "orqix");
        payload.put("timestamp", m.at().toString());
        payload.putObject("custom_details").put("signal", m.signal()).put("value", m.value())
                .put("threshold", m.threshold()).put("windowMinutes", m.windowMinutes()).put("scope", m.scope());
        return body;
    }

    private static String label(String kind) {
        return "email".equals(kind) ? "email" : "Teams";
    }

    private static ApiException invalid(String pointer, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError(pointer, "invalid", message)));
    }
}
