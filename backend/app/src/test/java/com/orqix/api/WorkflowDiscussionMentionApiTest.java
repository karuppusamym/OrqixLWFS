package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.notification.NotificationService;

import tools.jackson.databind.JsonNode;

/**
 * CMP01 (docs/build/S87): @mentions in the step and connection discussion. Suggestions only list people who can read the
 * workflow in this workspace; a posted comment keeps only mentions of such people that appear in the text; each kept
 * person gets exactly one in-app notification without the comment text; resolve and reopen never notify; read and
 * read-all work as for other notifications; audit records counts and ids only.
 */
class WorkflowDiscussionMentionApiTest extends com.orqix.support.RuntimeTestBase {

    private static final String BASE = "/workspaces/{ws}/workflows/{id}/discussion";
    private static final String CANDIDATES = BASE + "/mention-candidates";

    @Autowired
    NotificationService notificationService;

    @Autowired
    TransactionTemplate transactions;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID workflow() throws Exception {
        var response = api("dana.designer").post(Map.of("key", unique("ment"), "name", "Mentioned " + unique("w"),
                "templateId", "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING);
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return UUID.fromString(response.body().path("id").asString());
    }

    private JsonNode definition(UUID wf) throws Exception {
        return api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, wf).body().path("definition");
    }

    private UUID subject(String user) throws Exception {
        api(user).get("/me");
        return jdbc.sql("SELECT id FROM subject WHERE external_subject = :s").param("s", "kc-" + user)
                .query(UUID.class).single();
    }

    private Response post(String user, UUID wf, String element, String body, String parent, List<?> mentions)
            throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("elementId", element);
        payload.put("body", body);
        if (parent != null) {
            payload.put("parentId", parent);
        }
        if (mentions != null) {
            payload.put("mentions", mentions);
        }
        return api(user).post(payload, BASE, PURCHASING, wf);
    }

    private List<String> candidateNames(String user, UUID wf, String q) throws Exception {
        var response = q == null ? api(user).get(CANDIDATES, PURCHASING, wf)
                : api(user).get(CANDIDATES + "?q={q}", PURCHASING, wf, q);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        response.body().path("items").forEach(i -> names.add(i.path("displayName").asString()));
        return names;
    }

    private int mentionNotifications(UUID recipient, String commentId) {
        return jdbc.sql("""
                SELECT count(*) FROM notification WHERE recipient_subject_id = :r AND kind = 'discussionMention'
                  AND comment_id = CAST(:c AS uuid)
                """).param("r", recipient).param("c", commentId).query(Integer.class).single();
    }

    private int allMentionNotifications(UUID recipient, UUID wf) {
        return jdbc.sql("""
                SELECT count(*) FROM notification WHERE recipient_subject_id = :r AND kind = 'discussionMention'
                  AND workflow_id = :wf
                """).param("r", recipient).param("wf", wf).query(Integer.class).single();
    }

    @Test
    void suggestionsListOnlyPeopleWhoCanReadTheWorkflowInThisWorkspace() throws Exception {
        UUID wf = workflow();
        for (String user : List.of("dana.designer", "paul.publisher", "vic.viewer", "olga.operator", "rhea.requester",
                "wade.admin", "fred.facilities", "otto.outsider")) {
            subject(user);
        }
        // The shared test database holds many people, so each person is looked up by name.
        assertThat(candidateNames("dana.designer", wf, null)).doesNotContain("Dana Designer").hasSizeLessThanOrEqualTo(10);
        assertThat(candidateNames("dana.designer", wf, "Paul Publisher")).containsExactly("Paul Publisher");
        assertThat(candidateNames("dana.designer", wf, "vIc viewer")).containsExactly("Vic Viewer");
        assertThat(candidateNames("dana.designer", wf, "Olga Operator")).containsExactly("Olga Operator");
        for (String hidden : List.of("Dana Designer", "Rhea Requester", "Wade Admin", "Fred Facilities",
                "Otto Outsider")) {
            assertThat(candidateNames("dana.designer", wf, hidden)).as(hidden).isEmpty();
        }
        assertThat(candidateNames("dana.designer", wf, "%")).isEmpty(); // a literal %, not a wildcard
        // A publisher may ask (they may post); a viewer may read but not post, so not ask.
        assertThat(candidateNames("paul.publisher", wf, "dana")).containsExactly("Dana Designer");
        assertThat(api("vic.viewer").get(CANDIDATES, PURCHASING, wf).status()).isEqualTo(403);
        assertThat(api("rhea.requester").get(CANDIDATES, PURCHASING, wf).status()).isIn(403, 404);
        for (String user : List.of("fred.facilities", "otto.outsider")) {
            assertThat(api(user).get(CANDIDATES, PURCHASING, wf).status()).as(user).isEqualTo(404);
        }
        assertThat(api("dana.designer").get(CANDIDATES + "?q=" + "x".repeat(101), PURCHASING, wf).status()).isEqualTo(400);
    }

