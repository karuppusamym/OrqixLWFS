package com.orqix.integration.webhooks;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.integration.EgressPolicy;
import com.orqix.integration.adapters.InboundService;
import com.orqix.integration.internal.SecretStore;
import com.orqix.shared.web.Sql;

/**
 * Sends due webhook deliveries (docs/build/S99 B4-B9). Safe on every pod: rows are claimed with {@code FOR UPDATE SKIP
 * LOCKED} and a lease, sent outside any transaction, and the result is written back only while the lease is ours.
 * Never logs a URL, secret, payload or response body.
 */
@Component
@Profile("!migrate")
public class WebhookDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookDispatcher.class);

    private record Claimed(UUID id, UUID workspaceId, UUID subscriptionId, String eventType, String payload, int attempts,
            String url, String secretRef) {
    }

    /** {@code status} 0 means no HTTP answer. */
    record Result(boolean delivered, boolean permanent, int status, String error) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final EgressPolicy egress;
    private final SecretStore secrets;
    private final WebhookSettings settings;
    private final AuditWriter audit;
    private final Clock clock;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build();

    public WebhookDispatcher(JdbcClient jdbc, TransactionTemplate transactions, EgressPolicy egress, SecretStore secrets,
            WebhookSettings settings, AuditWriter audit, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.egress = egress;
        this.secrets = secrets;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${orqix.webhooks.dispatch-delay:5s}", initialDelayString = "${orqix.webhooks.dispatch-delay:5s}")
    public void tick() {
        try {
            run();
        } catch (RuntimeException e) {
            LOG.warn("Webhook dispatch failed; it runs again shortly ({})", e.getClass().getSimpleName());
        }
    }

    /** One pass (also called by tests). @return the number of deliveries attempted */
    public int run() {
        List<Claimed> claimed = transactions.execute(status -> claim());
        if (claimed == null) {
            return 0;
        }
        for (Claimed c : claimed) {
            Result result = send(c);
            transactions.executeWithoutResult(status -> finish(c, result));
        }
        if (ThreadLocalRandom.current().nextInt(20) == 0) {
            purge();
        }
        return claimed.size();
    }

    private List<Claimed> claim() {
        Instant now = clock.instant();
        List<Claimed> due = jdbc.sql("""
                SELECT d.id, d.workspace_id, d.subscription_id, d.event_type, d.payload::text AS payload, d.attempts,
                       s.url, s.secret_ref
                FROM webhook_delivery d JOIN webhook_subscription s ON s.id = d.subscription_id
                WHERE s.enabled AND d.next_attempt_at <= :now
                  AND (d.status = 'pending' OR (d.status = 'sending' AND d.lease_until < :now))
                ORDER BY d.next_attempt_at, d.id
                LIMIT :batch
                FOR UPDATE OF d SKIP LOCKED
                """).param("now", Sql.ts(now)).param("batch", settings.batch())
                .query((rs, n) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                        rs.getObject("subscription_id", UUID.class), rs.getString("event_type"), rs.getString("payload"),
                        rs.getInt("attempts") + 1, rs.getString("url"), rs.getString("secret_ref")))
                .list();
        for (Claimed c : due) {
            jdbc.sql("UPDATE webhook_delivery SET status = 'sending', attempts = :attempts, lease_until = :lease WHERE id = :id")
                    .param("attempts", c.attempts()).param("lease", Sql.ts(now.plus(settings.lease())))
                    .param("id", c.id()).update();
        }
        return due;
    }

    Result send(Claimed c) {
        try {
            egress.check(c.url());
        } catch (EgressPolicy.EgressDeniedException e) {
            boolean dns = e.denial() == EgressPolicy.Denial.DNS_FAILURE;
            return new Result(false, !dns, 0, dns ? "dns_failure" : "egress_denied");
        }
        String secret = secrets.read(c.secretRef()).orElse(null);
        if (secret == null) {
            return new Result(false, false, 0, "secret_unavailable");
        }
        byte[] body = c.payload().getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        HttpRequest request = HttpRequest.newBuilder(URI.create(c.url())).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Orqix-Webhooks/1")
                .header("X-Orqix-Webhook-Id", c.id().toString())
                .header("X-Orqix-Event", c.eventType())
                .header("X-Orqix-Timestamp", timestamp)
                .header("X-Orqix-Signature", "sha256=" + InboundService.sign(secret, timestamp, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return status >= 200 && status < 300 ? new Result(true, false, status, null)
                    : new Result(false, false, status, "http_status");
        } catch (HttpTimeoutException e) {
            return new Result(false, false, 0, "timeout");
        } catch (IOException e) {
            return new Result(false, false, 0, "connection_failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, false, 0, "interrupted");
        } catch (IllegalArgumentException e) {
            return new Result(false, true, 0, "invalid_url");
        }
    }

    private void finish(Claimed c, Result result) {
        Instant now = clock.instant();
        if (result.delivered()) {
            jdbc.sql("""
                    UPDATE webhook_delivery SET status = 'delivered', delivered_at = :now, lease_until = NULL,
                           last_status_code = :code, last_error = NULL
                    WHERE id = :id AND status = 'sending'
                    """).param("now", Sql.ts(now)).param("code", result.status()).param("id", c.id()).update();
            return;
        }
        boolean dead = result.permanent() || c.attempts() >= settings.maxAttempts();
        Integer code = result.status() == 0 ? null : result.status();
        if (dead) {
            int changed = jdbc.sql("""
                    UPDATE webhook_delivery SET status = 'dead', dead_at = :now, lease_until = NULL, last_status_code = :code,
                           last_error = :error
                    WHERE id = :id AND status = 'sending'
                    """).param("now", Sql.ts(now)).param("code", code).param("error", result.error())
                    .param("id", c.id()).update();
            if (changed == 1) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("subscriptionId", c.subscriptionId().toString());
                metadata.put("eventType", c.eventType());
                metadata.put("attempts", c.attempts());
                metadata.put("error", result.error());
                metadata.put("statusCode", code);
                audit.write(new AuditWriter.Entry(c.workspaceId(), AuditWriter.ActorType.SYSTEM, null,
                        "webhook.delivery-dead", "webhookDelivery", c.id().toString(), AuditWriter.Outcome.FAILED,
                        metadata));
            }
            return;
        }
        jdbc.sql("""
                UPDATE webhook_delivery SET status = 'pending', next_attempt_at = :next, lease_until = NULL,
                       last_status_code = :code, last_error = :error
                WHERE id = :id AND status = 'sending'
                """).param("next", Sql.ts(now.plus(backoff(c.attempts())))).param("code", code)
                .param("error", result.error()).param("id", c.id()).update();
    }

    /** {@code initial x 2^(attempt-1)}, capped, plus up to 10 % jitter. */
    Duration backoff(int attempt) {
        long initial = settings.initialBackoff().toMillis();
        long max = settings.maxBackoff().toMillis();
        long base = initial << Math.min(30, Math.max(0, attempt - 1));
        long capped = base <= 0 || base > max ? max : base;
        return Duration.ofMillis(capped + ThreadLocalRandom.current().nextLong(capped / 10 + 1));
    }

    private void purge() {
        Instant now = clock.instant();
        jdbc.sql("DELETE FROM webhook_delivery WHERE status = 'delivered' AND delivered_at < :cutoff")
                .param("cutoff", Sql.ts(now.minus(Duration.ofDays(7)))).update();
        jdbc.sql("DELETE FROM webhook_delivery WHERE status = 'dead' AND dead_at < :cutoff")
                .param("cutoff", Sql.ts(now.minus(Duration.ofDays(30)))).update();
    }
}
