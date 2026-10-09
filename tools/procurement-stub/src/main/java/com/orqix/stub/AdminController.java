package com.orqix.stub;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** Test control surface: {@code /__faults}, {@code /__orders}, {@code /__reset}. Never part of the contract. */
@RestController
public class AdminController {

    private static final Set<String> FAULT_FIELDS = Set.of("mode", "times", "delayMs");

    private final OrderStore store;
    private final FaultState faults;
    private final StubProperties properties;
    private final McpState mcp;

    public AdminController(OrderStore store, FaultState faults, StubProperties properties, McpState mcp) {
        this.store = store;
        this.faults = faults;
        this.properties = properties;
        this.mcp = mcp;
    }

    /**
     * Arms a fault for {@code POST /orders}. Body: {@code {"mode": "...", "times": n, "delayMs": ms}}.
     * {@code times} (integer &gt;= 1) is the number of requests affected; omitted means every request until
     * {@code mode: none} or {@code /__reset}. {@code delayMs} applies to {@code delay} (default 1000) and
     * {@code commitThenTimeout} (default {@code stub.commit-then-timeout-ms}).
     */
    @PostMapping(value = "/__faults", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> setFault(HttpServletRequest request) throws IOException {
        JsonNode body;
        try (InputStream in = request.getInputStream()) {
            body = OrderController.JSON.readTree(in.readNBytes(4096));
        } catch (JacksonException e) {
            return OrderController.error(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Request body is not valid JSON");
        }
        if (body == null || !body.isObject()) {
            return OrderController.error(HttpStatus.BAD_REQUEST, "INVALID_FAULT", "Body must be a JSON object");
        }
        for (String field : body.propertyNames()) {
            if (!FAULT_FIELDS.contains(field)) {
                return OrderController.error(HttpStatus.BAD_REQUEST, "INVALID_FAULT", "Unknown property '" + field + "'");
            }
        }
        JsonNode modeNode = body.get("mode");
        if (modeNode == null || !modeNode.isString()) {
            return OrderController.error(HttpStatus.BAD_REQUEST, "INVALID_FAULT", "'mode' is required (none, delay, status500, status400, status429, commitThenTimeout, dropConnection)");
        }
        FaultMode mode = FaultMode.parse(modeNode.stringValue()).orElse(null);
        if (mode == null) {
            return OrderController.error(HttpStatus.BAD_REQUEST, "INVALID_FAULT", "Unknown mode '" + modeNode.stringValue() + "'");
        }

        Integer times = null;
        JsonNode timesNode = body.get("times");
        if (timesNode != null && !timesNode.isNull()) {
            if (!timesNode.isIntegralNumber() || !timesNode.canConvertToInt() || timesNode.intValue() < 1) {
                return OrderController.error(HttpStatus.BAD_REQUEST, "INVALID_FAULT", "'times' must be an integer >= 1");
            }
            times = timesNode.intValue();
        }
        Long delayMs = null;
        JsonNode delayNode = body.get("delayMs");
        if (delayNode != null && !delayNode.isNull()) {
            if (!delayNode.isIntegralNumber() || !delayNode.canConvertToLong() || delayNode.longValue() < 0
                    || delayNode.longValue() > properties.maxFaultDelayMs()) {
                return OrderController.error(HttpStatus.BAD_REQUEST, "INVALID_FAULT",
                        "'delayMs' must be an integer between 0 and " + properties.maxFaultDelayMs());
            }
            delayMs = delayNode.longValue();
        }
        if (delayMs != null && mode != FaultMode.delay && mode != FaultMode.commitThenTimeout) {
            return OrderController.error(HttpStatus.BAD_REQUEST, "INVALID_FAULT", "'delayMs' only applies to modes delay and commitThenTimeout");
        }
        if (mode == FaultMode.delay && delayMs == null) {
            delayMs = 1000L;
        }
        if (mode == FaultMode.commitThenTimeout && delayMs == null) {
            delayMs = properties.commitThenTimeoutMs();
        }

        faults.set(mode, times, delayMs);
        return ResponseEntity.ok(faultView());
    }

    @GetMapping(value = "/__faults", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getFault() {
        return faultView();
    }

    @GetMapping(value = "/__orders", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> orders() {
        List<Map<String, Object>> orders = store.all().stream().map(order -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", order.id());
            view.put("status", order.status());
            view.put("idempotencyKey", order.idempotencyKey());
            view.put("request", order.request());
            view.put("createdAt", order.createdAt().toString());
            return view;
        }).toList();
        OrderStore.Stats stats = store.stats();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", orders.size());
        result.put("orders", orders);
        result.put("createRequests", stats.createRequests());
        result.put("replays", stats.replays());
        result.put("conflicts", stats.conflicts());
        return result;
    }

    @PostMapping(value = "/__reset", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> reset() {
        store.reset();
        faults.reset();
        mcp.reset();
        return Map.of("status", "RESET");
    }

    private Map<String, Object> faultView() {
        FaultState.Snapshot snapshot = faults.snapshot();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("mode", snapshot.mode().name());
        view.put("remaining", snapshot.remaining());
        view.put("delayMs", snapshot.delayMs());
        view.put("applied", snapshot.applied());
        return view;
    }
}
