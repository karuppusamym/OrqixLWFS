package com.orqix.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.orqix.integration.kafka.KafkaConsumerRunner;
import com.orqix.shared.json.Json;
import com.orqix.support.RuntimeTestBase;

import tools.jackson.databind.JsonNode;

/**
 * IN05 (S106): a Kafka trigger against a broker that requires SASL/PLAIN sign-in (Testcontainers, apache/kafka). The
 * trigger's credentials come only from its secretRef file; with the right ones a record starts a request, with wrong
 * ones nothing is consumed and the status says authentication_failed. TLS (SASL_SSL) is not covered here.
 */
class KafkaSaslTest extends RuntimeTestBase {

    private static final String TOPIC = "ks-orders";
    private static final String CLIENT_USER = "orqix";
    private static final String CLIENT_PASSWORD = "orqix-test-sasl-pw";
    private static final String ADMIN_PASSWORD = "admin-test-sasl-pw";
    private static final int HOST_PORT = freePort();

    private static final String JAAS = "org.apache.kafka.common.security.plain.PlainLoginModule required "
            + "username=\"admin\" password=\"" + ADMIN_PASSWORD + "\" user_admin=\"" + ADMIN_PASSWORD + "\" user_"
            + CLIENT_USER + "=\"" + CLIENT_PASSWORD + "\";";

