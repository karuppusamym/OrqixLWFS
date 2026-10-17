package com.orqix.hardening;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.orqix.resilience.Client;
import com.orqix.resilience.Node;
import com.orqix.resilience.Nodes;
import com.orqix.resilience.Resp;

/**
 * T-SEC-09 (in-JVM): a permission removed through one application instance reaches a second instance, which has
 * the old answer cached, within the policy cache bound ({@code orqix.policy.cache-ttl}; 3 s here, 60 s in
 * production). The instance that performed the change reflects it immediately (local cache eviction).
 *
 * <p>Two kinds of removal: membership (the caller drops out of the workspace: 404) and a single permission of a
 * caller who stays a member (403). The same procedure with the real 60 s bound on two Compose instances is
 * {@code tools/resilience/resilience.py revocation}.
 */
@Tag("resilience")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PermissionRevocationTest {

    private static final UUID SANDBOX = UUID.fromString("00000000-0000-7000-8000-000000000202");
    private static final Duration TTL = Duration.parse("PT" + Nodes.POLICY_TTL.toUpperCase());
    /** Polling and scheduling slack on top of the TTL; the bound itself is the TTL. */
    private static final Duration SLACK = Duration.ofMillis(1500);

    private Node a;
    private Node b;

    @BeforeAll
    void startBothInstances() {
        a = Node.start(Nodes.NodeA.class);
        b = Node.start(Nodes.NodeB.class);
        assertThat(a.lockOwner()).isNotEqualTo(b.lockOwner());
    }

    @AfterAll
    void stopBothInstances() {
        if (b != null) {
            b.stop();
        }
        if (a != null) {
            a.stop();
        }
    }

    @Test
    void tSec09_removedMembershipReachesTheSecondInstanceWithinTheCacheBound() throws Exception {
        String group = "sec09-member-" + suffix();
        String binding = createBinding(a, "VIEWER", group);
        Client onA = a.as("otto.outsider", List.of(group));
        Client onB = b.as("otto.outsider", List.of(group));
        assertThat(onA.get("/workspaces/{ws}", SANDBOX).status()).isEqualTo(200);
        assertThat(onB.get("/workspaces/{ws}", SANDBOX).status()).as("cached on the second instance").isEqualTo(200);
        long filled = System.nanoTime();

        deleteBinding(a, binding);
        long removed = System.nanoTime();
        assertThat(onA.get("/workspaces/{ws}", SANDBOX).status()).as("instance that changed it: immediate")
                .isEqualTo(404);

        boolean stale = onB.get("/workspaces/{ws}", SANDBOX).status() == 200;
        long convergedAfterMs = awaitStatus(onB, "/workspaces/{ws}", SANDBOX, 404, removed);
        System.out.printf("T-SEC-09 (membership): removed on %s; %s still answered from its cache right after: %b; "
                + "second instance converged %d ms after the change (cache entry was %d ms old at the change, "
                + "bound %d ms)%n", a.name(), b.name(), stale, convergedAfterMs,
                (removed - filled) / 1_000_000, TTL.toMillis());
        assertThat(convergedAfterMs).as("convergence after the removal").isLessThanOrEqualTo(TTL.plus(SLACK).toMillis());
        if ((removed - filled) / 1_000_000 < TTL.toMillis() / 2) {
            assertThat(stale).as("the second instance really served from its cache").isTrue();
        }
    }

    @Test
    void tSec09_removedSinglePermissionReachesTheSecondInstanceWithinTheCacheBound() throws Exception {
        String viewers = "sec09-viewer-" + suffix();
        String admins = "sec09-admin-" + suffix();
        createBinding(a, "VIEWER", viewers);
        String adminBinding = createBinding(a, "WORKSPACE_ADMIN", admins);
        List<String> groups = List.of(viewers, admins);
        Client onA = a.as("otto.outsider", groups);
        Client onB = b.as("otto.outsider", groups);
        assertThat(onA.get("/workspaces/{ws}/access", SANDBOX).status()).isEqualTo(200);
        assertThat(onB.get("/workspaces/{ws}/access", SANDBOX).status()).as("cached on the second instance")
                .isEqualTo(200);
        long filled = System.nanoTime();

        deleteBinding(a, adminBinding);
        long removed = System.nanoTime();
        Resp denied = onA.get("/workspaces/{ws}/access", SANDBOX);
        assertThat(denied.status()).as("instance that changed it: immediate").isEqualTo(403);
        assertThat(onA.get("/workspaces/{ws}", SANDBOX).status()).as("still a member through the viewer group")
                .isEqualTo(200);

        boolean stale = onB.get("/workspaces/{ws}/access", SANDBOX).status() == 200;
        long convergedAfterMs = awaitStatus(onB, "/workspaces/{ws}/access", SANDBOX, 403, removed);
        assertThat(onB.get("/workspaces/{ws}", SANDBOX).status()).isEqualTo(200);
        System.out.printf("T-SEC-09 (permission): removed on %s; %s stale right after: %b; second instance denied %d ms "
                + "after the change (cache entry %d ms old at the change, bound %d ms)%n", a.name(), b.name(), stale,
                convergedAfterMs, (removed - filled) / 1_000_000, TTL.toMillis());
        assertThat(convergedAfterMs).isLessThanOrEqualTo(TTL.plus(SLACK).toMillis());
        if ((removed - filled) / 1_000_000 < TTL.toMillis() / 2) {
            assertThat(stale).as("the second instance really served from its cache").isTrue();
        }
    }

    /** Polls {@code path} on the client until it answers {@code status}; returns ms since {@code since}. */
    private static long awaitStatus(Client client, String path, UUID ws, int status, long since) throws Exception {
        long deadline = System.nanoTime() + TTL.plus(Duration.ofSeconds(10)).toNanos();
        while (System.nanoTime() < deadline) {
            if (client.get(path, ws).status() == status) {
                return (System.nanoTime() - since) / 1_000_000;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the second instance still serves the revoked permission " + TTL.plusSeconds(10)
                + " after the change");
    }

    private static String createBinding(Node node, String role, String group) throws Exception {
        Resp created = node.as("wade.admin").post(Map.of("role", role,
                "principal", Map.of("type", "idpGroup", "value", group)), "/workspaces/{ws}/access/role-bindings",
                SANDBOX);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return created.body().path("id").asString();
    }

    private static void deleteBinding(Node node, String id) throws Exception {
        Resp deleted = node.as("wade.admin").delete("/workspaces/{ws}/access/role-bindings/{id}", SANDBOX, id);
        assertThat(deleted.status()).as(deleted.toString()).isEqualTo(204);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }
}
