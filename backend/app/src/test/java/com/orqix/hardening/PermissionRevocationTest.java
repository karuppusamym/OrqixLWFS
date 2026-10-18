package com.orqix.hardening;

import static org.assertj.core.api.Assertions.assertThat;

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
 * T-SEC-09: revocation is enforced on the next request on both application instances,
 * including an instance that previously resolved the caller's workspace access.
 * Membership removal returns 404; removal of one permission retains membership and returns 403.
 */
@Tag("resilience")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PermissionRevocationTest {

    private static final UUID SANDBOX = UUID.fromString("00000000-0000-7000-8000-000000000202");
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
    void tSec09_removedMembershipReachesTheSecondInstanceImmediately() throws Exception {
        String group = "sec09-member-" + suffix();
        String binding = createBinding(a, "VIEWER", group);
        Client onA = a.as("otto.outsider", List.of(group));
        Client onB = b.as("otto.outsider", List.of(group));
        assertThat(onA.get("/workspaces/{ws}", SANDBOX).status()).isEqualTo(200);
        assertThat(onB.get("/workspaces/{ws}", SANDBOX).status()).as("access previously resolved on the second instance").isEqualTo(200);

        deleteBinding(a, binding);
        assertThat(onA.get("/workspaces/{ws}", SANDBOX).status()).as("instance that changed it: immediate")
                .isEqualTo(404);

        assertThat(onB.get("/workspaces/{ws}", SANDBOX).status())
                .as("second instance must deny the first request after membership removal").isEqualTo(404);
    }

    @Test
    void tSec09_removedSinglePermissionReachesTheSecondInstanceImmediately() throws Exception {
        String viewers = "sec09-viewer-" + suffix();
        String admins = "sec09-admin-" + suffix();
        createBinding(a, "VIEWER", viewers);
        String adminBinding = createBinding(a, "WORKSPACE_ADMIN", admins);
        List<String> groups = List.of(viewers, admins);
        Client onA = a.as("otto.outsider", groups);
        Client onB = b.as("otto.outsider", groups);
        assertThat(onA.get("/workspaces/{ws}/access", SANDBOX).status()).isEqualTo(200);
        assertThat(onB.get("/workspaces/{ws}/access", SANDBOX).status()).as("access previously resolved on the second instance")
                .isEqualTo(200);

        deleteBinding(a, adminBinding);
        Resp denied = onA.get("/workspaces/{ws}/access", SANDBOX);
        assertThat(denied.status()).as("instance that changed it: immediate").isEqualTo(403);
        assertThat(onA.get("/workspaces/{ws}", SANDBOX).status()).as("still a member through the viewer group")
                .isEqualTo(200);

        assertThat(onB.get("/workspaces/{ws}/access", SANDBOX).status())
                .as("second instance must deny the first request after permission removal").isEqualTo(403);
        assertThat(onB.get("/workspaces/{ws}", SANDBOX).status())
                .as("viewer membership remains on the second instance").isEqualTo(200);
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
