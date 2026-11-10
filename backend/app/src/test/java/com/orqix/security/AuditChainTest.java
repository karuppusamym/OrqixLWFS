package com.orqix.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.orqix.governance.AuditChains;
import com.orqix.support.RuntimeTestBase;
import com.orqix.support.TestDatabase;

import tools.jackson.databind.JsonNode;

/**
 * CG-I06 (docs/build/S151): every audit event joins its workspace's hash chain, verification reports the first broken
 * link, and exports are signed. Tampering is simulated in a workspace created for the test, with the database owner
 * (the application role cannot change audit rows), and the workspace is left with its break recorded.
 */
class AuditChainTest extends RuntimeTestBase {

    @Autowired
    AuditChains chains;

    private UUID newWorkspace() throws Exception {
        String key = "chain-" + UUID.randomUUID().toString().substring(0, 8);
        Response org = api("pat.platform").post(Map.of("key", key, "name", "Chain " + key), "/admin/organizations");
        assertThat(org.status()).as(org.toString()).isEqualTo(201);
        Response ws = api("pat.platform").post(Map.of("organizationId", org.body().path("id").asString(),
                "key", "ws-" + key, "name", "Chain " + key, "isTest", false, "allowSelfApprovalWorkflows", false,
                "initialAdmins", java.util.List.of(Map.of("type", "idpGroup", "value", "orqix-chain-admins"))),
                "/admin/workspaces");
        assertThat(ws.status()).as(ws.toString()).isEqualTo(201);
        return UUID.fromString(ws.body().path("id").asString());
    }

    @Test
    void eventsAreChainedPerWorkspaceAndVerificationNeedsAuditRead() throws Exception {
        assertThat(api("olga.operator").post(null, "/workspaces/{ws}/audit/chain-verifications", PURCHASING).status())
                .isEqualTo(403);
        assertThat(api("fred.facilities").post(null, "/workspaces/{ws}/audit/chain-verifications", PURCHASING)
                .status()).isEqualTo(404);
        // Every row has a position and hashes; positions are gapless and each links to the previous hash.
        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit_event a JOIN audit_event p
                  ON COALESCE(p.workspace_id::text, 'platform') = COALESCE(a.workspace_id::text, 'platform')
                 AND p.chain_seq = a.chain_seq - 1
                WHERE a.workspace_id = :ws AND a.prev_hash <> p.hash
                """).param("ws", PURCHASING).query(Long.class).single()).isZero();
        AuditChains.Result result = chains.verify(PURCHASING.toString());
        assertThat(result.intact()).as(result.toString()).isTrue();
        assertThat(result.checked()).isPositive();
    }

    @Test
    void verificationReportsTheFirstBrokenLinkAndExportsAreSigned() throws Exception {
        UUID ws = newWorkspace();
        Api api = api("otto.outsider", java.util.List.of("orqix-chain-admins"));
        // Give the chain a few events: three access changes by the workspace administrator.
        for (int i = 0; i < 3; i++) {
            Response grant = api.post(Map.of("permission", "audit.read", "principal",
                    Map.of("type", "idpGroup", "value", "orqix-chain-admins")), "/workspaces/{ws}/access/grants", ws);
            if (grant.status() == 201) {
                continue;
            }
            assertThat(grant.status()).as(grant.toString()).isEqualTo(409);
        }
        Response verified = api.post(null, "/workspaces/{ws}/audit/chain-verifications", ws);
        assertThat(verified.status()).as(verified.toString()).isEqualTo(200);
        assertThat(verified.body().path("intact").asBoolean()).isTrue();

        // Signed export: a verifier checks the JWS with the published key and recomputes the segment's links.
        Response exported = api.get("/workspaces/{ws}/audit/export?afterSeq=0", ws);
        assertThat(exported.status()).as(exported.toString()).isEqualTo(200);
        JsonNode keys = api.get("/workspaces/{ws}/audit/signing-keys", ws).body().path("keys");
        ECKey key = ECKey.parse(keys.get(0).toString());
        JWSObject jws = JWSObject.parse(exported.body().path("jws").asString());
        assertThat(jws.verify(new ECDSAVerifier(key))).isTrue();
        JsonNode payload = json.parse(new String(jws.getPayload().toBytes(), StandardCharsets.UTF_8));
        JsonNode events = payload.path("events");
        assertThat(events.size()).isEqualTo(exported.body().path("eventCount").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(events.get(0).path("prevHash").asString()).isEqualTo("0".repeat(64));
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).path("prevHash").asString()).isEqualTo(events.get(i - 1).path("hash").asString());
        }
        assertThat(api("olga.operator").get("/workspaces/{ws}/audit/export", PURCHASING).status()).isEqualTo(403);

        // Tamper with the second event as the database owner: the check reports exactly that position.
        var owner = TestDatabase.legacyFixtureJdbc();
        owner.sql("UPDATE orqix.audit_event SET outcome = 'failed' WHERE workspace_id = :ws AND chain_seq = 2")
                .param("ws", ws).update();
        AuditChains.Result broken = chains.verify(ws.toString());
        assertThat(broken.intact()).isFalse();
        assertThat(broken.brokenSeq()).isEqualTo(2L);
        assertThat(broken.reason()).isEqualTo("hashMismatch");
        Response reported = api.post(null, "/workspaces/{ws}/audit/chain-verifications", ws);
        assertThat(reported.body().path("intact").asBoolean()).isFalse();
        assertThat(reported.body().path("brokenSeq").asLong()).isEqualTo(2L);
        assertThat(api.get("/workspaces/{ws}/audit/chain-verifications", ws).body().path("items").get(0)
                .path("intact").asBoolean()).isFalse();

        // Removing the last event is caught by the head.
        owner.sql("UPDATE orqix.audit_event SET outcome = 'success' WHERE workspace_id = :ws AND chain_seq = 2")
                .param("ws", ws).update();
        owner.sql("DELETE FROM orqix.audit_event WHERE workspace_id = :ws AND chain_seq ="
                + " (SELECT max(chain_seq) FROM orqix.audit_event WHERE workspace_id = :ws)").param("ws", ws).update();
        AuditChains.Result truncated = chains.verify(ws.toString());
        assertThat(truncated.intact()).isFalse();
        assertThat(truncated.reason()).isEqualTo("headMismatch");
    }
}
