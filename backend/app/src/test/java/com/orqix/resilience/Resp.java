package com.orqix.resilience;

import java.nio.charset.StandardCharsets;

import org.springframework.test.web.servlet.MvcResult;

import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/** Captured response of one request to a {@link Node}. */
public final class Resp {

    private final MvcResult result;
    private final Json json;

    Resp(MvcResult result, Json json) {
        this.result = result;
        this.json = json;
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

    public String text() {
        return new String(bytes(), StandardCharsets.UTF_8);
    }

    public JsonNode body() {
        String text = text();
        return text.isEmpty() ? json.object() : json.parse(text);
    }

    public String code() {
        return body().path("code").asString(null);
    }

    @Override
    public String toString() {
        return status() + " " + result.getResponse().getContentType() + " " + text();
    }
}
