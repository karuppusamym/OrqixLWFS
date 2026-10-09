package com.orqix.support;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * Base for integration tests: one cached application context per JVM against the shared Testcontainers PostgreSQL
 * (migrated with the real {@code migrate} profile incl. the dev seed), connected as the DML-only runtime role, with a
 * WireMock procurement stub. Every response is validated against contracts/openapi.yaml (T-API-01).
 */
@SpringBootTest(classes = {com.orqix.OrqixApplication.class, TestJwtDecoder.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    public static final UUID PURCHASING = UUID.fromString("00000000-0000-7000-8000-000000000201");
    public static final UUID SANDBOX = UUID.fromString("00000000-0000-7000-8000-000000000202");
    public static final UUID FACILITIES = UUID.fromString("00000000-0000-7000-8000-000000000203");

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected Json json;

    @DynamicPropertySource
    static void orqixProperties(DynamicPropertyRegistry registry) {
        TestDatabase.appProperties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("orqix.runtime.lock-owner", () -> "test-node-1");
        String stub = "localhost:" + Stub.port();
        // Extra allowlisted hosts exist only to prove that DNS/IP checks still reject them (T-SEC-07).
        registry.add("orqix.egress.allowed-hosts", () -> stub + ",public.test.example:443,internal.test.example:443,"
                + "127.0.0.1:443,169.254.169.254:443,10.1.2.3:443");
        registry.add("orqix.egress.allowed-private-hosts", () -> stub);
        registry.add("orqix.egress.plain-http-hosts", () -> stub);
    }

    /**
     * Real cookie-to-header CSRF exactly like the SPA (XSRF-TOKEN cookie echoed in X-XSRF-TOKEN). Spring Security's
     * csrf() test helper is avoided on purpose: it swaps the shared context's repository for a session-based one.
     */
    public static org.springframework.mock.web.MockHttpServletRequest xsrf(
            org.springframework.mock.web.MockHttpServletRequest request) {
        String token = UUID.randomUUID().toString();
        jakarta.servlet.http.Cookie[] existing = request.getCookies() == null ? new jakarta.servlet.http.Cookie[0]
                : request.getCookies();
        jakarta.servlet.http.Cookie[] cookies = java.util.Arrays.copyOf(existing, existing.length + 1);
        cookies[existing.length] = new jakarta.servlet.http.Cookie("XSRF-TOKEN", token);
        request.setCookies(cookies);
        request.addHeader("X-XSRF-TOKEN", token);
        return request;
    }

    /** Request as {@code user}; unsafe methods carry a valid CSRF token. */
    protected Api api(String user) {
        return new Api(user);
    }

    /** Small fluent client over MockMvc that validates every response against the OpenAPI contract. */
    public final class Api {

        private final String user;

        Api(String user) {
            this.user = user;
        }

        public Response get(String path, Object... vars) throws Exception {
            return send(MockMvcRequestBuilders.get("/api/v1" + path, vars));
        }

        public Response post(Object body, String path, Object... vars) throws Exception {
            return send(withBody(MockMvcRequestBuilders.post("/api/v1" + path, vars), body).with(IntegrationTestBase::xsrf));
        }

        public Response post(Object body, String idempotencyKey, String path, Object... vars) throws Exception {
            MockHttpServletRequestBuilder builder = withBody(MockMvcRequestBuilders.post("/api/v1" + path, vars), body)
                    .with(IntegrationTestBase::xsrf);
            if (idempotencyKey != null) {
                builder.header("Idempotency-Key", idempotencyKey);
            }
            return send(builder);
        }

        public Response put(Object body, String ifMatch, String path, Object... vars) throws Exception {
            MockHttpServletRequestBuilder builder = withBody(MockMvcRequestBuilders.put("/api/v1" + path, vars), body)
                    .with(IntegrationTestBase::xsrf);
            if (ifMatch != null) {
                builder.header("If-Match", ifMatch);
            }
            return send(builder);
        }

        public Response delete(String path, Object... vars) throws Exception {
            return send(MockMvcRequestBuilders.delete("/api/v1" + path, vars).with(IntegrationTestBase::xsrf));
        }

        public Response send(MockHttpServletRequestBuilder builder) throws Exception {
            if (user != null) {
                builder.with(TestUsers.as(user));
            }
            MvcResult result = mvc.perform(builder).andReturn();
            Response response = new Response(result);
            OpenApiContract.validate(result);
            return response;
        }

        private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder builder, Object body) {
            builder.contentType(MediaType.APPLICATION_JSON);
            if (body != null) {
                builder.content(body instanceof String s ? s : json.write(body));
            }
            return builder;
        }
    }

    /** Captured response. */
    public final class Response {

        private final MvcResult result;

        Response(MvcResult result) {
            this.result = result;
        }

        public int status() {
            return result.getResponse().getStatus();
        }

        public String header(String name) {
            return result.getResponse().getHeader(name);
        }

        public byte[] bytes() {
            return result.getResponse().getContentAsByteArray();
        }

        public JsonNode body() {
            try {
                String text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
                return text.isEmpty() ? json.object() : json.parse(text);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        public String code() {
            return body().path("code").asString(null);
        }

        @Override
        public String toString() {
            return status() + " " + result.getResponse().getContentType() + " "
                    + new String(bytes(), StandardCharsets.UTF_8);
        }
    }
}
