package com.orqix.sdk.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.orqix.sdk.OrqixClient;
import com.orqix.sdk.ScriptedServer;
import com.orqix.sdk.worker.BusinessErrorException;
import com.orqix.sdk.worker.Job;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class OrqixAutoConfigurationTest {

    static final String WS = "00000000-0000-7000-8000-000000000201";
    static final String JOB_ID = "0193a0c4-7777-7000-8000-000000000007";

    static String jobs(String topic) {
        return """
                {"items":[{"id":"%s","topic":"%s","input":{"postcode":"W1A 1AA"},"fence":2,
                 "leaseExpiresAt":"2026-11-09T10:00:00Z","attempt":1,"maxAttempts":3,"runReference":"ORQ-1043",
                 "nodeId":"check"}]}
                """.formatted(JOB_ID, topic);
    }

    private ScriptedServer server;
    private ApplicationContextRunner runner;

    @BeforeEach
    void start() throws Exception {
        server = new ScriptedServer();
        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OrqixAutoConfiguration.class))
                .withPropertyValues("orqix.base-url=" + server.baseUrl(), "orqix.workspace-id=" + WS,
                        "orqix.worker.poll-interval=20ms", "orqix.worker.id=test-worker-1",
                        "spring.application.name=orders");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Configuration(proxyBeanMethods = false)
    static class Token {
        @Bean
        OrqixTokenProvider token() {
            return () -> "starter-token";
        }
    }

    static class AddressChecks {
        final AtomicReference<Map<String, Object>> seen = new AtomicReference<>();

        @OrqixWorker(topic = "address-check")
        Map<String, Object> check(Map<String, Object> input) {
            seen.set(input);
            return Map.of("valid", true);
        }

        @OrqixWorker("supplier-check")
        void supplier(Job job) throws BusinessErrorException {
            throw new BusinessErrorException("SUPPLIER_BLOCKED", "On the sanctions list " + job.runReference());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Handlers {
        @Bean
        AddressChecks addressChecks() {
            return new AddressChecks();
        }
    }

    @Test
    void annotatedMethodsAreServedByOneWorkerLoop() {
        server.once("POST", "/fetch-and-lock", 200, jobs("address-check"));
        server.always("POST", "/fetch-and-lock", 200, "{\"items\":[]}");
        server.once("POST", "/external-jobs/" + JOB_ID + "/complete", 204, null);

        runner.withUserConfiguration(Token.class, Handlers.class).run(context -> {
            assertThat(context).hasNotFailed();
            ScriptedServer.Received complete = server.await(r -> r.path().endsWith("/complete"), 10);
            assertThat(complete.header("Authorization")).isEqualTo("Bearer starter-token");
            assertThat(complete.body()).contains("\"workerId\":\"test-worker-1\"").contains("\"valid\":true");
            assertThat(context.getBean(AddressChecks.class).seen.get()).containsEntry("postcode", "W1A 1AA");
            ScriptedServer.Received fetch = server.received("POST", "/fetch-and-lock").get(0);
            assertThat(fetch.body()).contains("\"topics\":[\"address-check\",\"supplier-check\"]");
            assertThat(context.getBean(OrqixWorkerContainer.class).isRunning()).isTrue();
        });
        assertThat(server.received("POST", "/fetch-and-lock")).isNotEmpty();
    }

    @Test
    void aBusinessErrorFromAnAnnotatedMethodKeepsItsCode() {
        server.once("POST", "/fetch-and-lock", 200, jobs("supplier-check"));
        server.always("POST", "/fetch-and-lock", 200, "{\"items\":[]}");
        server.once("POST", "/external-jobs/" + JOB_ID + "/fail", 204, null);

        runner.withUserConfiguration(Token.class, Handlers.class).run(context -> {
            ScriptedServer.Received fail = server.await(r -> r.path().endsWith("/fail"), 10);
            assertThat(fail.body()).contains("\"errorCode\":\"SUPPLIER_BLOCKED\"").contains("\"retryable\":false")
                    .contains("ORQ-1043");
        });
    }

    @Test
    void theTokenFileIsReadForEveryCall(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("token");
        Files.writeString(file, "from-file\n");
        server.always("GET", "/events", 200, "{\"items\":[],\"nextAfter\":0,\"hasMore\":false,\"lifecycle\":\"running\"}");

        runner.withPropertyValues("orqix.token-file=" + file).run(context -> {
            OrqixClient client = context.getBean(OrqixClient.class);
            client.listRunEvents("0193a0c4-1111-7000-8000-000000000001", 0, null);
            Files.writeString(file, "rotated");
            client.listRunEvents("0193a0c4-1111-7000-8000-000000000001", 0, null);
        });
        assertThat(server.received().get(0).header("Authorization")).isEqualTo("Bearer from-file");
        assertThat(server.received().get(1).header("Authorization")).isEqualTo("Bearer rotated");
    }

    @Test
    void withoutATokenTheContextFailsWithAClearMessage() {
        runner.run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().hasMessageContaining("define an OrqixTokenProvider bean or set orqix.token-file"));
    }

    @Test
    void withoutABaseUrlNothingIsConfigured() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(OrqixAutoConfiguration.class))
                .withUserConfiguration(Token.class)
                .run(context -> assertThat(context).doesNotHaveBean(OrqixClient.class));
    }

    @Test
    void theWorkerCanBeSwitchedOff() {
        runner.withUserConfiguration(Token.class, Handlers.class).withPropertyValues("orqix.worker.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(OrqixClient.class);
                    assertThat(context).doesNotHaveBean(OrqixWorkerContainer.class);
                });
        assertThat(server.received()).isEmpty();
    }

    static class BadSignature {
        @OrqixWorker(topic = "bad")
        String wrong(String text) {
            return text;
        }
    }

    @Test
    void aBadHandlerSignatureFailsAtStartup() {
        runner.withUserConfiguration(Token.class).withBean(BadSignature.class).run(context -> assertThat(context)
                .hasFailed().getFailure().rootCause().hasMessageContaining("takes no parameter, a Job or a Map"));
    }

    static class Duplicate {
        @OrqixWorker(topic = "address-check")
        void again() {
        }
    }

    @Test
    void aTopicServedTwiceFailsAtStartup() {
        runner.withUserConfiguration(Token.class, Handlers.class).withBean(Duplicate.class).run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("served by both"));
    }

    @Test
    void theWorkerIdDefaultsToTheApplicationName() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(OrqixAutoConfiguration.class))
                .withUserConfiguration(Token.class)
                .withPropertyValues("orqix.base-url=" + server.baseUrl(), "orqix.workspace-id=" + WS,
                        "spring.application.name=orders")
                .run(context -> assertThat(context.getBean(OrqixWorkerContainer.class).workerId())
                        .matches("orders-[0-9a-f]{8}"));
    }
}
