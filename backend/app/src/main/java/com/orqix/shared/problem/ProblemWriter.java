package com.orqix.shared.problem;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import com.orqix.shared.web.CorrelationId;

import tools.jackson.databind.json.JsonMapper;

/** Writes problem+json responses from filters and security handlers (outside Spring MVC). */
@Component
public class ProblemWriter {

    public static final String MEDIA_TYPE = "application/problem+json";

    private final JsonMapper mapper;

    public ProblemWriter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public Problem problem(ProblemCode code, String detail, Map<String, Object> extensions) {
        return new Problem(code, detail, null, CorrelationId.current(), extensions);
    }

    public void write(HttpServletResponse response, ProblemCode code, String detail) throws IOException {
        write(response, code, detail, Map.of(), Map.of());
    }

    public void write(HttpServletResponse response, ProblemCode code, String detail, Map<String, Object> extensions,
            Map<String, String> headers) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(code.status());
        response.setContentType(MEDIA_TYPE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        headers.forEach(response::setHeader);
        mapper.writeValue(response.getOutputStream(), problem(code, detail, extensions));
        response.flushBuffer();
    }
}
