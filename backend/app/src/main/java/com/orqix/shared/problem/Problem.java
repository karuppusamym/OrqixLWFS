package com.orqix.shared.problem;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** RFC 9457 body with Orqix members {@code code} and {@code correlationId}. Never contains stack traces. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"type", "title", "status", "detail", "instance", "code", "correlationId"})
public final class Problem {

    private final String type;
    private final String title;
    private final int status;
    private final String detail;
    private final String instance;
    private final String code;
    private final String correlationId;
    private final Map<String, Object> extensions;

    public Problem(ProblemCode code, String detail, String instance, String correlationId, Map<String, Object> extensions) {
        this(code, code.status(), detail, instance, correlationId, extensions);
    }

    public Problem(ProblemCode code, int status, String detail, String instance, String correlationId,
            Map<String, Object> extensions) {
        this.type = code.type();
        this.title = code.title();
        this.status = status;
        this.detail = detail;
        this.instance = instance;
        this.code = code.name();
        this.correlationId = correlationId;
        this.extensions = extensions == null ? Map.of() : new LinkedHashMap<>(extensions);
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public int getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public String getInstance() {
        return instance;
    }

    public String getCode() {
        return code;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    @JsonAnyGetter
    public Map<String, Object> getExtensions() {
        return extensions;
    }
}
