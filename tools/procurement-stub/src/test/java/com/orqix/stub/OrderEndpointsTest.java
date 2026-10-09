package com.orqix.stub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

/** POST /orders, GET /orders and GET /health: the S1 section 6.2 contract. */
class OrderEndpointsTest extends StubTestSupport {

    @Test
    void healthReportsUp() throws Exception {
        Response response = get("/health");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("application/json");
        assertThat(response.json().get("status").stringValue()).isEqualTo("UP");
    }

    @Test
    void newKeyCreatesOrderWith201() throws Exception {
        Response response = createOrder("key-1", VALID_BODY);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.header("Content-Type")).startsWith("application/json");
        assertThat(response.json().get("id").stringValue()).matches("PO-\\d+");
        assertThat(response.json().get("status").stringValue()).isEqualTo("CREATED");
        assertThat(response.json().propertyNames()).containsExactlyInAnyOrder("id", "status");
    }

    @Test
    void ordersGetSequentialIds() throws Exception {
        assertThat(createOrder("a", VALID_BODY).json().get("id").stringValue()).isEqualTo("PO-1");
        assertThat(createOrder("b", VALID_BODY).json().get("id").stringValue()).isEqualTo("PO-2");
    }

    @Test
    void sameKeyAndSameBodyReplaysTheOriginalWith200() throws Exception {
        Response first = createOrder("replay-key", VALID_BODY);
        Response second = createOrder("replay-key", VALID_BODY);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.json()).isEqualTo(first.json());
        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(storedOrderCount()).isEqualTo(1);
    }

    @Test
    void equivalentBodyWithDifferentFormattingIsStillAReplay() throws Exception {
        Response first = createOrder("format-key", "{\"title\":\"Laptop\",\"amount\":1800.07,\"costCenter\":\"IT\"}");
        Response second = createOrder("format-key", "{ \"costCenter\": \"IT\", \"amount\": 1800.070, \"title\": \"Laptop\" }");

        assertThat(second.status()).isEqualTo(200);
        assertThat(second.json()).isEqualTo(first.json());
    }

    @Test
    void sameKeyWithDifferentBodyIs422AndKeepsTheOriginal() throws Exception {
        Response first = createOrder("conflict-key", VALID_BODY);
        Response second = createOrder("conflict-key", "{\"title\":\"Laptop\",\"amount\":1800.08,\"costCenter\":\"IT\"}");

        assertThat(second.status()).isEqualTo(422);
        assertThat(second.json().get("error").stringValue()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(second.json().get("existingOrderId").stringValue()).isEqualTo(first.json().get("id").stringValue());
        assertThat(storedOrderCount()).isEqualTo(1);
        assertThat(lookup("conflict-key").json()).isEqualTo(first.json());
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        Response response = createOrder(null, VALID_BODY);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("error").stringValue()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
        assertThat(storedOrderCount()).isZero();
    }

    @Test
    void blankIdempotencyKeyIs400() throws Exception {
        assertThat(createOrder(" ", VALID_BODY).status()).isEqualTo(400);
    }

    @Test
    void overlongIdempotencyKeyIs400() throws Exception {
        assertThat(createOrder("k".repeat(256), VALID_BODY).status()).isEqualTo(400);
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("missing title", "{\"amount\":1,\"costCenter\":\"IT\"}"),
                Arguments.of("missing amount", "{\"title\":\"x\",\"costCenter\":\"IT\"}"),
                Arguments.of("missing costCenter", "{\"title\":\"x\",\"amount\":1}"),
                Arguments.of("amount is a string", "{\"title\":\"x\",\"amount\":\"12\",\"costCenter\":\"IT\"}"),
                Arguments.of("costCenter outside the enum", "{\"title\":\"x\",\"amount\":1,\"costCenter\":\"FIN\"}"),
                Arguments.of("additional property", "{\"title\":\"x\",\"amount\":1,\"costCenter\":\"IT\",\"extra\":true}"),
                Arguments.of("title longer than 120", "{\"title\":\"" + "t".repeat(121) + "\",\"amount\":1,\"costCenter\":\"IT\"}"),
                Arguments.of("empty object", "{}"),
                Arguments.of("array instead of object", "[1,2]"),
                Arguments.of("malformed JSON", "{\"title\":"),
                Arguments.of("trailing garbage", VALID_BODY + " garbage"),
                Arguments.of("duplicate keys", "{\"title\":\"a\",\"title\":\"b\",\"amount\":1,\"costCenter\":\"IT\"}"),
                Arguments.of("empty body", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void invalidBodyIs400AndStoresNothing(String description, String body) throws Exception {
        Response response = createOrder("invalid-" + description.hashCode(), body);

        assertThat(response.status()).as(description).isEqualTo(400);
        assertThat(storedOrderCount()).isZero();
    }

    @Test
    void schemaViolationsAreListed() throws Exception {
        Response response = createOrder("violations", "{\"title\":\"x\",\"amount\":1,\"costCenter\":\"FIN\"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("error").stringValue()).isEqualTo("INVALID_REQUEST");
        assertThat(response.json().get("violations").size()).isGreaterThanOrEqualTo(1);
        assertThat(response.json().get("violations").get(0).get("path").stringValue()).contains("costCenter");
    }

    @Test
    void anInvalidRequestDoesNotConsumeTheKey() throws Exception {
        assertThat(createOrder("reuse-after-400", "{}").status()).isEqualTo(400);

        assertThat(createOrder("reuse-after-400", VALID_BODY).status()).isEqualTo(201);
    }

    @Test
    void lookupByIdempotencyKeyReturnsTheOrder() throws Exception {
        Response created = createOrder("lookup key/with+special&chars", VALID_BODY);

        Response found = lookup("lookup key/with+special&chars");

        assertThat(found.status()).isEqualTo(200);
        assertThat(found.json()).isEqualTo(created.json());
    }

    @Test
    void lookupOfUnknownKeyIs404() throws Exception {
        assertThat(lookup("never-used").status()).isEqualTo(404);
    }

    @Test
    void lookupWithoutKeyIs400() throws Exception {
        assertThat(get("/orders").status()).isEqualTo(400);
        assertThat(get("/orders?idempotencyKey=").status()).isEqualTo(400);
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateExactlyOneOrder() throws Exception {
        int threads = 16;
        List<Response> responses = runConcurrently(threads, i -> () -> createOrder("same-key", VALID_BODY));

        long created = responses.stream().filter(r -> r.status() == 201).count();
        long replayed = responses.stream().filter(r -> r.status() == 200).count();
        Set<String> ids = responses.stream().map(r -> r.json().get("id").stringValue()).collect(Collectors.toSet());

        assertThat(created).isEqualTo(1);
        assertThat(replayed).isEqualTo(threads - 1);
        assertThat(ids).hasSize(1);
        assertThat(storedOrderCount()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithDistinctKeysGetDistinctIds() throws Exception {
        int threads = 24;
        List<Response> responses = runConcurrently(threads, i -> () -> createOrder("key-" + i, VALID_BODY));

        Set<String> ids = responses.stream().map(r -> r.json().get("id").stringValue()).collect(Collectors.toSet());
        assertThat(responses).allMatch(r -> r.status() == 201);
        assertThat(ids).hasSize(threads);
        assertThat(storedOrderCount()).isEqualTo(threads);
    }

    private List<Response> runConcurrently(int threads, java.util.function.IntFunction<Callable<Response>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Response>> futures = new ArrayList<>();
            IntStream.range(0, threads).forEach(i -> futures.add(pool.submit(task.apply(i))));
            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }
}
