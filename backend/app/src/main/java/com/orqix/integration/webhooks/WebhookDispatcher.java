package com.orqix.integration.webhooks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.integration.EgressPolicy;
import com.orqix.integration.internal.SecretStore;
import com.orqix.shared.web.Sql;

/**
 * Sends due webhook deliveries (docs/build/S99 B4-B9) on its own scheduler, so a slow receiver never holds the shared
 * one. Each delivery is claimed alone, right before it is sent, with a lease longer than the worst-case send (5 s
 * connect + 10 s answer); the result is written back only while that exact claim still holds (same attempt number and
 * lease), so a late pod can never overwrite a newer claim. A pass stops after {@link #PASS_BUDGET}, and a subscription
 * that used up {@link #SUBSCRIPTION_BUDGET} of a pass or failed once waits for the next pass. The connection goes to the
 * address the egress policy just vetted (no second DNS answer), with SNI, Host and certificate checks on the real host
 * name; redirects are never followed. Never logs a URL, secret, payload or response body.
 */
@Component
@Profile("!migrate")
public class WebhookDispatcher {

    static final Duration PASS_BUDGET = Duration.ofSeconds(20);
    static final Duration SUBSCRIPTION_BUDGET = Duration.ofSeconds(5);
    static final int CONNECT_TIMEOUT_MS = 5_000;
    static final int READ_TIMEOUT_MS = 10_000;
    /** Worst case after connecting: TLS handshake, sending and the status line together (a slow drip is cut off). */
    static final long ANSWER_DEADLINE_MS = 15_000;
    /** Prefix of the outbound signature; deliberately different from the inbound {@code sha256=} scheme. */
    public static final String SIGNATURE_PREFIX = "v1=";
    private static final Logger LOG = LoggerFactory.getLogger(WebhookDispatcher.class);

    record Claimed(UUID id, UUID workspaceId, UUID subscriptionId, String eventType, String payload, int attempts,
            Instant lease, String url, String secretRef) {
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

    @Scheduled(fixedDelayString = "${orqix.webhooks.dispatch-delay:5s}",
            initialDelayString = "${orqix.webhooks.dispatch-delay:5s}", scheduler = "webhookScheduler")
    public void tick() {
        try {
            run();
        } catch (RuntimeException e) {
            LOG.warn("Webhook dispatch failed; it runs again shortly ({})", e.getClass().getSimpleName());
        }
    }

    /** One pass (also called by tests). @return the number of deliveries attempted */
    public int run() {
        Instant passEnd = clock.instant().plus(PASS_BUDGET);
        Set<UUID> resting = new HashSet<>();
        Map<UUID, Long> spent = new HashMap<>();
        int attempted = 0;
        while (attempted < settings.batch() && clock.instant().isBefore(passEnd)) {
            Claimed c = transactions.execute(status -> claimOne(resting));
            if (c == null) {
                break;
            }
            long started = System.nanoTime();
            Result result = send(c);
            long millis = (System.nanoTime() - started) / 1_000_000L;
            transactions.executeWithoutResult(status -> finish(c, result));
            attempted++;
            long total = spent.merge(c.subscriptionId(), millis, Long::sum);
            if (!result.delivered() || total >= SUBSCRIPTION_BUDGET.toMillis()) {
                resting.add(c.subscriptionId());
            }
        }
        if (ThreadLocalRandom.current().nextInt(20) == 0) {
            purge();
        }
        return attempted;
    }

    /** Claims the next due delivery of a subscription that is not resting in this pass, with a fresh lease. */
    Claimed claimOne(Set<UUID> resting) {
        Instant now = clock.instant();
        String skip = "{" + String.join(",", resting.stream().map(UUID::toString).toList()) + "}";
        Claimed due = jdbc.sql("""
                SELECT d.id, d.workspace_id, d.subscription_id, d.event_type, d.payload::text AS payload, d.attempts,
                       s.url, s.secret_ref
                FROM webhook_delivery d JOIN webhook_subscription s ON s.id = d.subscription_id
                WHERE s.enabled AND d.next_attempt_at <= :now
                  AND (d.status = 'pending' OR (d.status = 'sending' AND d.lease_until < :now))
                  AND NOT (d.subscription_id = ANY (CAST(:skip AS uuid[])))
                ORDER BY d.next_attempt_at, d.id
                LIMIT 1
                FOR UPDATE OF d SKIP LOCKED
                """).param("now", Sql.ts(now)).param("skip", skip)
                .query((rs, n) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                        rs.getObject("subscription_id", UUID.class), rs.getString("event_type"), rs.getString("payload"),
                        rs.getInt("attempts") + 1, null, rs.getString("url"), rs.getString("secret_ref")))
                .optional().orElse(null);
        if (due == null) {
            return null;
        }
        // Microsecond precision, as stored, so the fence in finish() compares equal values.
        Instant lease = now.plus(settings.lease()).truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("UPDATE webhook_delivery SET status = 'sending', attempts = :attempts, lease_until = :lease WHERE id = :id")
                .param("attempts", due.attempts()).param("lease", Sql.ts(lease)).param("id", due.id()).update();
        return new Claimed(due.id(), due.workspaceId(), due.subscriptionId(), due.eventType(), due.payload(),
                due.attempts(), lease, due.url(), due.secretRef());
    }

