# Procurement stub

Standalone Spring Boot 4.0.8 / Java 21 service that implements the procurement contract of
`examples/connections/procurement.connection.json` (docs/build/S1 section 6.2). **A development and test fixture only**: it is
never evidence of a real integration (decision D07). State is in memory and thread-safe; restarting or `POST /__reset` clears it.

The request body of `POST /orders` is validated against the `createOrder.requestSchema` of the shared connection definition,
which Maven copies from `examples/connections/` at build time. The stub therefore cannot drift from the contract and the file
is never duplicated.

## Run

```bash
cd tools/procurement-stub
./mvnw -B -ntp verify                       # 58 tests over real HTTP (random port)
./mvnw -B -ntp spring-boot:run              # http://localhost:8095   (override: --server.port=... or SERVER_PORT)
docker build -f tools/procurement-stub/Dockerfile -t orqix/procurement-stub:dev .   # from the repository root
```

Ports: **8095 on the host** (the default of the application), **8090 inside the Compose network** (the image sets
`SERVER_PORT=8090`; Compose maps `${STUB_HOST_PORT:-8095}:8090`). The image is multi-stage, runs as UID 10001 and has a health check.

## Contract

| Endpoint | Behavior |
|---|---|
| `POST /orders` | Header `Idempotency-Key` required (missing or blank: 400). Body is validated against the contract schema (400 with a `violations` list). New key: **201** `{"id":"PO-<n>","status":"CREATED"}` (`n` counts from 1 after a reset). Same key and an equivalent body (key order and number formatting do not matter): **200** with the original order and header `Idempotent-Replayed: true`. Same key and a different body: **422** `IDEMPOTENCY_KEY_REUSED`. An invalid request never consumes the key |
| `GET /orders?idempotencyKey=...` | **200** with the order, **404** if unknown, 400 without the parameter |
| `GET /health` | **200** `{"status":"UP"}` |

Error bodies are JSON: `{"error": "<CODE>", "message": "...", "violations": [...], "existingOrderId": "..."}` (optional members omitted).

## Test control surface

| Endpoint | Behavior |
|---|---|
| `POST /__faults` `{"mode": ..., "times": n, "delayMs": ms}` | Arms a fault for `POST /orders`. `times` (integer >= 1) is the number of affected requests; omitted means every request until `mode: none` or `/__reset`. `delayMs` is allowed for `delay` and `commitThenTimeout` only (0 to 600000) |
| `GET /__faults` | The active fault: `{"mode","remaining","delayMs","applied"}` |
| `GET /__orders` | `{"count","orders":[{"id","status","idempotencyKey","request","createdAt"}],"createRequests","replays","conflicts"}` |
| `POST /__reset` | Clears orders, counters, the id sequence and faults |

Fault modes (they apply to `POST /orders` only, so health checks and reconciliation lookups stay reliable):

| Mode | Effect |
|---|---|
| `none` | Clears any active fault |
| `delay` | Sleeps `delayMs` (default 1000), then processes normally (T-RES-03: `delayMs: 25000`) |
| `status500`, `status400`, `status429` | Answers with that status and creates no order; 429 carries `Retry-After: 1` |
| `commitThenTimeout` | **Creates the order**, then withholds the response for `delayMs` (default `stub.commit-then-timeout-ms` = 35000, longer than the 20 s `maxTimeoutSeconds` of the contract). The order is visible through `GET /orders` and `/__orders` while the client times out. Nothing is delayed when the request is rejected before an order exists |
| `dropConnection` | Closes the TCP connection without writing any response, before the request body is read |

Faults are consumed in request order; use `times` for "fail twice, then succeed". Sleeping requests run on virtual threads,
so many concurrent delayed requests are fine.

## Implementation notes

* `dropConnection` is not possible with the servlet API. A Tomcat valve (`ConnectionDropSupport`) exposes a request attribute
  that puts the Coyote processor into its `CLOSE_NOW` state; Tomcat then discards output and closes the socket. This is tied to
  the embedded Tomcat that Spring Boot starts; the test `FaultModesTest.dropConnectionClosesTheSocketWithoutAnyResponse`
  reads the raw socket and asserts that zero bytes arrive.
* Idempotency decisions use a canonical form of the body (sorted keys, numbers compared by value), serialised under one lock;
  the sleeping fault modes run outside the lock.
* Configuration (`src/main/resources/application.yaml`, prefix `stub.`): `contract-resource`, `contract-operation`,
  `commit-then-timeout-ms`, `max-fault-delay-ms`, `max-body-bytes`.
