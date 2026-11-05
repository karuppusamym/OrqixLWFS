package com.orqix.support;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ServiceNow-shaped Table API on the shared, approved WireMock origin under {@code /servicenow} (S130): incidents by
 * sys_id, encoded-query lists with sysparm_offset / sysparm_limit and X-Total-Count, creates without an idempotency
 * key, and the error body ServiceNow returns for an unknown record. Faults for creates are queued per test. Nothing
 * here contacts a real instance; the shape follows the default ServiceNow data model (illustrative).
 */
public final class ServiceNowStub {

    /** How the next create behaves. */
    public enum CreateFault {
        /** The incident is created, but the answer comes after the client gave up. */
        COMMIT_THEN_TIMEOUT,
        /** Nothing is created and the answer never comes in time (the request was lost). */
        LOST_THEN_TIMEOUT
    }

    /** One request received: method, path with decoded query, body, raw bytes and Content-Type. */
    public record Call(String method, String path, Map<String, String> query, String body, byte[] bytes,
            String contentType) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, ObjectNode> INCIDENTS = new LinkedHashMap<>();
    private static final List<Call> CALLS = new ArrayList<>();
    private static final Deque<CreateFault> FAULTS = new ArrayDeque<>();
    private static int sequence;
    private static int delayMs = 6000;

    private ServiceNowStub() {
    }

    public static synchronized void reset() {
        INCIDENTS.clear();
        CALLS.clear();
        FAULTS.clear();
        sequence = 0;
        delayMs = 6000;
    }

    /** Adds an incident and returns its sys_id. */
    public static synchronized String seed(String number, String state, String group, String shortDescription) {
        String sysId = sysId(++sequence);
        ObjectNode incident = JSON.createObjectNode();
        incident.put("sys_id", sysId);
        incident.put("number", number);
        incident.put("state", state);
        incident.put("short_description", shortDescription);
        incident.put("assignment_group", group);
        incident.put("active", "true");
        incident.put("close_code", "");
        incident.put("close_notes", "");
        incident.put("correlation_id", "");
        INCIDENTS.put(sysId, incident);
        return sysId;
    }

    public static synchronized void fault(CreateFault fault, int answerDelayMs) {
        FAULTS.addLast(fault);
        delayMs = answerDelayMs;
    }

    public static synchronized List<Call> calls() {
        return List.copyOf(CALLS);
    }

    public static synchronized long creates() {
        return CALLS.stream().filter(c -> "POST".equals(c.method())).count();
    }

    public static synchronized List<ObjectNode> incidents() {
        return List.copyOf(INCIDENTS.values());
    }

    static synchronized ResponseDefinition handle(Request request) {
        String url = request.getUrl().substring("/servicenow".length());
        String method = request.getMethod().getName();
        int q = url.indexOf('?');
        String path = q < 0 ? url : url.substring(0, q);
        Map<String, String> query = query(q < 0 ? "" : url.substring(q + 1));
        CALLS.add(new Call(method, path, query, request.getBodyAsString(), request.getBody(),
                request.getHeader("Content-Type")));
        if (path.equals("/api/now/attachment/file") && "POST".equals(method)) {
            return json(201, "{\"result\":{\"sys_id\":\"" + sysId(900 + CALLS.size()) + "\",\"size_bytes\":\""
                    + request.getBody().length + "\"}}");
        }
        if (!"application/json".equals(request.getHeader("Accept"))) {
            return json(406, "{\"error\":{\"message\":\"Accept must be application/json\"}}");
        }
        if (path.startsWith("/api/now/table/incident/") && "GET".equals(method)) {
            ObjectNode incident = INCIDENTS.get(path.substring("/api/now/table/incident/".length()));
            if (incident == null) {
                return json(404, "{\"error\":{\"message\":\"No Record found\",\"detail\":\"Record doesn't exist or ACL"
                        + " restricts the record retrieval\"},\"status\":\"failure\"}");
            }
            return json(200, JSON.createObjectNode().set("result", fields(incident, query)).toString());
        }
        if (path.equals("/api/now/table/incident") && "GET".equals(method)) {
            return list(query);
        }
        if (path.equals("/api/now/table/incident") && "POST".equals(method)) {
            return create(request.getBodyAsString());
        }
        return json(404, "{\"error\":{\"message\":\"Unknown resource\"}}");
    }

    private static ResponseDefinition list(Map<String, String> query) {
        String encoded = query.getOrDefault("sysparm_query", "");
        List<ObjectNode> matches = new ArrayList<>();
        for (ObjectNode incident : INCIDENTS.values()) {
            boolean match = true;
            for (String condition : encoded.split("\\^")) {
                if (condition.isEmpty() || condition.startsWith("ORDERBY")) {
                    continue;
                }
                int eq = condition.indexOf('=');
                if (eq < 0) {
                    return json(400, "{\"error\":{\"message\":\"Invalid query\"}}");
                }
                match &= incident.path(condition.substring(0, eq)).asString("").equals(condition.substring(eq + 1));
            }
            if (match) {
                matches.add(incident);
            }
        }
        int offset = Integer.parseInt(query.getOrDefault("sysparm_offset", "0"));
        int limit = Integer.parseInt(query.getOrDefault("sysparm_limit", "10000"));
        ArrayNode result = JSON.createArrayNode();
        for (int i = offset; i < Math.min(matches.size(), offset + limit); i++) {
            result.add(fields(matches.get(i), query));
        }
        return new ResponseDefinitionBuilder().withStatus(200).withHeader("Content-Type", "application/json")
                .withHeader("X-Total-Count", Integer.toString(matches.size()))
                .withBody(JSON.createObjectNode().set("result", result).toString()).build();
    }

    private static ResponseDefinition create(String body) {
        JsonNode input = JSON.readTree(body);
        CreateFault fault = FAULTS.pollFirst();
        if (!input.path("short_description").isString()) {
            return json(400, "{\"error\":{\"message\":\"short_description is mandatory\"}}");
        }
        if (fault == CreateFault.LOST_THEN_TIMEOUT) {
            return new ResponseDefinitionBuilder().withStatus(503).withFixedDelay(delayMs).build();
        }
        String sysId = sysId(++sequence);
        ObjectNode incident = JSON.createObjectNode();
        incident.put("sys_id", sysId);
        incident.put("number", "INC" + String.format("%07d", 12344 + sequence));
        incident.put("state", "1");
        input.properties().forEach(e -> incident.set(e.getKey(), e.getValue()));
        incident.put("active", "true");
        INCIDENTS.put(sysId, incident);
        ResponseDefinitionBuilder answer = new ResponseDefinitionBuilder().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody(JSON.createObjectNode().set("result", incident).toString());
        if (fault == CreateFault.COMMIT_THEN_TIMEOUT) {
            answer.withFixedDelay(delayMs);
        }
        return answer.build();
    }

    private static ObjectNode fields(ObjectNode incident, Map<String, String> query) {
        String list = query.get("sysparm_fields");
        if (list == null) {
            return incident.deepCopy();
        }
        ObjectNode out = JSON.createObjectNode();
        for (String field : list.split(",")) {
            if (incident.has(field)) {
                out.set(field, incident.get(field));
            }
        }
        return out;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(name, value);
        }
        return out;
    }

    private static String sysId(int n) {
        return String.format("%032x", 0xa11ce000L + n);
    }

    private static ResponseDefinition json(int status, String body) {
        return new ResponseDefinitionBuilder().withStatus(status).withHeader("Content-Type", "application/json")
                .withBody(body).build();
    }
}
