package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;

import tools.jackson.databind.JsonNode;

/**
 * Emails guest links whose step names an address field (CG-F05, D-CG7) and sweeps guest links. The address is read
 * from the request when the link is sent and is never stored; its domain must be in the workspace's approved guest
 * domains at that moment, otherwise the link is not sent ({@code refused}) and an operator can approve the domain and
 * send again, or issue the link and share it. Rows are claimed with {@code FOR UPDATE SKIP LOCKED}, so every pod may
 * run this. Expired links are audited once; their files and the files of closed waits are deleted; closed links older
 * than 30 days are removed.
 */
@Component
@Profile("!migrate")
public class GuestLinkSender {

    private static final Logger LOG = LoggerFactory.getLogger(GuestLinkSender.class);
    static final Duration RETENTION = Duration.ofDays(30);
    static final int MAX_SEND_ATTEMPTS = 5;
    private static final Pattern ADDRESS = Pattern.compile(
            "^[A-Za-z0-9._%+-]{1,64}@([A-Za-z0-9-]{1,63}\\.)+[A-Za-z]{2,24}$");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm 'UTC'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    private final GuestResponseStore store;
    private final GuestResponseService guests;
    private final StageMailTransport mail;
    private final AuditWriter audit;
    private final RunEvents events;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private Instant lastPurge = Instant.MIN;

    public GuestLinkSender(GuestResponseStore store, GuestResponseService guests, StageMailTransport mail,
            AuditWriter audit, RunEvents events, TransactionTemplate transactions, Clock clock) {
        this.store = store;
        this.guests = guests;
        this.mail = mail;
        this.audit = audit;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${orqix.guest-links.sweep-delay:15s}",
            initialDelayString = "${orqix.guest-links.sweep-delay:15s}")
    public void sweep() {
        try {
            send();
            expire();
            Instant now = clock.instant();
            if (!now.isBefore(lastPurge.plus(Duration.ofMinutes(10)))) {
                lastPurge = now;
                purge(now);
            }
        } catch (RuntimeException e) {
            LOG.warn("Guest link sweep failed; it runs again shortly ({})", e.getClass().getSimpleName());
        }
    }

    /** One sending pass (also called by tests). @return links handled */
    public int send() {
        Integer handled = transactions.execute(status -> {
            List<GuestResponseStore.Row> rows = store.lockPending(20);
            for (GuestResponseStore.Row row : rows) {
                sendOne(row);
            }
            return rows.size();
        });
        return handled == null ? 0 : handled;
    }

    private void sendOne(GuestResponseStore.Row row) {
        String[] state = GuestResponseService.state(row, clock.instant());
        if ("submitted".equals(state[0]) || "cancelled".equals(state[0])) {
            store.sendState(row.id(), "manual", null, false);
            return;
        }
        JsonNode guest = guests.guestConfig(row);
        String ref = guest == null ? "" : guest.path("recipientFrom").asString("");
        String key = ref.startsWith("/input/") ? ref.substring("/input/".length()) : null;
        String address = key == null || row.input() == null ? null : row.input().path(key).asString(null);
        if (address == null || !ADDRESS.matcher(address.strip()).matches()) {
            refuse(row, "noAddress", null);
            return;
        }
        String domain = address.strip().substring(address.strip().indexOf('@') + 1).toLowerCase(Locale.ROOT);
        if (!store.domainApproved(row.workspaceId(), domain)) {
            refuse(row, "domainNotApproved", domain);
            return;
        }
        if (!mail.enabled()) {
            store.sendState(row.id(), "failed", domain, true);
            audit(row, "guest.link-refused", Map.of("linkId", row.id().toString(), "runId", row.runId().toString(),
                    "reason", "mailNotConfigured"));
            return;
        }
        GuestResponseService.Minted minted = guests.mintForEmail(row, domain);
        String title = guest.path("title").asString("a question").replaceAll("[\\r\\n\\t]+", " ").strip();
        String body = "You are asked to answer: " + title + "\n\n"
                + "Open this link to answer (it works once):\n" + minted.url() + "\n\n"
                + "The link expires on " + WHEN.format(minted.expiresAt()) + ".\n"
                + "If you did not expect this message, ignore it.\n";
        try {
            mail.send(row.id(), address.strip(), "Please answer: " + title, body);
        } catch (Exception e) {
            boolean last = row.sendAttempts() + 1 >= MAX_SEND_ATTEMPTS;
            store.revoke(row.id(), clock.instant());
            store.sendState(row.id(), last ? "failed" : "pending", domain, true);
            if (last) {
                audit(row, "guest.link-refused", Map.of("linkId", row.id().toString(), "runId",
                        row.runId().toString(), "reason", "mailFailed"));
            }
            return;
        }
        audit(row, "guest.link-sent", Map.of("linkId", row.id().toString(), "runId", row.runId().toString(),
                "domain", domain));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("domain", domain);
        data.put("expiresAt", minted.expiresAt().toString());
        events.append(row.workspaceId(), row.runId(), "GUEST_LINK_SENT", row.nodeId(), null, data);
    }

    private void refuse(GuestResponseStore.Row row, String reason, String domain) {
        store.sendState(row.id(), "refused", domain, true);
        audit(row, "guest.link-refused", Map.of("linkId", row.id().toString(), "runId", row.runId().toString(),
                "reason", reason));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason);
        data.put("domain", domain);
        events.append(row.workspaceId(), row.runId(), "GUEST_LINK_REFUSED", row.nodeId(), null, data);
    }

    /** Audits expired links once and deletes their files and the files of closed waits. @return expiries */
    public int expire() {
        Integer count = transactions.execute(status -> {
            List<UUID[]> expired = store.lockExpired(clock.instant(), 200);
            for (UUID[] row : expired) {
                store.expiryAudited(row[0]);
                audit.write(new AuditWriter.Entry(row[1], AuditWriter.ActorType.SYSTEM, null, "guest.link-expired",
                        "guestLink", row[0].toString(), AuditWriter.Outcome.SUCCESS,
                        Map.of("linkId", row[0].toString(), "runId", row[2].toString())));
            }
            store.deleteFilesOfClosed();
            return expired.size();
        });
        return count == null ? 0 : count;
    }

    /** Removes closed links older than the retention as of {@code now}. @return links removed */
    public int purge(Instant now) {
        Integer deleted = transactions.execute(status -> {
            List<UUID> workspaces = store.purgeClosed(now.minus(RETENTION), 500);
            Map<UUID, Integer> counts = new LinkedHashMap<>();
            workspaces.forEach(ws -> counts.merge(ws, 1, Integer::sum));
            counts.forEach((ws, n) -> audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.SYSTEM, null,
                    "guest.links-purged", "workspace", ws.toString(), AuditWriter.Outcome.SUCCESS,
                    Map.of("count", n, "retentionSeconds", RETENTION.toSeconds()))));
            return workspaces.size();
        });
        return deleted == null ? 0 : deleted;
    }

    private void audit(GuestResponseStore.Row row, String action, Map<String, ?> metadata) {
        audit.write(new AuditWriter.Entry(row.workspaceId(), AuditWriter.ActorType.SYSTEM, null, action, "guestLink",
                row.id().toString(), AuditWriter.Outcome.SUCCESS, metadata));
    }
}
