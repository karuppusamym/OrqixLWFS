package com.orqix.stub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/** Every fault mode of POST /__faults, observed over real HTTP and, for dropConnection, over a raw socket. */
class FaultModesTest extends StubTestSupport {

    @Test
    void modeNoneClearsAnActiveFault() throws Exception {
        arm("{\"mode\":\"status500\"}");
        assertThat(createOrder("k1", VALID_BODY).status()).isEqualTo(500);

        Response cleared = arm("{\"mode\":\"none\"}");

        assertThat(cleared.status()).isEqualTo(200);
        assertThat(cleared.json().get("mode").stringValue()).isEqualTo("none");
        assertThat(createOrder("k2", VALID_BODY).status()).isEqualTo(201);
    }

    @Test
    void delayHoldsTheResponseThenProcessesNormally() throws Exception {
        arm("{\"mode\":\"delay\",\"delayMs\":800,\"times\":1}");

        long start = System.nanoTime();
        Response delayed = createOrder("delay-key", VALID_BODY);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(elapsedMs).isGreaterThanOrEqualTo(750);
        assertThat(delayed.status()).isEqualTo(201);

        long start2 = System.nanoTime();
        Response next = createOrder("delay-key-2", VALID_BODY);
        assertThat(Duration.ofNanos(System.nanoTime() - start2).toMillis()).isLessThan(700);
        assertThat(next.status()).isEqualTo(201);
    }

    @Test
    void delayDefaultsToOneSecond() throws Exception {
        Response armed = arm("{\"mode\":\"delay\",\"times\":1}");

        assertThat(armed.json().get("delayMs").longValue()).isEqualTo(1000);
    }

    @Test
    void status500AffectsTheRequestedNumberOfRequestsAndStoresNothing() throws Exception {
        arm("{\"mode\":\"status500\",\"times\":2}");

        Response first = createOrder("k500", VALID_BODY);
        Response second = createOrder("k500", VALID_BODY);
        assertThat(first.status()).isEqualTo(500);
        assertThat(second.status()).isEqualTo(500);
        assertThat(storedOrderCount()).isZero();

        Response third = createOrder("k500", VALID_BODY);
        assertThat(third.status()).isEqualTo(201);
        assertThat(storedOrderCount()).isEqualTo(1);
        assertThat(get("/__faults").json().get("mode").stringValue()).isEqualTo("none");
    }

