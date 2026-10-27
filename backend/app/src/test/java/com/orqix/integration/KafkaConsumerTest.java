package com.orqix.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import com.orqix.integration.internal.SecretStore;
import com.orqix.integration.kafka.KafkaConsumerRunner;
import com.orqix.integration.kafka.KafkaRecordHandler;
import com.orqix.integration.kafka.KafkaSettings;
import com.orqix.integration.kafka.KafkaTriggerService;
import com.orqix.shared.json.Json;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * IN05 (c) against a real, throw-away Kafka broker (Testcontainers, apache/kafka): a record starts a request, a keyed
 * repeat is dropped, a malformed record is dead-lettered while later records still flow, a restarted consumer does not
 * reprocess committed records, a resume trigger reaches a waiting request by correlation key, and two replicas in one
 * consumer group each own one partition and fail over when one stops. The second replica is a second consumer runtime
 * with its own instance id in this JVM against the same database and broker (not a second process).
 */
class KafkaConsumerTest extends RuntimeTestBase {

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");
    private static final List<String> TOPICS = List.of("kt-start", "kt-bad", "kt-restart", "kt-resume", "kt-replicas");

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        KAFKA.start();
        registry.add("orqix.kafka.mode", () -> "live");
        registry.add("orqix.kafka.instance-id", () -> "replica-a");
        // Reconcile only when a test asks, so starting and stopping consumers is deterministic.
        registry.add("orqix.kafka.reconcile-delay", () -> "1h");
        registry.add("orqix.kafka.clusters.local.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("orqix.kafka.clusters.local.security-protocol", () -> "PLAINTEXT");
        registry.add("orqix.kafka.clusters.local.allowed-topics", () -> String.join(",", TOPICS));
    }

    @Autowired
    private KafkaConsumerRunner runner;

    @Autowired
    private KafkaSettings settings;

    @Autowired
    private KafkaTriggerService triggers;

    @Autowired
    private KafkaRecordHandler handler;

    @Autowired
    private SecretStore secrets;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private Json jsonCodec;

    @Autowired
    private Clock clock;

    private KafkaConsumerRunner replicaB;

    /** The cap is 5 triggers per workspace and the database is shared, so each test starts without any (owner connection). */
    @org.junit.jupiter.api.BeforeEach
    void removeKafkaTriggers() {
        var owner = com.orqix.support.TestDatabase.legacyFixtureJdbc();
        owner.sql("DELETE FROM orqix.kafka_inbound_record WHERE workspace_id = :ws").param("ws", PURCHASING).update();
        owner.sql("DELETE FROM orqix.kafka_trigger_status WHERE workspace_id = :ws").param("ws", PURCHASING).update();
        owner.sql("DELETE FROM orqix.kafka_trigger WHERE workspace_id = :ws").param("ws", PURCHASING).update();
    }

    @AfterEach
    void stop() {
        runner.stopAll();
        if (replicaB != null) {
            replicaB.stopAll();
        }
        // No trigger of this class may keep consuming in later tests of the shared context.
        jdbc.sql("UPDATE kafka_trigger SET enabled = false WHERE topic LIKE 'kt-%'").update();
    }

    private static void createTopic(String topic, int partitions) throws Exception {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (Admin admin = Admin.create(p)) {
            if (!admin.listTopics().names().get().contains(topic)) {
                admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
            }
        }
    }

