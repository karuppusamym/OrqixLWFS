package com.orqix.stub;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The procurement contract (docs/build/S1 section 6.2): {@code POST /orders}, {@code GET /orders}, {@code GET /health}. */
@RestController
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);
    private static final int MAX_KEY_LENGTH = 255;

    /** Strict parsing: numbers keep their exact decimal value, trailing tokens and duplicate keys are errors. */
    static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final OrderStore store;
    private final FaultState faults;
    private final ContractValidator contract;
    private final StubProperties properties;

    public OrderController(OrderStore store, FaultState faults, ContractValidator contract, StubProperties properties) {
        this.store = store;
        this.faults = faults;
        this.contract = contract;
        this.properties = properties;
    }

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @PostMapping(value = "/orders", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createOrder(HttpServletRequest request) throws IOException {
        Optional<FaultState.AppliedFault> fault = faults.consume();
        boolean commitThenTimeout = false;
        if (fault.isPresent()) {
            FaultState.AppliedFault applied = fault.get();
            switch (applied.mode()) {
                case dropConnection -> {
                    // Before anything is read: the client sees a closed connection and no response.
                    if (ConnectionDropSupport.drop(request)) {
                        return null;
                    }
                    log.warn("dropConnection requested but the server cannot close the socket; answering 500 instead");
                    return error(HttpStatus.INTERNAL_SERVER_ERROR, "INJECTED_FAULT", "dropConnection is not supported by this server");
                }
                case status500 -> {
                    return error(HttpStatus.INTERNAL_SERVER_ERROR, "INJECTED_FAULT", "injected status500");
                }
                case status400 -> {
                    return error(HttpStatus.BAD_REQUEST, "INJECTED_FAULT", "injected status400");
                }
                case status429 -> {
                    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                            .header("Retry-After", "1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(ErrorBody.of("INJECTED_FAULT", "injected status429"));
                }
                case delay -> sleep(applied.delayMs() != null ? applied.delayMs() : 1000);
                case commitThenTimeout -> commitThenTimeout = true;
                case none -> {
                }
            }
        }

        String key = request.getHeader(contract.idempotencyHeader());
        if (key == null || key.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY", "Header " + contract.idempotencyHeader() + " is required");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Header " + contract.idempotencyHeader() + " exceeds " + MAX_KEY_LENGTH + " characters");
        }

        byte[] raw = readBody(request);
        if (raw == null) {
            return error(HttpStatus.CONTENT_TOO_LARGE, "BODY_TOO_LARGE", "Request body exceeds " + properties.maxBodyBytes() + " bytes");
        }
        JsonNode body;
        try {
            body = JSON.readTree(raw);
        } catch (JacksonException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Request body is not valid JSON");
        }
        if (body == null || body.isMissingNode()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Request body is empty");
        }
        List<ContractValidator.Violation> violations = contract.validate(body);
        if (!violations.isEmpty()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ErrorBody("INVALID_REQUEST", "Request body violates the createOrder request schema", violations, null));
        }

        OrderStore.Outcome outcome = store.createOrReplay(key, CanonicalJson.hash(body, JSON), body);
        if (commitThenTimeout) {
            // The order is committed; the response is deliberately withheld past the client's timeout.
            long sleepMs = fault.get().delayMs() != null ? fault.get().delayMs() : properties.commitThenTimeoutMs();
            log.info("commitThenTimeout: order {} committed, withholding the response for {} ms", outcome.order().id(), sleepMs);
            sleep(sleepMs);
        }

        return switch (outcome) {
            case OrderStore.Created created -> {
                log.info("created {} for key {}", created.order().id(), key);
                yield ResponseEntity.status(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(created.order().view());
            }
            case OrderStore.Replayed replayed -> {
                log.info("replayed {} for key {}", replayed.order().id(), key);
                yield ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotent-Replayed", "true")
                        .body(replayed.order().view());
            }
            case OrderStore.Conflict conflict -> ResponseEntity.unprocessableContent()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ErrorBody("IDEMPOTENCY_KEY_REUSED",
                            "The Idempotency-Key was already used with a different request body", null, conflict.order().id()));
        };
    }

    @GetMapping(value = "/orders", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> findOrder(@RequestParam(name = "idempotencyKey", required = false) String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", "Query parameter idempotencyKey is required");
        }
        return store.findByIdempotencyKey(idempotencyKey)
                .<ResponseEntity<?>>map(order -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(order.view()))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND", "No order for this idempotency key"));
    }

    private byte[] readBody(HttpServletRequest request) throws IOException {
        int limit = properties.maxBodyBytes();
        try (InputStream in = request.getInputStream()) {
            byte[] data = in.readNBytes(limit + 1);
            return data.length > limit ? null : data;
        }
    }

    static ResponseEntity<ErrorBody> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(ErrorBody.of(code, message));
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