    @Test
    void status400IsReturnedWithoutCreatingAnOrder() throws Exception {
        arm("{\"mode\":\"status400\",\"times\":1}");

        Response response = createOrder("k400", VALID_BODY);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("error").stringValue()).isEqualTo("INJECTED_FAULT");
        assertThat(storedOrderCount()).isZero();
        assertThat(createOrder("k400", VALID_BODY).status()).isEqualTo(201);
    }

    @Test
    void status429CarriesRetryAfterAndDoesNotCreateAnOrder() throws Exception {
        arm("{\"mode\":\"status429\",\"times\":1}");

        Response response = createOrder("k429", VALID_BODY);

        assertThat(response.status()).isEqualTo(429);
        assertThat(response.header("Retry-After")).isEqualTo("1");
        assertThat(storedOrderCount()).isZero();
        assertThat(createOrder("k429", VALID_BODY).status()).isEqualTo(201);
    }

    @Test
    void faultWithoutTimesStaysArmedUntilCleared() throws Exception {
        arm("{\"mode\":\"status500\"}");

        for (int i = 0; i < 5; i++) {
            assertThat(createOrder("unbounded", VALID_BODY).status()).isEqualTo(500);
        }
        assertThat(get("/__faults").json().get("applied").intValue()).isEqualTo(5);

        arm("{\"mode\":\"none\"}");
        assertThat(createOrder("unbounded", VALID_BODY).status()).isEqualTo(201);
    }

    @Test
    void faultsApplyToOrderCreationOnly() throws Exception {
        arm("{\"mode\":\"status500\",\"times\":3}");

        assertThat(get("/health").status()).isEqualTo(200);
        assertThat(lookup("nothing").status()).isEqualTo(404);
        assertThat(get("/__orders").status()).isEqualTo(200);
        assertThat(get("/__faults").json().get("remaining").intValue()).isEqualTo(3);
    }

    @Test
    void commitThenTimeoutCreatesTheOrderButDelaysTheResponsePastTheClientTimeout() throws Exception {
        arm("{\"mode\":\"commitThenTimeout\",\"times\":1,\"delayMs\":2500}");

        assertThatThrownBy(() -> client.send(postRequest("/orders", "ctt-key", VALID_BODY, Duration.ofMillis(600)),
                java.net.http.HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(HttpTimeoutException.class);

        // The client gave up, yet the order is committed: the reconciliation evidence endpoint finds it.
        Response found = lookup("ctt-key");
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.json().get("status").stringValue()).isEqualTo("CREATED");
        assertThat(storedOrderCount()).isEqualTo(1);

        // Resending with the same key (fault consumed) is a replay of the committed order, not a duplicate.
        Response retry = createOrder("ctt-key", VALID_BODY);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.json()).isEqualTo(found.json());
        assertThat(storedOrderCount()).isEqualTo(1);
    }

    @Test
    void commitThenTimeoutUsesTheConfiguredDefaultWhenNoDelayIsGiven() throws Exception {
        Response armed = arm("{\"mode\":\"commitThenTimeout\",\"times\":1}");
        assertThat(armed.json().get("delayMs").longValue()).isEqualTo(1500);   // stub.commit-then-timeout-ms in StubTestSupport

        long start = System.nanoTime();
        Response late = send(postRequest("/orders", "ctt-default", VALID_BODY, Duration.ofSeconds(10)));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(elapsedMs).isGreaterThanOrEqualTo(1400);
        assertThat(late.status()).isEqualTo(201);
    }

    @Test
    void commitThenTimeoutDoesNotWaitWhenNothingWasCommitted() throws Exception {
        arm("{\"mode\":\"commitThenTimeout\",\"times\":1,\"delayMs\":5000}");

        long start = System.nanoTime();
        Response rejected = createOrder(null, VALID_BODY);

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(2000);
    }

    @Test
    void dropConnectionClosesTheSocketWithoutAnyResponse() throws Exception {
        arm("{\"mode\":\"dropConnection\",\"times\":1}");

        byte[] received = rawPost("drop-key", VALID_BODY);

        assertThat(received).as("no bytes may be written before the socket is closed").isEmpty();
        assertThat(storedOrderCount()).isZero();
        assertThat(get("/__faults").json().get("mode").stringValue()).isEqualTo("none");
    }

    @Test
    void dropConnectionSurfacesAsAnIoErrorForAnHttpClientAndTheNextCallWorks() throws Exception {
        arm("{\"mode\":\"dropConnection\",\"times\":1}");

        assertThatThrownBy(() -> createOrder("drop-key-2", VALID_BODY))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(HttpTimeoutException.class);

        assertThat(storedOrderCount()).isZero();
        assertThat(createOrder("drop-key-2", VALID_BODY).status()).isEqualTo(201);
    }

    /** Sends a raw HTTP/1.1 request and returns every byte received until the peer closes (empty = silent close). */
    private byte[] rawPost(String key, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String head = "POST /orders HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n"
                + "Idempotency-Key: " + key + "\r\nContent-Length: " + payload.length + "\r\nConnection: close\r\n\r\n";
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            try {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    received.write(buffer, 0, read);
                }
            } catch (SocketTimeoutException e) {
                throw new AssertionError("server neither answered nor closed the connection", e);
            } catch (java.net.SocketException e) {
                // A connection reset counts as a close without a response.
            }
            return received.toByteArray();
        }
    }
}