    Result send(Claimed c) {
        EgressPolicy.Checked target;
        try {
            target = egress.checkAndResolve(c.url());
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
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", "Orqix-Webhooks/1");
        headers.put("X-Orqix-Webhook-Id", c.id().toString());
        headers.put("X-Orqix-Event", c.eventType());
        headers.put("X-Orqix-Timestamp", timestamp);
        headers.put("X-Orqix-Signature", SIGNATURE_PREFIX + sign(secret, c.id().toString(), timestamp, body));
        try {
            int status = post(target, URI.create(c.url()), headers, body);
            return status >= 200 && status < 300 ? new Result(true, false, status, null)
                    : new Result(false, false, status, "http_status");
        } catch (SocketTimeoutException e) {
            return new Result(false, false, 0, "timeout");
        } catch (IOException e) {
            return new Result(false, false, 0, "connection_failed");
        } catch (IllegalArgumentException e) {
            return new Result(false, true, 0, "invalid_url");
        }
    }

    /**
     * Outbound signature (S99 B4): hex HMAC-SHA256 over {@code "orqix-webhook-v1." + webhookId + "." + timestamp + "."
     * + body}. The domain prefix and the id make it unusable as an inbound-event signature, which covers
     * {@code timestamp + "." + body} only.
     */
    public static String sign(String secret, String webhookId, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(("orqix-webhook-v1." + webhookId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * One HTTP/1.1 POST to the vetted addresses only. TLS keeps SNI and certificate checks on the host name; the status
     * line is read, the rest of the answer is discarded unread.
     */
    static int post(EgressPolicy.Checked target, URI uri, Map<String, String> headers, byte[] body) throws IOException {
        IOException last = new ConnectException("no address");
        for (InetAddress address : target.addresses()) {
            Socket raw = new Socket();
            try {
                raw.connect(new InetSocketAddress(address, target.port()), CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                raw.close();
                last = e;
                continue; // only a refused connection moves on to the next vetted address
            }
            long deadline = System.nanoTime() + ANSWER_DEADLINE_MS * 1_000_000L;
            raw.setSoTimeout(READ_TIMEOUT_MS);
            Socket opened;
            try {
                opened = "https".equals(target.scheme()) ? tls(raw, target.host(), target.port()) : raw;
            } catch (IOException | RuntimeException e) {
                raw.close();
                throw e;
            }
            try (Socket socket = opened) {
                socket.setSoTimeout(READ_TIMEOUT_MS);
                String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
                boolean defaultPort = target.port() == ("https".equals(target.scheme()) ? 443 : 80);
                String hostHeader = (target.host().contains(":") ? "[" + target.host() + "]" : target.host())
                        + (defaultPort ? "" : ":" + target.port());
                StringBuilder head = new StringBuilder("POST ").append(path).append(" HTTP/1.1\r\nHost: ")
                        .append(hostHeader).append("\r\n");
                headers.forEach((k, v) -> head.append(k).append(": ").append(v).append("\r\n"));
                head.append("Content-Length: ").append(body.length).append("\r\nConnection: close\r\n\r\n");
                OutputStream out = socket.getOutputStream();
                out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
                return status(socket.getInputStream(), deadline);
            }
        }
        throw last;
    }

    private static Socket tls(Socket raw, String host, int port) throws IOException {
        SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
        SSLParameters parameters = ssl.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (!host.contains(":") && !host.matches("^[0-9.]+$")) {
            parameters.setServerNames(List.of(new SNIHostName(host)));
        }
        ssl.setSSLParameters(parameters);
        ssl.setSoTimeout(READ_TIMEOUT_MS);
        ssl.startHandshake();
        return ssl;
    }

    private static int status(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (System.nanoTime() > deadline) {
                throw new SocketTimeoutException("answer too slow");
            }
            if (line.size() >= 1024) {
                throw new IOException("status line too long");
            }
            line.write(b);
        }
        String[] parts = line.toString(StandardCharsets.US_ASCII).trim().split(" ");
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) {
            throw new IOException("not an HTTP answer");
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("not an HTTP status");
        }
    }

    /** Writes the result back only if this exact claim (attempt number and lease) is still the current one. */
    int finish(Claimed c, Result result) {
        Instant now = clock.instant();
        if (result.delivered()) {
            return jdbc.sql("""
                    UPDATE webhook_delivery SET status = 'delivered', delivered_at = :now, lease_until = NULL,
                           last_status_code = :code, last_error = NULL
                    WHERE id = :id AND status = 'sending' AND attempts = :attempts AND lease_until = :lease
                    """).param("now", Sql.ts(now)).param("code", result.status()).param("id", c.id())
                    .param("attempts", c.attempts()).param("lease", Sql.ts(c.lease())).update();
        }
        boolean dead = result.permanent() || c.attempts() >= settings.maxAttempts();
        Integer code = result.status() == 0 ? null : result.status();
        if (dead) {
            int changed = jdbc.sql("""
                    UPDATE webhook_delivery SET status = 'dead', dead_at = :now, lease_until = NULL, last_status_code = :code,
                           last_error = :error
                    WHERE id = :id AND status = 'sending' AND attempts = :attempts AND lease_until = :lease
                    """).param("now", Sql.ts(now)).param("code", code).param("error", result.error())
                    .param("id", c.id()).param("attempts", c.attempts()).param("lease", Sql.ts(c.lease())).update();
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
            return changed;
        }
        return jdbc.sql("""
                UPDATE webhook_delivery SET status = 'pending', next_attempt_at = :next, lease_until = NULL,
                       last_status_code = :code, last_error = :error
                WHERE id = :id AND status = 'sending' AND attempts = :attempts AND lease_until = :lease
                """).param("next", Sql.ts(now.plus(backoff(c.attempts())))).param("code", code)
                .param("error", result.error()).param("id", c.id()).param("attempts", c.attempts())
                .param("lease", Sql.ts(c.lease())).update();
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