    /** One KRaft node; INTERNAL is for the broker itself, EXTERNAL is published on a fixed free host port. */
    @SuppressWarnings("resource")
    private static final GenericContainer<?> KAFKA = new GenericContainer<>("apache/kafka:3.9.1")
            .withEnv("KAFKA_NODE_ID", "1")
            .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
            .withEnv("KAFKA_LISTENERS", "INTERNAL://0.0.0.0:9094,EXTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093")
            .withEnv("KAFKA_ADVERTISED_LISTENERS", "INTERNAL://localhost:9094,EXTERNAL://localhost:" + HOST_PORT)
            .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "INTERNAL:SASL_PLAINTEXT,EXTERNAL:SASL_PLAINTEXT,CONTROLLER:PLAINTEXT")
            .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
            .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
            .withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "INTERNAL")
            .withEnv("KAFKA_SASL_ENABLED_MECHANISMS", "PLAIN")
            .withEnv("KAFKA_SASL_MECHANISM_INTER_BROKER_PROTOCOL", "PLAIN")
            .withEnv("KAFKA_LISTENER_NAME_INTERNAL_PLAIN_SASL_JAAS_CONFIG", JAAS)
            .withEnv("KAFKA_LISTENER_NAME_EXTERNAL_PLAIN_SASL_JAAS_CONFIG", JAAS)
            .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
            .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
            .withExposedPorts(9092)
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(HostConfig.newHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(HOST_PORT), new ExposedPort(9092)))))
            .waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        KAFKA.start();
        registry.add("orqix.kafka.mode", () -> "live");
        registry.add("orqix.kafka.instance-id", () -> "sasl-replica");
        registry.add("orqix.kafka.reconcile-delay", () -> "1h");
        registry.add("orqix.kafka.clusters.secure.bootstrap-servers", () -> "localhost:" + HOST_PORT);
        registry.add("orqix.kafka.clusters.secure.security-protocol", () -> "SASL_PLAINTEXT");
        registry.add("orqix.kafka.clusters.secure.sasl-mechanism", () -> "PLAIN");
        registry.add("orqix.kafka.clusters.secure.allowed-topics", () -> TOPIC);
    }

    @Autowired
    private KafkaConsumerRunner runner;

    @Autowired
    private Json jsonCodec;

    private static Properties client(String user, String password) {
        Properties p = new Properties();
        p.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, "localhost:" + HOST_PORT);
        p.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT");
        p.put(SaslConfigs.SASL_MECHANISM, "PLAIN");
        p.put(SaslConfigs.SASL_JAAS_CONFIG, "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                + user + "\" password=\"" + password + "\";");
        return p;
    }

    @BeforeEach
    void prepare() throws Exception {
        var owner = com.orqix.support.TestDatabase.legacyFixtureJdbc();
        owner.sql("DELETE FROM orqix.kafka_inbound_record WHERE workspace_id = :ws").param("ws", PURCHASING).update();
        owner.sql("DELETE FROM orqix.kafka_trigger_status WHERE workspace_id = :ws").param("ws", PURCHASING).update();
        owner.sql("DELETE FROM orqix.kafka_trigger WHERE workspace_id = :ws").param("ws", PURCHASING).update();
        try (Admin admin = Admin.create(client("admin", ADMIN_PASSWORD))) {
            if (!admin.listTopics().names().get().contains(TOPIC)) {
                admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
            }
        }
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "orqix-test-secrets");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("kafka-sasl-good"), "{\"username\":\"" + CLIENT_USER + "\",\"password\":\""
                + CLIENT_PASSWORD + "\"}");
        Files.writeString(dir.resolve("kafka-sasl-bad"), "{\"username\":\"" + CLIENT_USER + "\",\"password\":\"wrong\"}");
    }

    @AfterEach
    void stop() {
        runner.stopAll();
        jdbc.sql("UPDATE kafka_trigger SET enabled = false WHERE topic = :t").param("t", TOPIC).update();
    }

    private UUID trigger(UUID workflowId, String secretRef) throws Exception {
        UUID wade = UUID.fromString(api("wade.admin").get("/me").body().path("subject").path("id").asString());
        assertThat(api("wade.admin").post(Map.of("permission", "workflow.start", "workflowId", workflowId,
                "principal", Map.of("type", "subject", "value", wade.toString())), "/workspaces/{ws}/access/grants",
                PURCHASING).status()).isIn(201, 409);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", "ks-" + UUID.randomUUID().toString().substring(0, 6));
        body.put("name", "Secure orders");
        body.put("cluster", "secure");
        body.put("topic", TOPIC);
        body.put("secretRef", secretRef);
        body.put("action", "start");
        body.put("workflowId", workflowId);
        body.put("mapping", Map.of("title", "/title", "purpose", "/purpose", "costCenter", "/costCenter", "amount", "/amount"));
        body.put("startFrom", "earliest");
        body.put("batchSize", 20);
        body.put("maxPerSecond", 200);
        Response created = api("wade.admin").post(body, "/workspaces/{ws}/kafka-triggers", PURCHASING);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        return UUID.fromString(created.body().path("id").asString());
    }

    private void produce(String key, int amount) {
        Properties p = client("admin", ADMIN_PASSWORD);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        byte[] value = jsonCodec.write(new LinkedHashMap<>(input(BigDecimal.valueOf(amount)))).getBytes(StandardCharsets.UTF_8);
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(p)) {
            producer.send(new ProducerRecord<>(TOPIC, key.getBytes(StandardCharsets.UTF_8), value));
            producer.flush();
        }
    }

    private long started(UUID trigger) {
        return jdbc.sql("SELECT count(*) FROM kafka_inbound_record WHERE trigger_id = :t AND status = 'started'")
                .param("t", trigger).query(Long.class).single();
    }

    private JsonNode status(UUID trigger) throws Exception {
        for (JsonNode t : api("olga.operator").get("/workspaces/{ws}/kafka-triggers", PURCHASING).body().path("items")) {
            if (t.path("id").asString().equals(trigger.toString())) {
                return t.path("status");
            }
        }
        throw new AssertionError("trigger not listed");
    }

    @Test
    void wrongCredentialsConsumeNothingAndSaySoThenTheRightOnesStartTheRequest() throws Exception {
        UUID wf = publishPurchase(PURCHASING, null);
        UUID bad = trigger(wf, "kafka-sasl-bad");
        runner.reconcile();
        produce("sec-1", 420);
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1))
                .until(() -> "authentication_failed".equals(status(bad).path("lastError").asString(null)));
        assertThat(started(bad)).isZero();
        runner.stopAll();
        jdbc.sql("UPDATE kafka_trigger SET enabled = false WHERE id = :id").param("id", bad).update();

        UUID good = trigger(wf, "kafka-sasl-good");
        runner.reconcile();
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> started(good) == 1);
        UUID run = jdbc.sql("SELECT run_id FROM kafka_inbound_record WHERE trigger_id = :t").param("t", good)
                .query(UUID.class).single();
        assertThat(run(PURCHASING, run).path("input").path("amount").decimalValue()).isEqualByComparingTo("420");
        // The password is in no audit row and no status field.
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE metadata::text LIKE :p")
                .param("p", "%" + CLIENT_PASSWORD + "%").query(Long.class).single()).isZero();
        assertThat(status(good).toString()).doesNotContain(CLIENT_PASSWORD);
    }
}
