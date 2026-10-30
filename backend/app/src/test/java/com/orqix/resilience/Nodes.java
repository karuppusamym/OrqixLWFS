package com.orqix.resilience;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.orqix.support.Stub;
import com.orqix.support.TestJwtDecoder;

/**
 * Context definitions of the application instances in the resilience suite. Each nested class is one "pod": the same
 * application against the same (resilience) database, differing only in the lock owner. They are never run as tests;
 * {@link Node} builds their contexts through the Spring TestContext framework, which gives every instance the same
 * MockMvc wiring as the regular integration tests, and closes them on demand.
 *
 * <p>Timings are shortened so that the suite finishes in minutes: job lock 10 s (production: 60 s), expired-job reset
 * every 2 s (15 s), policy cache 3 s (60 s). The Docker scenarios in {@code tools/resilience} use the real values.
 */
public final class Nodes {

    public static final String LOCK_TIME = "10s";
    public static final String RESET_INTERVAL = "2s";
    public static final String POLICY_TTL = "3s";

    private Nodes() {
    }

    @SpringBootTest(classes = {com.orqix.OrqixApplication.class, TestJwtDecoder.class, JobExecutionRecorder.class})
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "orqix.runtime.async-job-lock-time=" + LOCK_TIME,
            "orqix.runtime.reset-expired-jobs-interval=" + RESET_INTERVAL,
            "orqix.policy.cache-ttl=" + POLICY_TTL
    })
    public abstract static class Base {

        @DynamicPropertySource
        static void orqixProperties(DynamicPropertyRegistry registry) {
            ResilienceDatabase.appProperties().forEach((key, value) -> registry.add(key, () -> value));
            String stub = "localhost:" + Stub.port();
            registry.add("orqix.egress.allowed-hosts", () -> stub);
            registry.add("orqix.egress.allowed-private-hosts", () -> stub);
            registry.add("orqix.egress.plain-http-hosts", () -> stub);
        }
    }

    @TestPropertySource(properties = "orqix.runtime.lock-owner=node-a")
    public static class NodeA extends Base {
    }

    @TestPropertySource(properties = "orqix.runtime.lock-owner=node-b")
    public static class NodeB extends Base {
    }

    /** API role (docs/adr/0004): serves requests, runs no engine jobs and no scheduled work. */
    @ActiveProfiles("api")
    @TestPropertySource(properties = "orqix.runtime.lock-owner=node-api")
    public static class ApiNode extends Base {
    }

    /** Worker role (docs/adr/0004): runs engine jobs and scheduled work. */
    @ActiveProfiles("worker")
    @TestPropertySource(properties = "orqix.runtime.lock-owner=node-worker")
    public static class WorkerNode extends Base {
    }
}
