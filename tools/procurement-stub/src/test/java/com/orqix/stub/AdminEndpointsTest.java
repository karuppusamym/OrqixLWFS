package com.orqix.stub;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** GET /__orders, POST /__reset and the validation of POST /__faults. */
class AdminEndpointsTest extends StubTestSupport {

    @Test
    void ordersEndpointListsFullRecordsAndCounters() throws Exception {
        createOrder("one", VALID_BODY);
        createOrder("one", VALID_BODY);                                   // replay
        createOrder("two", "{\"title\":\"Chair\",\"amount\":99.5,\"costCenter\":\"HR\"}");

        var result = get("/__orders").json();

        assertThat(result.get("count").intValue()).isEqualTo(2);
        assertThat(result.get("createRequests").intValue()).isEqualTo(3);
        assertThat(result.get("replays").intValue()).isEqualTo(1);
        var first = result.get("orders").get(0);
        assertThat(first.get("id").stringValue()).isEqualTo("PO-1");
        assertThat(first.get("idempotencyKey").stringValue()).isEqualTo("one");
        assertThat(first.get("request").get("costCenter").stringValue()).isEqualTo("IT");
        assertThat(first.get("createdAt").stringValue()).isNotBlank();
    }

    @Test
    void resetClearsOrdersFaultsCountersAndTheIdSequence() throws Exception {
        createOrder("one", VALID_BODY);
        arm("{\"mode\":\"status500\",\"times\":4}");

        Response reset = post("/__reset", null, null);

        assertThat(reset.status()).isEqualTo(200);
        assertThat(storedOrderCount()).isZero();
        assertThat(get("/__faults").json().get("mode").stringValue()).isEqualTo("none");
        assertThat(get("/__faults").json().get("applied").intValue()).isZero();
        assertThat(createOrder("again", VALID_BODY).json().get("id").stringValue()).isEqualTo("PO-1");
    }

    @Test
    void armedFaultIsReportedBack() throws Exception {
        Response armed = arm("{\"mode\":\"delay\",\"times\":3,\"delayMs\":250}");

        assertThat(armed.status()).isEqualTo(200);
        assertThat(armed.json().get("mode").stringValue()).isEqualTo("delay");
        assertThat(armed.json().get("remaining").intValue()).isEqualTo(3);
        assertThat(armed.json().get("delayMs").longValue()).isEqualTo(250);
        assertThat(get("/__faults").json()).isEqualTo(armed.json());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"mode\":\"explode\"}",
            "{\"mode\":7}",
            "{\"mode\":\"status500\",\"times\":0}",
            "{\"mode\":\"status500\",\"times\":-1}",
            "{\"mode\":\"status500\",\"times\":1.5}",
            "{\"mode\":\"status500\",\"times\":\"2\"}",
            "{\"mode\":\"delay\",\"delayMs\":-5}",
            "{\"mode\":\"delay\",\"delayMs\":99999999}",
            "{\"mode\":\"status500\",\"delayMs\":100}",
            "{\"mode\":\"status500\",\"unknown\":true}",
            "[\"status500\"]",
            "not json"})
    void invalidFaultDefinitionsAreRejectedAndLeaveNoFaultArmed(String payload) throws Exception {
        Response response = arm(payload);

        assertThat(response.status()).as(payload).isEqualTo(400);
        assertThat(get("/__faults").json().get("mode").stringValue()).isEqualTo("none");
    }
}
