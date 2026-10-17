package com.orqix.integration.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.orqix.integration.adapters.AdapterRules.Invalid;
import com.orqix.integration.adapters.AdapterRules.Kind;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure rule tests for the IN02 adapters and inbound triggers: allowlists, SQL limits, secret refusal and mapping. */
class AdapterRulesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode j(String text) {
        return JSON.readTree(text);
    }

    private static final AdapterRules.EgressCheck ALLOW_ALL = url -> {
    };

    @Test
    void emailConfigNormalizesDomainsAndRefusesWildcardsAndSecrets() {
        JsonNode ok = AdapterRules.validateConfig(Kind.EMAIL,
                j("{\"from\":\"Noreply@Example.com\",\"allowedRecipientDomains\":[\"Example.com\",\"example.com\"]}"), ALLOW_ALL);
        assertThat(ok.path("from").asString()).isEqualTo("noreply@example.com");
        assertThat(ok.path("allowedRecipientDomains")).hasSize(1);
        assertThat(ok.path("maxRecipients").asInt()).isEqualTo(5);
        assertThatThrownBy(() -> AdapterRules.validateConfig(Kind.EMAIL,
                j("{\"from\":\"a@example.com\",\"allowedRecipientDomains\":[\"*.example.com\"]}"), ALLOW_ALL))
                .isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> AdapterRules.validateConfig(Kind.EMAIL,
                j("{\"from\":\"a@example.com\",\"allowedRecipientDomains\":[\"example.com\"],\"smtpPassword\":\"hunter2\"}"), ALLOW_ALL))
                .isInstanceOfSatisfying(Invalid.class, e -> {
                    assertThat(e.pointer()).isEqualTo("/config/smtpPassword");
                    assertThat(e.getMessage()).contains("secretRef").doesNotContain("hunter2");
                });
    }

    @Test
    void emailSendsOnlyToApprovedDomainsWithinTheRecipientLimitAndWithoutHeaderInjection() {
        JsonNode config = AdapterRules.validateConfig(Kind.EMAIL,
                j("{\"from\":\"a@example.com\",\"allowedRecipientDomains\":[\"example.com\"],\"maxRecipients\":2}"), ALLOW_ALL);
        JsonNode sent = AdapterRules.validateSend(Kind.EMAIL, config,
                j("{\"to\":[\"Bob@Example.com\",\"bob@example.com\"],\"subject\":\"Hi\",\"body\":\"Hello\"}"));
        assertThat(sent.path("to")).hasSize(1);
        assertThatThrownBy(() -> AdapterRules.validateSend(Kind.EMAIL, config,
                j("{\"to\":[\"x@evil.example.org\"],\"subject\":\"Hi\",\"body\":\"b\"}"))).isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> AdapterRules.validateSend(Kind.EMAIL, config,
                j("{\"to\":[\"a@example.com\",\"b@example.com\",\"c@example.com\"],\"subject\":\"Hi\",\"body\":\"b\"}")))
                .isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> AdapterRules.validateSend(Kind.EMAIL, config,
                j("{\"to\":[\"a@example.com\"],\"subject\":\"Hi\\r\\nBcc: spy@evil.example.org\",\"body\":\"b\"}")))
                .isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> AdapterRules.validateSend(Kind.EMAIL, config,
                j("{\"to\":[\"a@example.com\"],\"subject\":\"Hi\",\"body\":\"b\",\"bcc\":[\"spy@evil.example.org\"]}")))
                .isInstanceOf(Invalid.class);
    }

    @Test
    void teamsBaseUrlMustPassTheEgressCheck() {
        AdapterRules.EgressCheck deny = url -> {
            throw new Invalid("/config/baseUrl", "The host is not on the egress allowlist.");
        };
        assertThatThrownBy(() -> AdapterRules.validateConfig(Kind.TEAMS, j("{\"baseUrl\":\"https://hooks.example.com\"}"), deny))
                .isInstanceOfSatisfying(Invalid.class, e -> assertThat(e.pointer()).isEqualTo("/config/baseUrl"));
        assertThat(AdapterRules.validateConfig(Kind.TEAMS, j("{\"baseUrl\":\"https://hooks.example.com\"}"), ALLOW_ALL)
                .path("baseUrl").asString()).isEqualTo("https://hooks.example.com");
        assertThatThrownBy(() -> AdapterRules.validateConfig(Kind.TEAMS,
                j("{\"baseUrl\":\"https://hooks.example.com\",\"webhookToken\":\"abc\"}"), ALLOW_ALL)).isInstanceOf(Invalid.class);
    }

    @Test
    void jdbcAcceptsOneParameterizedSelectAndRefusesEverythingElse() {
        JsonNode ok = AdapterRules.validateConfig(Kind.JDBC, j("""
                {"statement":"SELECT id, status FROM orders WHERE customer_id = :customerId AND total > :minTotal",
                 "parameters":["customerId","minTotal"],"maxRows":50}"""), ALLOW_ALL);
        assertThat(ok.path("maxRows").asInt()).isEqualTo(50);
        assertThat(ok.path("timeoutSeconds").asInt()).isEqualTo(10);
        String[] unsafe = {
                "DELETE FROM orders WHERE id = :id",
                "SELECT 1; DROP TABLE orders",
                "SELECT * FROM orders -- comment",
                "SELECT * FROM orders /* c */ WHERE id = :id",
                "SELECT * FROM orders WHERE name = 'x' AND id = :id",
                "SELECT * INTO backup FROM orders WHERE id = :id",
                "SELECT * FROM orders WHERE id = :id FOR UPDATE",
                "SELECT pg_sleep(10) FROM orders WHERE id = :id",
                "SELECT * FROM pg_catalog.pg_user WHERE id = :id",
                "SELECT id::text FROM orders WHERE id = :id",
                "UPDATE orders SET a = 1 WHERE id = :id",
        };
        for (String statement : unsafe) {
            JsonNode config = JSON.createObjectNode().put("statement", statement).set("parameters", j("[\"id\"]"));
            assertThatThrownBy(() -> AdapterRules.validateConfig(Kind.JDBC, config, ALLOW_ALL)).as(statement)
                    .isInstanceOf(Invalid.class);
        }
        // Undeclared and unused parameters are both refused; so is a row cap above the limit.
        assertThatThrownBy(() -> AdapterRules.validateConfig(Kind.JDBC,
                j("{\"statement\":\"SELECT id FROM orders WHERE id = :id\",\"parameters\":[]}"), ALLOW_ALL)).isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> AdapterRules.validateConfig(Kind.JDBC,
                j("{\"statement\":\"SELECT id FROM orders\",\"parameters\":[\"id\"]}"), ALLOW_ALL)).isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> AdapterRules.validateConfig(Kind.JDBC,
                j("{\"statement\":\"SELECT id FROM orders\",\"maxRows\":501}"), ALLOW_ALL)).isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> AdapterRules.validateConfig(Kind.JDBC,
                j("{\"statement\":\"SELECT id FROM orders\",\"jdbcUrl\":\"jdbc:postgresql://db/prod?password=x\"}"), ALLOW_ALL))
                .isInstanceOf(Invalid.class);
    }

    @Test
    void jdbcSendBindsOnlyDeclaredScalarParameters() {
        JsonNode config = AdapterRules.validateConfig(Kind.JDBC,
                j("{\"statement\":\"SELECT id FROM orders WHERE customer_id = :customerId\",\"parameters\":[\"customerId\"]}"), ALLOW_ALL);
        assertThat(AdapterRules.validateSend(Kind.JDBC, config, j("{\"parameters\":{\"customerId\":42}}"))
                .path("parameters").path("customerId").asInt()).isEqualTo(42);
        assertThatThrownBy(() -> AdapterRules.validateSend(Kind.JDBC, config, j("{\"parameters\":{}}"))).isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> AdapterRules.validateSend(Kind.JDBC, config,
                j("{\"parameters\":{\"customerId\":1,\"other\":2}}"))).isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> AdapterRules.validateSend(Kind.JDBC, config,
                j("{\"parameters\":{\"customerId\":{\"$ne\":1}}}"))).isInstanceOf(Invalid.class);
    }

    @Test
    void kafkaProducesOnlyToExactApprovedTopicsWithBoundedValues() {
        JsonNode config = AdapterRules.validateConfig(Kind.KAFKA, j("{\"allowedTopics\":[\"orders.created\"]}"), ALLOW_ALL);
        assertThat(AdapterRules.validateSend(Kind.KAFKA, config,
                j("{\"topic\":\"orders.created\",\"key\":\"k1\",\"value\":{\"id\":1}}")).path("topic").asString())
                .isEqualTo("orders.created");
        assertThatThrownBy(() -> AdapterRules.validateSend(Kind.KAFKA, config, j("{\"topic\":\"orders.deleted\",\"value\":{}}")))
                .isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> AdapterRules.validateConfig(Kind.KAFKA, j("{\"allowedTopics\":[\"orders.*\"]}"), ALLOW_ALL))
                .isInstanceOf(Invalid.class);
        String big = "x".repeat(AdapterRules.MAX_KAFKA_VALUE_BYTES + 1);
        assertThatThrownBy(() -> AdapterRules.validateSend(Kind.KAFKA, config,
                JSON.createObjectNode().put("topic", "orders.created").set("value", JSON.createObjectNode().put("blob", big))))
                .isInstanceOf(Invalid.class);
    }

    @Test
    void inboundFiltersAndMappingAreEnforcedAndOnlyMappedScalarsSurvive() {
        JsonNode emailFilter = AdapterRules.validateFilter(Kind.EMAIL, j("{\"allowedSenderDomains\":[\"Partner.example\"]}"));
        assertThat(emailFilter.path("allowedSenderDomains").get(0).asString()).isEqualTo("partner.example");
        AdapterRules.acceptInbound(Kind.EMAIL, emailFilter, j("{\"from\":\"a@partner.example\",\"subject\":\"PO\"}"));
        assertThatThrownBy(() -> AdapterRules.acceptInbound(Kind.EMAIL, emailFilter, j("{\"from\":\"a@evil.example\"}")))
                .isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> AdapterRules.acceptInbound(Kind.EMAIL, emailFilter, j("{\"from\":\"not-an-address\"}")))
                .isInstanceOf(Invalid.class);
        JsonNode kafkaFilter = AdapterRules.validateFilter(Kind.KAFKA, j("{\"allowedTopics\":[\"orders.created\"]}"));
        AdapterRules.acceptInbound(Kind.KAFKA, kafkaFilter, j("{\"topic\":\"orders.created\",\"value\":{}}"));
        assertThatThrownBy(() -> AdapterRules.acceptInbound(Kind.KAFKA, kafkaFilter, j("{\"topic\":\"other\",\"value\":{}}")))
                .isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> AdapterRules.validateFilter(Kind.TEAMS, j("{\"anything\":1}"))).isInstanceOf(Invalid.class);

        JsonNode mapping = AdapterRules.validateMapping(j("{\"title\":\"/value/title\",\"amount\":\"/value/amount\"}"));
        JsonNode mapped = AdapterRules.mapInput(mapping, j("{\"topic\":\"t\",\"value\":{\"title\":\"Laptop\",\"amount\":12.5,\"secret\":\"x\"}}"));
        assertThat(mapped.propertyNames()).containsExactlyInAnyOrder("title", "amount");
        assertThatThrownBy(() -> AdapterRules.mapInput(mapping, j("{\"value\":{\"title\":{\"nested\":true},\"amount\":1}}")))
                .isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> AdapterRules.mapInput(mapping, j("{\"value\":{\"title\":\"x\"}}"))).isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> AdapterRules.validateMapping(j("{\"title\":\"value/title\"}"))).isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> AdapterRules.validateMapping(j("{}"))).isInstanceOf(Invalid.class);
    }

    @Test
    void signatureIsDeterministicAndBoundToTimestampSecretAndBody() {
        byte[] body = "{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String base = InboundService.sign("s3cret", "1700000000", body);
        assertThat(base).hasSize(64).isEqualTo(InboundService.sign("s3cret", "1700000000", body));
        assertThat(InboundService.sign("other", "1700000000", body)).isNotEqualTo(base);
        assertThat(InboundService.sign("s3cret", "1700000001", body)).isNotEqualTo(base);
        assertThat(InboundService.sign("s3cret", "1700000000", "{\"a\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isNotEqualTo(base);
    }
}
