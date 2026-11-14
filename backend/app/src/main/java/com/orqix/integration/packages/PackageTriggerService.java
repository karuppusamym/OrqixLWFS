package com.orqix.integration.packages;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.KafkaDispatch;
import com.orqix.integration.SecretNamespaces;
import com.orqix.integration.adapters.AdapterRules;
import com.orqix.integration.internal.SecretStore;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Package triggers (CG-C05, docs/build/S133 section 5): webhook and polling triggers declared by an installed package
 * and enabled in a workspace. Both start a workflow or send a message through the same port as Kafka triggers (S99
 * K4-K8), as the run-as person who enabled the trigger, one transaction per event with its dedupe row. The event's
 * content fills mapped values and the correlation key only; workspace, workflow and message name come from the trigger.
 *
 * <p>Webhook verification schemes: {@code hmacSha256} (the S47 scheme with the package's header names and encoding),
 * {@code jwt} (HS256 with the trigger secret, or RS256 when the secret is a public key; issuer, audience and expiry
 * checked, lifetime bounded) and {@code basic} (weaker: only over HTTPS, a dedicated per-trigger secret).
 */
@Service
public class PackageTriggerService {

    public static final int MAX_TRIGGERS = 10;
    public static final int MAX_BODY_BYTES = 64 * 1024;
    private static final long CLOCK_SKEW_SECONDS = 30;

    public record ConfigureRequest(String packageId, String triggerKey, UUID workflowId, String secretRef,
            String jwtIssuer, String jwtAudience, Boolean enabled, Integer expectedVersion) {
    }

    public record TriggerStatus(String watermark, Instant nextRunAt, Instant lastRunAt, Instant lastSuccessAt,
            String lastErrorCode, Long lagSeconds, long polls, long recordsHandled, long duplicates, long deadRecords,
            boolean leased) {
    }

    public record TriggerView(UUID id, String packageId, String packageVersion, String triggerKey, String label,
            String kind, String action, UUID workflowId, String messageName, String verification, boolean weaker,
            String secretRef, String jwtIssuer, String jwtAudience, String connectionKey, String every, boolean enabled,
            int version, String receivePath, TriggerStatus status) {
    }

    public record TriggerList(List<TriggerView> items) {
    }

    public record DeadRecord(UUID id, String errorCode, String valueSha256, Instant receivedAt) {
    }

    public record DeadRecordList(List<DeadRecord> items) {
    }

    /** {@code status}: started, resumed, buffered or duplicate. */
    public record Accepted(String status, UUID runId) {
    }

    /** A stored trigger as the runtime needs it. */
    record Trigger(UUID id, UUID workspaceId, String packageId, String key, String kind, JsonNode definition,
            String action, UUID workflowId, String messageName, String secretRef, String jwtIssuer, String jwtAudience,
            String connectionKey, UUID runAs, boolean enabled, int version) {
    }

    /** One event of a trigger (webhook body or polled record) and its outcome. */
    record Outcome(String status, String code, UUID runId) {
    }

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final Json json;
    private final Clock clock;
    private final SecretStore secrets;
    private final KafkaDispatch dispatch;
    private final TransactionTemplate transactions;
    private final ConnectorPackageService packages;

    public PackageTriggerService(JdbcClient jdbc, PolicyService policy, AuditWriter audit, Json json, Clock clock,
            SecretStore secrets, KafkaDispatch dispatch, TransactionTemplate transactions, ConnectorPackageService packages) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.secrets = secrets;
        this.dispatch = dispatch;
        this.transactions = transactions;
        this.packages = packages;
    }

    // ------------------------------------------------------------------------------------------------- authoring

    @Transactional(readOnly = true)
    public TriggerList list(Caller caller, UUID ws) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        if (!access.has(Permission.CONNECTOR_MANAGE) && !access.has(Permission.RUN_OPERATE)) {
            throw policy.deny(ws, caller.subjectId(), "connector.manage", "listPackageTriggers", false,
                    new ApiException(ProblemCode.FORBIDDEN));
        }
        return new TriggerList(triggers(ws, null).stream().map(this::view).toList());
    }

    @Transactional
    public TriggerView create(Caller caller, UUID ws, ConfigureRequest request) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.CONNECTOR_MANAGE, "createPackageTrigger", true);
        if (request == null || request.packageId() == null || request.triggerKey() == null) {
            throw ConnectorPackageService.invalid("/triggerKey", "required", "Choose a package and one of its triggers.");
        }
        record Installed(UUID versionId, String version) {
        }
        Installed installed = jdbc.sql("""
                SELECT package_version_id, version FROM connector_package_install
                WHERE workspace_id = :ws AND package_id = :pkg AND status = 'active'
                """).param("ws", ws).param("pkg", request.packageId())
                .query((rs, n) -> new Installed(Sql.uuid(rs, "package_version_id"), rs.getString("version"))).optional()
                .orElseThrow(() -> ConnectorPackageService.invalid("/packageId", "enum",
                        "The package is not installed in this workspace."));
        JsonNode definition = packages.contents(access.workspace().organizationId(), installed.versionId()).triggers()
                .get(request.triggerKey());
        if (definition == null) {
            throw ConnectorPackageService.invalid("/triggerKey", "enum", "The installed package has no trigger "
                    + request.triggerKey() + ".");
        }
        jdbc.sql("SELECT id FROM workspace WHERE id = :ws FOR UPDATE").param("ws", ws).query(UUID.class).single();
        int count = jdbc.sql("SELECT count(*) FROM package_trigger WHERE workspace_id = :ws").param("ws", ws)
                .query(Integer.class).single();
        if (count >= MAX_TRIGGERS) {
            throw ConnectorPackageService.invalid("/triggerKey", "maxItems", "A workspace can enable at most "
                    + MAX_TRIGGERS + " package triggers.");
        }
        Settings s = settings(access, ws, definition, request);
        UUID id = Ids.newId();
        boolean enabled = !Boolean.FALSE.equals(request.enabled());
        try {
            jdbc.sql("""
                    INSERT INTO package_trigger (id, workspace_id, package_id, trigger_key, package_version_id, kind, definition,
                        action, workflow_id, message_name, secret_ref, jwt_issuer, jwt_audience, connection_key, run_as,
                        enabled, next_run_at, created_by)
                    VALUES (:id, :ws, :pkg, :key, :ver, :kind, CAST(:def AS jsonb), :action, :wf, :msg, :ref, :iss, :aud,
                            :conn, :by, :enabled, :next, :by)
                    """).param("id", id).param("ws", ws).param("pkg", request.packageId())
                    .param("key", request.triggerKey()).param("ver", installed.versionId())
                    .param("kind", definition.path("kind").asString()).param("def", json.write(definition))
                    .param("action", s.action()).param("wf", s.workflowId()).param("msg", s.messageName())
                    .param("ref", s.secretRef()).param("iss", s.issuer()).param("aud", s.audience())
                    .param("conn", s.connectionKey()).param("by", caller.subjectId()).param("enabled", enabled)
                    .param("next", "poll".equals(definition.path("kind").asString()) ? Sql.ts(clock.instant()) : null)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ProblemCode.DUPLICATE_KEY, "This trigger is already set up in this workspace.");
        }
        configured(caller, ws, id, request.packageId(), request.triggerKey(), definition, s.action(), enabled, 1);
        return view(triggers(ws, id).get(0));
    }

    @Transactional
    public TriggerView update(Caller caller, UUID ws, UUID id, ConfigureRequest request) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        Trigger existing = jdbc.sql("SELECT id FROM package_trigger WHERE workspace_id = :ws AND id = :id FOR UPDATE")
                .param("ws", ws).param("id", id).query(UUID.class).optional().map(x -> triggers(ws, id).get(0))
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.CONNECTOR_MANAGE, "updatePackageTrigger", true);
        if (request == null || request.expectedVersion() == null || request.expectedVersion() != existing.version()) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT, "The trigger changed. Reload first.")
                    .with("currentVersion", existing.version());
        }
        Settings s = settings(access, ws, existing.definition(), request);
        boolean enabled = !Boolean.FALSE.equals(request.enabled());
        jdbc.sql("""
                UPDATE package_trigger SET workflow_id = :wf, message_name = :msg, secret_ref = :ref, jwt_issuer = :iss,
                       jwt_audience = :aud, run_as = :by, enabled = :enabled, version = version + 1, updated_at = now(),
                       next_run_at = CASE WHEN kind = 'poll' AND :enabled AND NOT enabled THEN now() ELSE next_run_at END
                WHERE id = :id
                """).param("wf", s.workflowId()).param("msg", s.messageName()).param("ref", s.secretRef())
                .param("iss", s.issuer()).param("aud", s.audience()).param("by", caller.subjectId())
                .param("enabled", enabled).param("id", id).update();
        configured(caller, ws, id, existing.packageId(), existing.key(), existing.definition(), s.action(), enabled,
                existing.version() + 1);
        return view(triggers(ws, id).get(0));
    }

    private record Settings(String action, UUID workflowId, String messageName, String secretRef, String issuer,
            String audience, String connectionKey) {
    }

    /** Validates the workspace settings of a trigger and the run-as rights of the caller (S99 K8). */
    private Settings settings(WorkspaceAccess access, UUID ws, JsonNode definition, ConfigureRequest request) {
        String action = definition.path("action").path("type").asString();
        UUID workflowId = null;
        String message = null;
        if ("start".equals(action)) {
            if (request.workflowId() == null || jdbc.sql("SELECT count(*) FROM workflow WHERE id = :id AND workspace_id = :ws")
                    .param("id", request.workflowId()).param("ws", ws).query(Integer.class).single() == 0) {
                throw ConnectorPackageService.invalid("/workflowId", "required", "Choose the workflow this trigger starts.");
            }
            if (!access.has(Permission.WORKFLOW_START) && !access.workflowStartGrants().contains(request.workflowId())) {
                throw ConnectorPackageService.invalid("/workflowId", "PKG_RUN_AS", "Triggers start requests as you; you"
                        + " need workflow.start for this workflow.");
            }
            workflowId = request.workflowId();
        } else {
            if (request.workflowId() != null) {
                throw ConnectorPackageService.invalid("/workflowId", "not", "A message trigger resumes waiting requests;"
                        + " it does not start a workflow.");
            }
            if (!access.has(Permission.MESSAGE_SEND)) {
                throw ConnectorPackageService.invalid("/triggerKey", "PKG_RUN_AS", "Triggers send messages as you; you"
                        + " need message.send.");
            }
            message = definition.path("action").path("message").asString();
        }
        String secretRef = null;
        String issuer = null;
        String audience = null;
        String connection = null;
        if ("webhook".equals(definition.path("kind").asString())) {
            secretRef = request.secretRef();
            if (secretRef == null || !secretRef.matches("^[a-z0-9][a-z0-9-]{0,62}$")) {
                throw ConnectorPackageService.invalid("/secretRef", "pattern", "Name the mounted secret this trigger"
                        + " verifies events with.");
            }
            SecretNamespaces.refuseReserved(secretRef, "/secretRef");
            if ("jwt".equals(definition.path("verification").path("scheme").asString())) {
                issuer = request.jwtIssuer() == null ? null : request.jwtIssuer().strip();
                audience = request.jwtAudience() == null ? null : request.jwtAudience().strip();
                if (issuer == null || issuer.isEmpty() || issuer.length() > 200 || audience == null || audience.isEmpty()
                        || audience.length() > 200) {
                    throw ConnectorPackageService.invalid("/jwtIssuer", "required", "A JWT trigger needs the expected"
                            + " issuer and audience.");
                }
            }
        } else {
            if (request.secretRef() != null) {
                throw ConnectorPackageService.invalid("/secretRef", "not", "A polling trigger uses the connection's binding.");
            }
            connection = definition.path("connection").asString();
        }
        return new Settings(action, workflowId, message, secretRef, issuer, audience, connection);
    }

    private void configured(Caller caller, UUID ws, UUID id, String packageId, String key, JsonNode definition,
            String action, boolean enabled, int version) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("packageId", packageId);
        metadata.put("triggerKey", key);
        metadata.put("kind", definition.path("kind").asString());
        metadata.put("action", action);
        metadata.put("enabled", enabled);
        metadata.put("version", version);
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(),
                "connector.trigger-configured", "packageTrigger", id.toString(), AuditWriter.Outcome.SUCCESS, metadata));
    }

    @Transactional(readOnly = true)
    public DeadRecordList dead(Caller caller, UUID ws, UUID id) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        if (triggers(ws, id).isEmpty()) {
            throw ApiException.notFound();
        }
        if (!access.has(Permission.CONNECTOR_MANAGE) && !access.has(Permission.RUN_OPERATE)) {
            throw policy.deny(ws, caller.subjectId(), "run.operate", "listPackageTriggerDeadRecords", false,
                    new ApiException(ProblemCode.FORBIDDEN));
        }
        return new DeadRecordList(jdbc.sql("""
                SELECT id, error_code, value_sha256, received_at FROM package_trigger_record
                WHERE trigger_id = :id AND status = 'dead' ORDER BY received_at DESC LIMIT 100
                """).param("id", id).query((rs, n) -> new DeadRecord(Sql.uuid(rs, "id"), rs.getString("error_code"),
                        rs.getString("value_sha256"), Sql.instant(rs, "received_at"))).list());
    }

    // ------------------------------------------------------------------------------------------------- webhook

    /**
     * A webhook event, authenticated by its verification scheme alone (no session). Unknown, disabled and polling
     * triggers answer 404; every verification failure answers the same 401.
     *
     * @param secure true when the request reached Orqix over HTTPS (basic credentials need it)
     */
    public Accepted receive(UUID ws, String triggerId, Map<String, String> headers, byte[] body, boolean secure) {
        UUID id;
        try {
            id = UUID.fromString(triggerId);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound();
        }
        Trigger trigger = jdbc.sql(SELECT + " WHERE workspace_id = :ws AND id = :id").param("ws", ws).param("id", id)
                .query((rs, n) -> trigger(rs)).optional().filter(Trigger::enabled)
                .filter(t -> "webhook".equals(t.kind())).orElseThrow(ApiException::notFound);
        byte[] bytes = body == null ? new byte[0] : body;
        if (bytes.length > MAX_BODY_BYTES) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "An event can be at most 64 KiB.");
        }
        verify(trigger, headers, bytes, secure);
        JsonNode payload;
        try {
            payload = json.parse(bytes);
        } catch (RuntimeException e) {
            payload = null;
        }
        if (payload == null || !payload.isObject() || Json.depth(payload) > 10) {
            record(trigger, "invalid:" + Json.sha256Hex(bytes), Json.sha256Hex(bytes), "malformed");
            throw ConnectorPackageService.invalid("/", "PKG_EVENT", "The event is not a JSON object (malformed).");
        }
        Outcome outcome = handle(trigger, payload, Json.sha256Hex(bytes));
        if ("dead".equals(outcome.status())) {
            throw ConnectorPackageService.invalid("/", "PKG_EVENT", "The event cannot be used (" + outcome.code() + ").");
        }
        if ("retry".equals(outcome.status()) && "rate_limited".equals(outcome.code())) {
            // CG-P05 (S150): the workspace's admission limit (or another rate limit); nothing was recorded, so the
            // sender retries the same event. 60 s is the longest wait of the one-minute window.
            throw new ApiException(ProblemCode.RATE_LIMITED, "Too many requests in this workspace; try again later.")
                    .withHeader("Retry-After", "60");
        }
        if ("retry".equals(outcome.status())) {
            throw new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE, "Try again later (" + outcome.code() + ").")
                    .withHeader("Retry-After", "30");
        }
        return new Accepted(outcome.status(), outcome.runId());
    }

    private void verify(Trigger trigger, Map<String, String> headers, byte[] body, boolean secure) {
        JsonNode v = trigger.definition().path("verification");
        String secret = secrets.read(trigger.secretRef(), trigger.workspaceId()).orElseThrow(() -> new ApiException(
                ProblemCode.DEPENDENCY_UNAVAILABLE, "The trigger secret is not available."));
        boolean ok = switch (v.path("scheme").asString()) {
            case "hmacSha256" -> hmac(v, secret, headers, body);
            case "jwt" -> jwt(v, trigger, secret, header(headers, "Authorization"));
            case "basic" -> secure && basic(secret, header(headers, "Authorization"));
            default -> false;
        };
        if (!ok) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED, "The event could not be verified.");
        }
    }

    private boolean hmac(JsonNode v, String secret, Map<String, String> headers, byte[] body) {
        String timestamp = header(headers, v.path("timestampHeader").asString());
        String signature = header(headers, v.path("signatureHeader").asString());
        if (timestamp == null || signature == null || !timestamp.strip().matches("^[0-9]{1,12}$")) {
            return false;
        }
        long tolerance = v.path("toleranceSeconds").asLong(300);
        if (Math.abs(clock.instant().getEpochSecond() - Long.parseLong(timestamp.strip())) > tolerance) {
            return false;
        }
        byte[] mac = hmacSha256(secret.getBytes(StandardCharsets.UTF_8), (timestamp.strip() + ".").getBytes(StandardCharsets.UTF_8),
                body);
        String encoded = "base64".equals(v.path("encoding").asString()) ? Base64.getEncoder().encodeToString(mac)
                : HexFormat.of().formatHex(mac);
        String expected = v.path("prefix").asString("") + encoded;
        String given = signature.strip();
        if ("hex".equals(v.path("encoding").asString())) {
            given = given.toLowerCase(java.util.Locale.ROOT);
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    /** HMAC-SHA256 over the concatenated parts; shared with tests and the package README. */
    public static byte[] hmacSha256(byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] part : parts) {
                mac.update(part);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean jwt(JsonNode v, Trigger trigger, String secret, String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        String[] parts = authorization.substring(7).strip().split("\\.", -1);
        if (parts.length != 3 || authorization.length() > 8192) {
            return false;
        }
        try {
            Base64.Decoder b64 = Base64.getUrlDecoder();
            JsonNode header = json.parse(b64.decode(parts[0]));
            JsonNode claims = json.parse(b64.decode(parts[1]));
            String alg = header.path("alg").asString();
            boolean allowed = false;
            for (JsonNode a : v.path("algorithms")) {
                allowed |= a.asString().equals(alg);
            }
            boolean publicKey = secret.strip().startsWith("-----BEGIN PUBLIC KEY-----");
            if (!allowed || header.has("crit") || ("RS256".equals(alg) != publicKey)) {
                return false; // the key decides the algorithm: a public key never verifies HS256 (no confusion)
            }
            byte[] signed = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
            byte[] signature = b64.decode(parts[2]);
            boolean valid;
            if ("HS256".equals(alg)) {
                valid = MessageDigest.isEqual(hmacSha256(secret.getBytes(StandardCharsets.UTF_8), signed), signature);
            } else {
                PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder()
                        .decode(secret.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", ""))));
                Signature verifier = Signature.getInstance("SHA256withRSA");
                verifier.initVerify(key);
                verifier.update(signed);
                valid = verifier.verify(signature);
            }
            if (!valid) {
                return false;
            }
            long now = clock.instant().getEpochSecond();
            if (!claims.path("exp").isIntegralNumber() || claims.path("exp").asLong() < now - CLOCK_SKEW_SECONDS) {
                return false;
            }
            if (claims.has("nbf") && claims.path("nbf").asLong() > now + CLOCK_SKEW_SECONDS) {
                return false;
            }
            long maxLifetime = v.path("maxLifetimeSeconds").asLong(300);
            long start = claims.has("iat") ? claims.path("iat").asLong() : now;
            if (claims.path("exp").asLong() - start > maxLifetime) {
                return false;
            }
            if (!trigger.jwtIssuer().equals(claims.path("iss").asString(null))) {
                return false;
            }
            JsonNode aud = claims.path("aud");
            boolean audience = aud.isString() && aud.asString().equals(trigger.jwtAudience());
            for (JsonNode a : aud.isArray() ? aud : json.mapper().createArrayNode()) {
                audience |= a.asString().equals(trigger.jwtAudience());
            }
            return audience;
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    private static boolean basic(String secret, String authorization) {
        if (authorization == null || !authorization.startsWith("Basic ")) {
            return false;
        }
        byte[] expected = Base64.getEncoder().encode(secret.strip().getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(expected, authorization.substring(6).strip().getBytes(StandardCharsets.UTF_8));
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------- one event

    /**
     * Records the event once per dedupe key and starts or resumes in the same transaction. Permanent problems become
     * a dead record (code only, never content); transient ones are reported as {@code retry} and record nothing.
     */
    Outcome handle(Trigger trigger, JsonNode event, String sha) {
        JsonNode d = trigger.definition();
        StringBuilder dedupe = new StringBuilder();
        for (JsonNode pointer : d.path("dedupeKey")) {
            JsonNode value = event.at(pointer.asString());
            if (value.isMissingNode() || value.isNull() || value.isContainer() || value.asString().isEmpty()) {
                return dead(trigger, "nokey:" + sha, sha, "no_dedupe_key");
            }
            dedupe.append(dedupe.isEmpty() ? "" : "|").append(value.asString());
        }
        String key = dedupe.length() > 300 ? Json.sha256Hex(dedupe.toString()) : dedupe.toString();
        if (Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM package_trigger_record WHERE trigger_id = :t"
                + " AND dedupe_key = :k)").param("t", trigger.id()).param("k", key).query(Boolean.class).single())) {
            counter(trigger.id(), "duplicates");
            return new Outcome("duplicate", null, null);
        }
        ObjectNode mapped;
        try {
            mapped = d.path("mapping").isEmpty() ? json.object() : AdapterRules.mapInput(d.path("mapping"), event);
        } catch (AdapterRules.Invalid e) {
            return dead(trigger, key, sha, "unmapped");
        }
        String correlation = null;
        if ("message".equals(trigger.action())) {
            JsonNode c = event.at(d.path("action").path("correlationFrom").asString());
            if (c.isMissingNode() || !(c.isString() || c.isIntegralNumber()) || c.asString().isEmpty()
                    || c.asString().length() > 200) {
                return dead(trigger, key, sha, "no_correlation_key");
            }
            correlation = c.asString();
        }
        String digest = Json.sha256Hex(trigger.id() + ":" + key);
        String idempotencyKey = "pkgtrigger:" + digest.substring(0, 40);
        String finalCorrelation = correlation;
        try {
            return transactions.execute(status -> {
                UUID recordId = Ids.newId();
                int inserted = jdbc.sql("""
                        INSERT INTO package_trigger_record (id, workspace_id, trigger_id, dedupe_key, status, value_sha256, received_at)
                        VALUES (:id, :ws, :t, :key, 'started', :sha, :now) ON CONFLICT (trigger_id, dedupe_key) DO NOTHING
                        """).param("id", recordId).param("ws", trigger.workspaceId()).param("t", trigger.id())
                        .param("key", key).param("sha", sha).param("now", Sql.ts(clock.instant())).update();
                if (inserted == 0) {
                    counter(trigger.id(), "duplicates");
                    return new Outcome("duplicate", null, null);
                }
                KafkaDispatch.Dispatched done = "start".equals(trigger.action())
                        ? dispatch.start(trigger.workspaceId(), trigger.runAs(), trigger.workflowId(), idempotencyKey,
                                "pt-" + digest.substring(0, 12), mapped)
                        : dispatch.resume(trigger.workspaceId(), trigger.runAs(), trigger.messageName(), finalCorrelation,
                                idempotencyKey, mapped);
                String outcome = done.outcome().name().toLowerCase(java.util.Locale.ROOT);
                jdbc.sql("UPDATE package_trigger_record SET status = :s, run_id = :run, message_id = :m WHERE id = :id")
                        .param("s", outcome).param("run", done.runId()).param("m", done.messageId()).param("id", recordId)
                        .update();
                counter(trigger.id(), "records_handled");
                return new Outcome(outcome, null, done.runId());
            });
        } catch (KafkaDispatch.Refused e) {
            return dead(trigger, key, sha, e.code());
        } catch (KafkaDispatch.Retry e) {
            return new Outcome("retry", e.code(), null);
        }
    }

    private Outcome dead(Trigger trigger, String key, String sha, String code) {
        String error = code == null || !code.matches("^[a-z_]{1,40}$") ? "refused" : code;
        record(trigger, key, sha, error);
        return new Outcome("dead", error, null);
    }

    private void record(Trigger trigger, String key, String sha, String error) {
        transactions.executeWithoutResult(status -> {
            int inserted = jdbc.sql("""
                    INSERT INTO package_trigger_record (id, workspace_id, trigger_id, dedupe_key, status, error_code,
                                                        value_sha256, received_at)
                    VALUES (:id, :ws, :t, :key, 'dead', :error, :sha, :now) ON CONFLICT (trigger_id, dedupe_key) DO NOTHING
                    """).param("id", Ids.newId()).param("ws", trigger.workspaceId()).param("t", trigger.id())
                    .param("key", key.length() > 300 ? key.substring(0, 300) : key).param("error", error).param("sha", sha)
                    .param("now", Sql.ts(clock.instant())).update();
            if (inserted == 1) {
                counter(trigger.id(), "dead_records");
                audit.write(new AuditWriter.Entry(trigger.workspaceId(), AuditWriter.ActorType.SYSTEM, null,
                        "connector.trigger-record-dead", "packageTrigger", trigger.id().toString(),
                        AuditWriter.Outcome.FAILED, Map.of("triggerId", trigger.id().toString(), "error", error)));
            }
        });
    }

    private void counter(UUID id, String column) {
        jdbc.sql("UPDATE package_trigger SET " + column + " = " + column + " + 1 WHERE id = :id").param("id", id).update();
    }

    // ------------------------------------------------------------------------------------------------- reading

    static final String SELECT = """
            SELECT id, workspace_id, package_id, trigger_key, kind, definition::text AS definition, action, workflow_id,
                   message_name, secret_ref, jwt_issuer, jwt_audience, connection_key, run_as, enabled, version
            FROM package_trigger
            """;

    Trigger trigger(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Trigger(Sql.uuid(rs, "id"), Sql.uuid(rs, "workspace_id"), rs.getString("package_id"),
                rs.getString("trigger_key"), rs.getString("kind"), json.parse(rs.getString("definition")),
                rs.getString("action"), rs.getObject("workflow_id", UUID.class), rs.getString("message_name"),
                rs.getString("secret_ref"), rs.getString("jwt_issuer"), rs.getString("jwt_audience"),
                rs.getString("connection_key"), Sql.uuid(rs, "run_as"), rs.getBoolean("enabled"), rs.getInt("version"));
    }

    private List<Trigger> triggers(UUID ws, UUID id) {
        var statement = jdbc.sql(SELECT + " WHERE workspace_id = :ws" + (id == null ? "" : " AND id = :id")
                + " ORDER BY package_id, trigger_key").param("ws", ws);
        if (id != null) {
            statement = statement.param("id", id);
        }
        return statement.query((rs, n) -> trigger(rs)).list();
    }

    Optional<Trigger> byId(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query((rs, n) -> trigger(rs)).optional();
    }

    private TriggerView view(Trigger t) {
        JsonNode d = t.definition();
        record Row(String version, TriggerStatus status) {
        }
        Row row = jdbc.sql("""
                SELECT v.version, t.watermark, t.next_run_at, t.last_run_at, t.last_success_at, t.last_error_code,
                       t.last_lag_seconds, t.polls, t.records_handled, t.duplicates, t.dead_records,
                       (t.lease_until IS NOT NULL AND t.lease_until > now()) AS leased
                FROM package_trigger t JOIN connector_package_version v ON v.id = t.package_version_id WHERE t.id = :id
                """).param("id", t.id()).query((rs, n) -> new Row(rs.getString("version"), new TriggerStatus(
                        rs.getString("watermark"), Sql.instant(rs, "next_run_at"), Sql.instant(rs, "last_run_at"),
                        Sql.instant(rs, "last_success_at"), rs.getString("last_error_code"),
                        (Long) rs.getObject("last_lag_seconds"), rs.getLong("polls"), rs.getLong("records_handled"),
                        rs.getLong("duplicates"), rs.getLong("dead_records"), rs.getBoolean("leased")))).single();
        String scheme = d.path("verification").path("scheme").asString(null);
        return new TriggerView(t.id(), t.packageId(), row.version(), t.key(), d.path("label").asString(), t.kind(),
                t.action(), t.workflowId(), t.messageName(), scheme, "basic".equals(scheme), t.secretRef(), t.jwtIssuer(),
                t.jwtAudience(), t.connectionKey(), d.path("every").asString(null), t.enabled(), t.version(),
                "webhook".equals(t.kind()) ? "/api/v1/workspaces/" + t.workspaceId() + "/package-triggers/" + t.id()
                        + "/events" : null, row.status());
    }
}
