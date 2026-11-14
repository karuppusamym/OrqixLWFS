# Orqix SDK for Java

Java 21. Two artifacts (group `com.orqix.sdk`, version `0.1.0`):

- `orqix-client`: typed client for the machine API, the external-worker loop and the callback-token helper. Only
  runtime dependency: Jackson 3 (`tools.jackson.core:jackson-databind`); HTTP is `java.net.http`.
- `orqix-spring-boot-starter`: auto-configuration for Spring Boot 4 host applications and the `@OrqixWorker`
  annotation.

Not yet published to a repository; build and install locally with `sh ./mvnw -B install`.

## Client

```java
OrqixClient orqix = OrqixClient.builder()
        .baseUrl("https://orqix.example/api/v1")
        .workspaceId(workspaceId)
        .token(tokens::currentAccessToken)      // called for every request
        .build();

Run run = orqix.startRun("order-" + orderId, workflowId, Map.of("amount", 1200));
RunDetail detail = orqix.getRun(run.id());
EventPage events = orqix.listRunEvents(run.id(), 0, 50);
orqix.sendMessage("payment-" + orderId, "payment-received", orderId, Map.of("paid", true));
```

Errors are `OrqixApiException` with `status()`, `code()` (for example `RUN_SUSPENDED`), `correlationId()`,
`retryAfterSeconds()`, `isTransient()` and `isAuthenticationFailure()`. Calls to deprecated operations are reported once
through `onDeprecation(...)` (default: a log warning).

Models in `com.orqix.sdk.model` are generated from the contract. Enum fields are strings with constants
(`Lifecycle.WAITING`), so a value added by a later server release never breaks deserialization; unknown fields are
ignored. Record constructors can gain components in a minor SDK release: build requests with the client helpers.

## Worker

```java
try (OrqixWorker worker = OrqixWorker.builder(orqix, "address-checker-1")
        .handler("address-check", job -> {
            if (!directory.knows(job.input().get("postcode"))) {
                throw new BusinessErrorException("ADDRESS_UNKNOWN", "No such postcode");
            }
            return Map.of("valid", true, "score", 0.9);
        })
        .build()) {
    worker.run();   // until worker.stop()
}
```

Throw `JobFailedException(message, retryable, retryAfterSeconds)` for a technical failure. For asynchronous gateways
use `fetchAndLock`, `awaitCallback`, `completeCallback` and `failCallback` explicitly and persist the `CallbackTicket`.

## Callback tokens

```java
CallbackTokenIssued issued = orqix.issueCallbackToken(waitId);         // run.operate; shown once
CallbackTokenClient wait = CallbackTokenClient.create(baseUrl, workspaceId, issued.token());
CallbackPoll poll = wait.poll(null);                                   // pass poll.etag() next time
wait.resume("shipment-" + shipmentId, Map.of("trackingNumber", "1Z999"));
```

## Spring Boot starter

```yaml
orqix:
  base-url: https://orqix.example/api/v1
  workspace-id: 00000000-0000-7000-8000-000000000201
  token-file: /var/run/secrets/orqix/token   # or define an OrqixTokenProvider bean
  worker:
    id: orders-worker-1          # default: spring.application.name plus a random suffix
    lease-seconds: 120
    max-jobs: 5
    poll-interval: 2s
    shutdown-timeout: 30s
    enabled: true
```

```java
@Component
class AddressChecks {
    @OrqixWorker(topic = "address-check")
    Map<String, Object> check(Map<String, Object> input) throws BusinessErrorException { ... }

    @OrqixWorker("supplier-check")
    void supplier(Job job) { ... }   // void: a step without declared results
}
```

Nothing is configured until `orqix.base-url` is set. The starter provides an `OrqixClient` bean (replace it by defining
your own) and one worker loop for all `@OrqixWorker` methods, started after the application and stopped first, waiting
for the job in progress. A method takes nothing, the `Job` or its input map; a wrong signature or a topic served twice
fails at startup. The token file is reread for every request, so your identity service can rotate it. Never put a token
in the configuration itself.

## Tests

```bash
sh ./mvnw -B verify
```

Tests run against a scripted loopback server (`ScriptedServer`, JDK `HttpServer`) with a fixed foreign time zone and
locale, so they do not depend on the machine's.