    @Test
    void mentionsAreFilteredStoredAndNotifiedOncePerPersonWithoutTheText() throws Exception {
        UUID wf = workflow();
        JsonNode def = definition(wf);
        String step = def.path("nodes").get(1).path("id").asString();
        String stepLabel = def.path("nodes").get(1).path("label").asString();
        UUID dana = subject("dana.designer");
        UUID paul = subject("paul.publisher");
        UUID vic = subject("vic.viewer");
        UUID olga = subject("olga.operator");
        UUID rhea = subject("rhea.requester");
        UUID fred = subject("fred.facilities");

        String text = "Secret threshold 4711: @Paul Publisher and @vic viewer please check; @Rhea Requester @Fred Facilities @Dana Designer";
        var root = post("dana.designer", wf, step, text, null,
                List.of(paul, vic, paul, rhea, fred, dana, olga, UUID.randomUUID()));
        assertThat(root.status()).as(root.toString()).isEqualTo(201);
        String rootId = root.body().path("id").asString();
        List<String> mentioned = new ArrayList<>();
        root.body().path("mentions").forEach(m -> mentioned.add(m.path("displayName").asString()));
        // Kept: people with workflow.read who are named in the text. Dropped: no access (requester, other workspace),
        // self, a person not named in the text (Olga), an unknown id, a duplicate.
        assertThat(mentioned).containsExactly("Paul Publisher", "Vic Viewer");
        assertThat(mentionNotifications(paul, rootId)).isEqualTo(1);
        assertThat(mentionNotifications(vic, rootId)).isEqualTo(1);
        for (UUID none : List.of(dana, olga, rhea, fred)) {
            assertThat(mentionNotifications(none, rootId)).isZero();
        }

        // The notification names who and where, links to the element, and never carries the comment text.
        JsonNode inbox = api("paul.publisher").get("/workspaces/{ws}/me/notifications?unread=true&limit=100", PURCHASING).body();
        JsonNode note = null;
        for (JsonNode n : inbox.path("items")) {
            if ("discussionMention".equals(n.path("kind").asString()) && wf.toString().equals(n.path("workflowId").asString())) {
                note = n;
            }
        }
        assertThat(note).isNotNull();
        assertThat(note.path("elementId").asString()).isEqualTo(step);
        assertThat(note.path("runId").isNull() || note.path("runId").isMissingNode()).isTrue();
        assertThat(note.path("message").asString()).contains("Dana Designer").contains(stepLabel)
                .doesNotContain("4711").doesNotContain("threshold");
        assertThat(jdbc.sql("SELECT string_agg(message, ' ') FROM notification WHERE comment_id = CAST(:c AS uuid)")
                .param("c", rootId).query(String.class).single()).doesNotContain("4711");

        // Replaying the notification step for the same comment and person never creates a second one.
        boolean again = transactions.execute(s -> notificationService.createMention(PURCHASING, paul, wf, step,
                UUID.fromString(rootId), "discussion-mention:" + rootId + ":" + paul, "replay"));
        assertThat(again).isFalse();
        assertThat(mentionNotifications(paul, rootId)).isEqualTo(1);

        // Every reader sees the mentions on the comment.
        JsonNode read = api("vic.viewer").get(BASE, PURCHASING, wf).body();
        assertThat(read.path("threads").get(0).path("root").path("mentions")).hasSize(2);

        // Resolve and reopen never notify again; a reply with a mention notifies once more (a new comment).
        int paulBefore = allMentionNotifications(paul, wf);
        assertThat(api("paul.publisher").put(Map.of("resolved", true), null, BASE + "/{c}/resolution", PURCHASING, wf, rootId)
                .status()).isEqualTo(200);
        assertThat(api("dana.designer").put(Map.of("resolved", false), null, BASE + "/{c}/resolution", PURCHASING, wf, rootId)
                .status()).isEqualTo(200);
        assertThat(allMentionNotifications(paul, wf)).isEqualTo(paulBefore);
        var reply = post("vic.viewer", wf, step, "@Paul Publisher ping", rootId, List.of(paul));
        assertThat(reply.status()).isEqualTo(403); // viewers cannot post, so cannot mention
        var publisherReply = post("paul.publisher", wf, step, "Thanks @Dana Designer", rootId, List.of(dana));
        assertThat(publisherReply.status()).as(publisherReply.toString()).isEqualTo(201);
        assertThat(mentionNotifications(dana, publisherReply.body().path("id").asString())).isEqualTo(1);
        assertThat(allMentionNotifications(paul, wf)).isEqualTo(paulBefore);

        // Audit: counts and ids only, never the text or the names.
        String metadata = jdbc.sql("SELECT metadata::text FROM audit_event WHERE action = 'workflow.comment.created' "
                + "AND metadata->>'commentId' = :c").param("c", rootId).query(String.class).single();
        assertThat(metadata).contains("\"mentionCount\": 2").contains("\"mentionsIgnored\": 5").contains("\"notified\": 2")
                .doesNotContain("4711").doesNotContain("Paul").doesNotContain(paul.toString());

        // More than 10 mentions is a malformed request; nothing is stored.
        List<UUID> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(UUID.randomUUID());
        }
        assertThat(post("dana.designer", wf, step, "too many", null, eleven).status()).isEqualTo(400);
        // A comment without mentions behaves exactly as before.
        var plain = post("dana.designer", wf, step, "no mentions here", null, null);
        assertThat(plain.status()).isEqualTo(201);
        assertThat(plain.body().path("mentions")).isEmpty();
    }

    @Test
    void personWhoLostAccessIsNeitherSuggestedNorNotified() throws Exception {
        UUID wf = workflow();
        String step = definition(wf).path("nodes").get(0).path("id").asString();
        String user = "mia." + UUID.randomUUID().toString().substring(0, 6);
        String name = "Mia " + Character.toUpperCase(user.charAt(4)) + user.substring(5);
        api(user, List.of("orqix-purchasing-viewers")).get("/me");
        UUID mia = jdbc.sql("SELECT id FROM subject WHERE external_subject = :s").param("s", "kc-" + user)
                .query(UUID.class).single();
        assertThat(candidateNames("dana.designer", wf, user.substring(4))).containsExactly(name);

        // Signing in again without the viewer group removes workflow.read (groups of the latest sign-in count).
        api(user, List.of()).get("/me");
        assertThat(candidateNames("dana.designer", wf, user.substring(4))).isEmpty();
        var posted = post("dana.designer", wf, step, "@" + name + " are you there?", null, List.of(mia));
        assertThat(posted.status()).isEqualTo(201);
        assertThat(posted.body().path("mentions")).isEmpty();
        assertThat(mentionNotifications(mia, posted.body().path("id").asString())).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_comment_mention WHERE subject_id = :s").param("s", mia)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void readAndReadAllWorkForMentionsAndStayWithinTheRecipient() throws Exception {
        UUID wf = workflow();
        String connection = definition(wf).path("transitions").get(0).path("id").asString();
        UUID olga = subject("olga.operator");
        UUID vic = subject("vic.viewer");
        String id = post("dana.designer", wf, connection, "@Olga Operator @Vic Viewer route check", null, List.of(olga, vic))
                .body().path("id").asString();
        assertThat(mentionNotifications(olga, id)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT message FROM notification WHERE comment_id = CAST(:c AS uuid) AND recipient_subject_id = :r")
                .param("c", id).param("r", olga).query(String.class).single()).contains("a connection");

        UUID noteId = jdbc.sql("SELECT id FROM notification WHERE comment_id = CAST(:c AS uuid) AND recipient_subject_id = :r")
                .param("c", id).param("r", olga).query(UUID.class).single();
        // Someone else's notification is not addressable.
        assertThat(api("vic.viewer").post(Map.of(), "/workspaces/{ws}/notifications/{n}/read", PURCHASING, noteId).status())
                .isEqualTo(404);
        assertThat(api("olga.operator").post(Map.of(), "/workspaces/{ws}/notifications/{n}/read", PURCHASING, noteId).status())
                .isEqualTo(204);

        var all = api("olga.operator").post(Map.of(), "/workspaces/{ws}/me/notifications/read-all", PURCHASING);
        assertThat(all.status()).as(all.toString()).isEqualTo(200);
        assertThat(all.body().path("markedRead").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(api("olga.operator").get("/workspaces/{ws}/me/notifications", PURCHASING).body().path("unreadCount").asInt())
                .isZero();
        var again = api("olga.operator").post(Map.of(), "/workspaces/{ws}/me/notifications/read-all", PURCHASING);
        assertThat(again.body().path("markedRead").asInt()).isZero();
        // Vic's mention is untouched by Olga's read-all.
        assertThat(jdbc.sql("SELECT read_at IS NULL FROM notification WHERE comment_id = CAST(:c AS uuid) AND recipient_subject_id = :r")
                .param("c", id).param("r", vic).query(Boolean.class).single()).isTrue();
        // Other workspaces cannot be reached.
        for (String user : List.of("fred.facilities", "otto.outsider")) {
            assertThat(api(user).post(Map.of(), "/workspaces/{ws}/me/notifications/read-all", PURCHASING).status())
                    .as(user).isEqualTo(404);
        }
    }
}
