package com.orqix.resilience;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.orqix.shared.json.Json;
import com.orqix.support.IntegrationTestBase;
import com.orqix.support.OpenApiContract;
import com.orqix.support.TestUsers;

/**
 * Signed-in caller of one {@link Node}: dev-realm user through {@code oidcLogin()}, cookie-to-header CSRF like the
 * SPA, every response validated against contracts/openapi.yaml (T-API-01), like {@code IntegrationTestBase.Api}.
 */
public final class Client {

    private final MockMvc mvc;
    private final Json json;
    private final String user;
    private final java.util.List<String> groups;

    Client(MockMvc mvc, Json json, String user, java.util.List<String> groups) {
        this.mvc = mvc;
        this.json = json;
        this.user = user;
        this.groups = groups;
    }

    public Resp get(String path, Object... vars) throws Exception {
        return send(MockMvcRequestBuilders.get("/api/v1" + path, vars));
    }

    public Resp post(Object body, String path, Object... vars) throws Exception {
        return post(body, null, path, vars);
    }

    public Resp post(Object body, String idempotencyKey, String path, Object... vars) throws Exception {
        MockHttpServletRequestBuilder builder = withBody(MockMvcRequestBuilders.post("/api/v1" + path, vars), body)
                .with(IntegrationTestBase::xsrf);
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return send(builder);
    }

    public Resp put(Object body, String ifMatch, String path, Object... vars) throws Exception {
        MockHttpServletRequestBuilder builder = withBody(MockMvcRequestBuilders.put("/api/v1" + path, vars), body)
                .with(IntegrationTestBase::xsrf);
        if (ifMatch != null) {
            builder.header("If-Match", ifMatch);
        }
        return send(builder);
    }

    public Resp delete(String path, Object... vars) throws Exception {
        return send(MockMvcRequestBuilders.delete("/api/v1" + path, vars).with(IntegrationTestBase::xsrf));
    }

    public Resp send(MockHttpServletRequestBuilder builder) throws Exception {
        builder.with(groups == null ? TestUsers.as(user) : TestUsers.as(user, groups));
        MvcResult result = mvc.perform(builder).andReturn();
        OpenApiContract.validate(result);
        return new Resp(result, json);
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder builder, Object body) {
        builder.contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            builder.content(body instanceof String s ? s : json.write(body));
        }
        return builder;
    }
}