    private static void produce(String topic, Map<String, byte[]> records) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(p)) {
            records.forEach((key, value) -> producer.send(new ProducerRecord<>(topic,
                    key == null ? null : key.getBytes(StandardCharsets.UTF_8), value)));
            producer.flush();
        }
    }

    private byte[] order(int amount) {
        Map<String, Object> value = new LinkedHashMap<>(input(BigDecimal.valueOf(amount)));
        return jsonCodec.write(value).getBytes(StandardCharsets.UTF_8);
    }

    private void grant(String permission, UUID workflowId) throws Exception {
        UUID wade = UUID.fromString(api("wade.admin").get("/me").body().path("subject").path("id").asString());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("permission", permission);
        if (workflowId != null) {
            body.put("workflowId", workflowId);
        }
        body.put("principal", Map.of("type", "subject", "value", wade.toString()));
        assertThat(api("wade.admin").post(body, "/workspaces/{ws}/access/grants", PURCHASING).status()).isIn(201, 409);
    }

    private UUID startTrigger(String topic, UUID workflowId) throws Exception {
        grant("workflow.start", workflowId);
        Response created = api("wade.admin").post(Map.of("key", topic + "-" + UUID.randomUUID().toString().substring(0, 6),
                "name", "Orders " + topic, "cluster", "local", "topic", topic, "action", "start", "workflowId", workflowId,
                "mapping", Map.of("title", "/title", "purpose", "/purpose", "costCenter", "/costCenter", "amount", "/amount"),
                "startFrom", "earliest", "batchSize", 20, "maxPerSecond", 200), "/workspaces/{ws}/kafka-triggers", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return UUID.fromString(created.body().path("id").asString());
    }

    private long records(UUID trigger, String status) {
        return jdbc.sql("SELECT count(*) FROM kafka_inbound_record WHERE trigger_id = :t AND status = :s")
                .param("t", trigger).param("s", status).query(Long.class).single();
    }

    private JsonNode status(UUID trigger) throws Exception {
        for (JsonNode t : api("olga.operator").get("/workspaces/{ws}/kafka-triggers", PURCHASING).body().path("items")) {
            if (t.path("id").asString().equals(trigger.toString())) {
                return t.path("status");
            }
        }
        throw new AssertionError("trigger not listed");
    }

    private List<Integer> partitionsOf(UUID trigger, String instance) {
        return jdbc.sql("SELECT partitions FROM kafka_trigger_status WHERE trigger_id = :t AND instance_id = :i AND running")
                .param("t", trigger).param("i", instance)
                .query((rs, n) -> List.of((Integer[]) rs.getArray(1).getArray())).optional().orElse(List.of());
    }

    @Test
    void aRecordStartsARequestOnceAndAKeyedRepeatIsDropped() throws Exception {
        createTopic("kt-start", 1);
        UUID wf = publishPurchase(PURCHASING, null);
        UUID trigger = startTrigger("kt-start", wf);
        runner.reconcile();
        Map<String, byte[]> batch = new LinkedHashMap<>();
        batch.put("po-1", order(410));
        produce("kt-start", batch);
        produce("kt-start", batch); // the same key again: a duplicate delivery
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> records(trigger, "started") == 1
                && status(trigger).path("duplicates").asLong() >= 1);
        UUID run = jdbc.sql("SELECT run_id FROM kafka_inbound_record WHERE trigger_id = :t").param("t", trigger)
                .query(UUID.class).single();
        assertThat(run(PURCHASING, run).path("input").path("amount").decimalValue()).isEqualByComparingTo("410");
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_run WHERE workflow_id = :w").param("w", wf).query(Long.class)
                .single()).isEqualTo(1);
        JsonNode s = status(trigger);
        assertThat(s.path("replicas")).hasSize(1);
        assertThat(s.path("replicas").get(0).path("instanceId").asString()).isEqualTo("replica-a");
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> status(trigger).path("lag").asLong() == 0);
    }

    @Test
    void malformedAndUnmappedRecordsAreDeadLetteredAndTheConsumerKeepsGoing() throws Exception {
        createTopic("kt-bad", 1);
        UUID wf = publishPurchase(PURCHASING, null);
        UUID trigger = startTrigger("kt-bad", wf);
        runner.reconcile();
        Map<String, byte[]> batch = new LinkedHashMap<>();
        batch.put("bad-1", "{not json".getBytes(StandardCharsets.UTF_8));
        batch.put("bad-2", "{\"title\":\"only a title\"}".getBytes(StandardCharsets.UTF_8));
        batch.put("bad-3", new byte[70 * 1024]);
        batch.put("good-1", order(55));
        produce("kt-bad", batch);
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> records(trigger, "dead") == 3
                && records(trigger, "started") == 1);
        JsonNode dead = api("olga.operator").get("/workspaces/{ws}/kafka-dead-letters", PURCHASING).body();
        assertThat(dead.path("items").findValuesAsString("errorCode")).contains("malformed", "unmapped", "too_large");
        assertThat(dead.toString()).doesNotContain("only a title").doesNotContain("bad-2");
        // Status rows are written about once a second.
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !status(trigger).path("lastError").asString("").isBlank()
                && status(trigger).path("dead").asLong() == 3);
        String audit = jdbc.sql("SELECT string_agg(metadata::text, ' ') FROM audit_event WHERE action = 'kafka.record-dead' AND resource_id = :t")
                .param("t", trigger.toString()).query(String.class).single();
        assertThat(audit).doesNotContain("only a title").doesNotContain("bad-2");
    }

    @Test
    void aRestartedConsumerDoesNotReprocessCommittedRecords() throws Exception {
        createTopic("kt-restart", 1);
        UUID wf = publishPurchase(PURCHASING, null);
        UUID trigger = startTrigger("kt-restart", wf);
        runner.reconcile();
        Map<String, byte[]> batch = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            batch.put("r-" + i, order(100 + i));
        }
        produce("kt-restart", batch);
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> records(trigger, "started") == 5);
        runner.stopAll();
        // Restart: the same consumer group continues after the committed offsets.
        runner.reconcile();
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> !partitionsOf(trigger, "replica-a").isEmpty());
        Map<String, byte[]> more = new LinkedHashMap<>();
        more.put("r-5", order(200));
        produce("kt-restart", more);
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> records(trigger, "started") == 6);
        Thread.sleep(2000);
        // Counters restart with the consumer: no duplicate was seen, so nothing was delivered twice.
        assertThat(status(trigger).path("duplicates").asLong()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_run WHERE workflow_id = :w").param("w", wf).query(Long.class)
                .single()).isEqualTo(6);
    }

    @Test
    void aResumeTriggerReachesTheWaitingRequestByCorrelationKey() throws Exception {
        createTopic("kt-resume", 1);
        ObjectNode example = (ObjectNode) json.parse(Files.readString(Path.of("../../examples/order-payment-wait.json")));
        UUID wf = publishPurchase(PURCHASING, d -> example.properties().forEach(e -> {
            if (!e.getKey().equals("workflowKey") && !e.getKey().equals("name")) {
                d.set(e.getKey(), e.getValue());
            }
        }));
        grant("message.send", null);
        String orderNumber = "SO-" + UUID.randomUUID().toString().substring(0, 8);
        Response started = api("rhea.requester").post(Map.of("workflowId", wf, "input",
                Map.of("orderNumber", orderNumber, "customerName", "Acme Ltd")), UUID.randomUUID().toString(),
                "/workspaces/{ws}/runs", PURCHASING);
        UUID run = UUID.fromString(started.body().path("id").asString());
        Response created = api("wade.admin").post(Map.of("key", "kt-resume-" + UUID.randomUUID().toString().substring(0, 6),
                "name", "Payments", "cluster", "local", "topic", "kt-resume", "action", "resume",
                "messageName", "payment-received", "correlationPointer", "/order",
                "mapping", Map.of("paymentReference", "/ref"), "startFrom", "earliest"), "/workspaces/{ws}/kafka-triggers",
                PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID trigger = UUID.fromString(created.body().path("id").asString());
        runner.reconcile();
        Map<String, byte[]> batch = new LinkedHashMap<>();
        batch.put("pay-1", jsonCodec.write(Map.of("order", orderNumber, "ref", "PAY-KAFKA-LIVE")).getBytes(StandardCharsets.UTF_8));
        produce("kt-resume", batch);
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> records(trigger, "resumed") == 1);
        assertThat(jdbc.sql("SELECT run_id FROM kafka_inbound_record WHERE trigger_id = :t").param("t", trigger)
                .query(UUID.class).single()).isEqualTo(run);
        assertThat(jdbc.sql("SELECT status FROM message_subscription WHERE run_id = :r").param("r", run)
                .query(String.class).single()).isEqualTo("received");
    }

    @Test
    void twoReplicasInOneGroupEachOwnAPartitionAndFailOverWhenOneStops() throws Exception {
        createTopic("kt-replicas", 2);
        UUID wf = publishPurchase(PURCHASING, null);
        UUID trigger = startTrigger("kt-replicas", wf);
        replicaB = new KafkaConsumerRunner(settings, triggers, handler, secrets, jdbcClient, jsonCodec, clock, "replica-b");
        runner.reconcile();
        replicaB.reconcile();
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> partitionsOf(trigger, "replica-a").size() == 1
                && partitionsOf(trigger, "replica-b").size() == 1);
        assertThat(partitionsOf(trigger, "replica-a")).doesNotContainAnyElementsOf(partitionsOf(trigger, "replica-b"));

        Map<String, byte[]> first = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) {
            first.put("k-" + i, order(1000 + i));
        }
        produce("kt-replicas", first);
        Awaitility.await().atMost(Duration.ofSeconds(90)).until(() -> records(trigger, "started") == 40);
        // Exactly one replica consumed each partition, and both replicas consumed.
        List<Map<String, Object>> owners = jdbc.sql("""
                SELECT topic_partition, count(DISTINCT instance_id) AS owners, min(instance_id) AS instance
                FROM kafka_inbound_record WHERE trigger_id = :t GROUP BY topic_partition ORDER BY topic_partition
                """).param("t", trigger).query().listOfRows();
        assertThat(owners).hasSize(2);
        assertThat(owners).allSatisfy(row -> assertThat(((Number) row.get("owners")).intValue()).isEqualTo(1));
        assertThat(owners.stream().map(row -> row.get("instance")).distinct()).hasSize(2);

        // Fail-over: replica B stops; replica A takes both partitions and carries on without duplicates.
        replicaB.stopAll();
        Awaitility.await().atMost(Duration.ofSeconds(90)).until(() -> partitionsOf(trigger, "replica-a").size() == 2);
        Map<String, byte[]> second = new LinkedHashMap<>();
        for (int i = 40; i < 60; i++) {
            second.put("k-" + i, order(1000 + i));
        }
        produce("kt-replicas", second);
        Awaitility.await().atMost(Duration.ofSeconds(90)).until(() -> records(trigger, "started") == 60);
        assertThat(jdbc.sql("SELECT count(DISTINCT instance_id) FROM kafka_inbound_record WHERE trigger_id = :t AND dedupe_key IN ("
                + "SELECT 'k:k-' || g FROM generate_series(40, 59) g)").param("t", trigger).query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM workflow_run WHERE workflow_id = :w").param("w", wf).query(Long.class)
                .single()).isEqualTo(60);
        assertThat(jdbc.sql("SELECT count(DISTINCT run_id) FROM kafka_inbound_record WHERE trigger_id = :t").param("t", trigger)
                .query(Long.class).single()).isEqualTo(60);
        assertThat(status(trigger).path("replicas")).hasSize(1);
    }

    @Test
    void aTransientRefusalIsRetriedWithoutStoringAndDeadLetteredAtTheRetryLimit() throws Exception {
        UUID wf = publishPurchase(PURCHASING, d -> ((ObjectNode) d.path("execution")).put("maxConcurrentRuns", 1));
        start("rhea.requester", PURCHASING, wf, BigDecimal.ONE); // the only allowed active request
        UUID trigger = startTrigger("kt-start", wf);
        jdbc.sql("UPDATE kafka_trigger SET enabled = false WHERE id = :id").param("id", trigger).update();
        KafkaTriggerService.Trigger t = triggers.find(PURCHASING, trigger).orElseThrow();
        KafkaRecordHandler.Incoming record = new KafkaRecordHandler.Incoming(0, 7, "quota-1".getBytes(StandardCharsets.UTF_8),
                order(10));
        KafkaRecordHandler.Result retry = handler.handle(t, record, "replica-a", 1, false);
        assertThat(retry.outcome()).isEqualTo("retry");
        assertThat(retry.code()).isEqualTo("quota_exceeded");
        assertThat(records(trigger, "started") + records(trigger, "dead")).isZero();
        KafkaRecordHandler.Result dead = handler.handle(t, record, "replica-a", 10, true);
        assertThat(dead.outcome()).isEqualTo("dead");
        assertThat(dead.code()).isEqualTo("retries_exhausted");
        assertThat(jdbc.sql("SELECT attempts FROM kafka_inbound_record WHERE trigger_id = :t").param("t", trigger)
                .query(Integer.class).single()).isEqualTo(10);
        // A later delivery of the same record is a duplicate, not another try.
        assertThat(handler.handle(t, record, "replica-a", 1, false).outcome()).isEqualTo("duplicate");
    }
}
