package com.orqix.runtime.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.audit.AuditWriter;
import com.orqix.catalog.ReleaseQueries;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.runtime.api.GuestDtos;
import com.orqix.runtime.engine.EngineCommands;
import com.orqix.shared.Ids;
import com.orqix.shared.config.AttachmentSettings;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Guest response forms (CG-F05, docs/build/S140). A row is created when a request reaches a "Wait for a message" step
 * whose release declares {@code config.guestResponse}. A link token ({@code ogf_} + 256 random bits, stored as SHA-256)
 * is issued by an operator with {@code run.operate} (shown once) or minted by {@link GuestLinkSender} and emailed to the
 * request's address field when its domain is approved at that moment (D-CG7). The guest opens a minimal page that sends
 * the token in {@value #TOKEN_HEADER}; it sees only the step's title, instructions and questions, may add files, and
 * submits once. Submitting attaches the files to the request and resumes the wait through {@link MessageDelivery}, the
 * path every message takes. Lock order: guest row, then request, then subscription.
 */
@Service
public class GuestResponseService {

    public static final String TOKEN_HEADER = "X-Orqix-Guest-Token";
    static final String PREFIX = "ogf_";
    static final int MAX_LIVE_TOKENS_PER_WORKSPACE = 10_000;
    static final int MAX_BODY_BYTES = 32 * 1024;
    static final int REQUESTS_PER_MINUTE = 60;
    static final int UPLOADS_PER_MINUTE = 10;
    static final int MAX_DOMAINS = 50;
    private static final Pattern TOKEN = Pattern.compile("^ogf_[A-Za-z0-9_-]{43}$");
    private static final Pattern DOMAIN = Pattern.compile(
            "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$");
    private static final Set<String> ACTIVE = Set.of("running", "waiting", "needsAttention");
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Submit(GuestDtos.GuestSubmitted body, boolean replayed) {
    }

    private final PolicyService policy;
    private final GuestResponseStore store;
    private final MessageStore messages;
    private final MessageDelivery delivery;
    private final MessageSettings settings;
    private final RunSuspensions suspensions;
    private final ReleaseQueries releases;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final RunEvents events;
    private final RunAttachments attachments;
    private final AttachmentScanner scanner;
    private final AttachmentSettings attachmentSettings;
    private final Json json;
    private final Clock clock;
    private final String origin;
    private final Map<String, Deque<Long>> recent = new HashMap<>();

    public GuestResponseService(PolicyService policy, GuestResponseStore store, MessageStore messages,
            @Lazy MessageDelivery delivery, MessageSettings settings, RunSuspensions suspensions,
            ReleaseQueries releases, IdempotencyService idempotency, AuditWriter audit, RunEvents events,
            @Lazy RunAttachments attachments, AttachmentScanner scanner, AttachmentSettings attachmentSettings,
            Json json, Clock clock, @Value("${orqix.mail.application-origin:http://localhost:8080}") String origin) {
        this.policy = policy;
        this.store = store;
        this.messages = messages;
        this.delivery = delivery;
        this.settings = settings;
        this.suspensions = suspensions;
        this.releases = releases;
        this.idempotency = idempotency;
        this.audit = audit;
        this.events = events;
        this.attachments = attachments;
        this.scanner = scanner;
        this.attachmentSettings = attachmentSettings;
        this.json = json;
        this.clock = clock;
        this.origin = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
    }

    // ---------------------------------------------------------------- the wait starts (engine transaction)

    /** Called by {@link MessageWaits#started}; does nothing for a wait without a guest form. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void waitStarted(RunStore.RunRow run, String nodeId, UUID subscriptionId) {
        JsonNode node = releases.require(run.workspaceId(), run.releaseId()).node(nodeId);
        JsonNode guest = node == null ? null : node.path("config").get("guestResponse");
        if (guest == null || !guest.isObject()) {
            return;
        }
        Duration validFor = duration(guest.path("linkValidFor").asString(null));
        if (validFor == null) {
            return; // the compiler admits only valid durations
        }
        store.insert(Ids.newId(), run.workspaceId(), run.id(), nodeId, subscriptionId, validFor,
                guest.has("recipientFrom") ? "pending" : "manual", clock.instant());
    }

    // ---------------------------------------------------------------- operators

    @Transactional(readOnly = true)
    public GuestDtos.GuestLinkList list(Caller caller, UUID workspaceId, UUID runId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.RUN_OPERATE, "listGuestLinks", false);
        Instant now = clock.instant();
        List<GuestDtos.GuestLink> items = new ArrayList<>();
        for (GuestResponseStore.Row row : store.list(workspaceId, runId, 200)) {
            items.add(view(row, now));
        }
        return new GuestDtos.GuestLinkList(items);
    }

    @Transactional
    public GuestDtos.GuestLinkIssued issue(Caller caller, UUID workspaceId, UUID linkId) {
        WorkspaceAccess access = fresh(caller, workspaceId, "issueGuestLink");
        GuestResponseStore.Row row = store.lock(workspaceId, linkId).orElseThrow(ApiException::notFound);
        Instant now = clock.instant();
        requireIssuable(row, now);
        String token = newToken();
        Instant expires = now.plus(row.validFor());
        if (row.tokenSha256() != null) {
            audit(workspaceId, caller, "guest.link-revoked", row,
                    Map.of("linkId", row.id().toString(), "runId", row.runId().toString(), "replaced", true));
        }
        store.issue(row.id(), Json.sha256Hex(token), now, expires, caller.subjectId(),
                "pending".equals(row.sendState()) ? "manual" : row.sendState(), row.sentDomain());
        audit(workspaceId, caller, "guest.link-issued", row, Map.of("linkId", row.id().toString(), "runId",
                row.runId().toString(), "nodeId", row.nodeId(), "validForSeconds", row.validFor().toSeconds(),
                "channel", "operator"));
        return new GuestDtos.GuestLinkIssued(row.id(), url(workspaceId, token), expires);
    }

    /** Asks for the link to be emailed (again) to the request's address; the domain is checked when it is sent. */
    @Transactional
    public GuestDtos.GuestLink send(Caller caller, UUID workspaceId, UUID linkId) {
        fresh(caller, workspaceId, "sendGuestLink");
        GuestResponseStore.Row row = store.lock(workspaceId, linkId).orElseThrow(ApiException::notFound);
        Instant now = clock.instant();
        requireIssuable(row, now);
        JsonNode guest = guestConfig(row);
        if (guest == null || !guest.has("recipientFrom")) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "This step has no address to send the link to;"
                    + " issue the link and share it yourself.");
        }
        store.sendState(row.id(), "pending", null, false);
        audit(workspaceId, caller, "guest.link-send-requested", row,
                Map.of("linkId", row.id().toString(), "runId", row.runId().toString()));
        return view(store.lock(workspaceId, linkId).orElseThrow(), now);
    }

    @Transactional
    public void revoke(Caller caller, UUID workspaceId, UUID linkId) {
        fresh(caller, workspaceId, "revokeGuestLink");
        GuestResponseStore.Row row = store.lock(workspaceId, linkId).orElseThrow(ApiException::notFound);
        if (row.submittedAt() != null) {
            throw new ApiException(ProblemCode.GUEST_RESPONSE_SUBMITTED,
                    "The guest already answered; the link only shows that the answer was sent.");
        }
        if (row.tokenSha256() == null) {
            return;
        }
        store.revoke(row.id(), clock.instant());
        audit(workspaceId, caller, "guest.link-revoked", row,
                Map.of("linkId", row.id().toString(), "runId", row.runId().toString(), "replaced", false));
    }

    // ---------------------------------------------------------------- approved domains (D-CG7)

    @Transactional(readOnly = true)
    public GuestDtos.GuestDomains domains(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.CONNECTION_READ, "listGuestDomains", false);
        return new GuestDtos.GuestDomains(store.domains(workspaceId));
    }

    @Transactional
    public GuestDtos.GuestDomains replaceDomains(Caller caller, UUID workspaceId, GuestDtos.GuestDomains request) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.CONNECTION_MANAGE, "replaceGuestDomains", true);
        List<String> given = request == null || request.domains() == null ? List.of() : request.domains();
        if (given.size() > MAX_DOMAINS) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "At most " + MAX_DOMAINS + " domains.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/domains", "maxItems",
                            "List at most " + MAX_DOMAINS + " domains.")));
        }
        Set<String> domains = new LinkedHashSet<>();
        List<ApiException.FieldError> errors = new ArrayList<>();
        for (int i = 0; i < given.size(); i++) {
            String d = given.get(i) == null ? "" : given.get(i).strip().toLowerCase(Locale.ROOT);
            if (d.length() > 253 || !DOMAIN.matcher(d).matches()) {
                errors.add(new ApiException.FieldError("/domains/" + i, "pattern",
                        "Enter a domain such as supplier.example, without @ or a path."));
            } else {
                domains.add(d);
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Some domains are not valid.").withFieldErrors(errors);
        }
        List<String> sorted = domains.stream().sorted().toList();
        store.replaceDomains(workspaceId, sorted, caller.subjectId(), clock.instant());
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "guest.domains.updated", "workspace", workspaceId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("count", sorted.size())));
        return new GuestDtos.GuestDomains(sorted);
    }

    // ---------------------------------------------------------------- the guest

    @Transactional
    public GuestDtos.GuestForm form(UUID workspaceId, String token) {
        GuestResponseStore.Row row = resolve(workspaceId, token, false);
        Instant now = clock.instant();
        if (store.viewed(row.id(), now)) {
            audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.SYSTEM, null, "guest.link-viewed",
                    "guestLink", row.id().toString(), AuditWriter.Outcome.SUCCESS,
                    Map.of("linkId", row.id().toString(), "runId", row.runId().toString())));
        }
        JsonNode guest = guestConfig(row);
        String state = guestState(row, now);
        List<GuestDtos.GuestFile> uploaded = new ArrayList<>();
        if ("open".equals(state)) {
            for (GuestResponseStore.File f : store.files(row.id(), row.tokenSha256())) {
                uploaded.add(new GuestDtos.GuestFile(f.id(), f.name(), f.contentType(), f.sizeBytes()));
            }
        }
        return new GuestDtos.GuestForm(guest == null ? "" : guest.path("title").asString(""),
                guest == null ? null : guest.path("instructions").asString(null), state, row.tokenExpiresAt(),
                questions(row), fileRules(guest), uploaded);
    }

    @Transactional
    public GuestDtos.GuestFile upload(UUID workspaceId, String token, RunAttachments.Upload upload) {
        GuestResponseStore.Row row = resolve(workspaceId, token, true);
        Instant now = clock.instant();
        requireOpen(row, now);
        GuestDtos.GuestFileRules rules = fileRules(guestConfig(row));
        if (rules == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "This form does not take files.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/file", "type", "This form does not take files.")));
        }
        if (store.files(row.id(), row.tokenSha256()).size() >= rules.maxFiles()) {
            throw new ApiException(ProblemCode.ATTACHMENT_LIMIT_REACHED, "You can add at most " + rules.maxFiles()
                    + " file" + (rules.maxFiles() == 1 ? "" : "s") + ".");
        }
        if (store.recentUploads(row.id(), now.minusSeconds(60)) >= UPLOADS_PER_MINUTE) {
            throw new ApiException(ProblemCode.RATE_LIMITED, "Too many uploads. Try again in a minute.")
                    .withHeader("Retry-After", "30");
        }
        if (upload == null || upload.stream() == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose a file.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/file", "required", "Choose a file.")));
        }
        long limit = Math.min(rules.maxFileBytes(), attachmentSettings.maxFileBytes());
        byte[] bytes;
        try (var in = upload.stream()) {
            bytes = in.readNBytes((int) limit + 1);
        } catch (IOException e) {
            throw ApiException.malformed("The file could not be read.");
        }
        if (bytes.length > limit) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "The file is larger than " + limit + " bytes.");
        }
        if (bytes.length == 0) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The file is empty.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/file", "required", "The file is empty.")));
        }
        String name = AttachmentTypes.sanitizeName(upload.fileName());
        AttachmentTypes.Detection detection = AttachmentTypes.detect(name, upload.contentType(), bytes);
        if (!detection.accepted() || (!rules.acceptTypes().isEmpty()
                && !rules.acceptTypes().contains(detection.type().key()))) {
            String message = detection.accepted() ? "This form accepts only " + String.join(", ", rules.acceptTypes())
                    + " files." : detection.refusal().message();
            throw new ApiException(ProblemCode.VALIDATION_FAILED, message)
                    .withFieldErrors(List.of(new ApiException.FieldError("/file", "type", message)));
        }
        AttachmentScanner.Status scan = scanner.scan(bytes);
        if (scan.blocksDownload()) {
            throw new ApiException(ProblemCode.ATTACHMENT_BLOCKED, "The file did not pass the file check.");
        }
        UUID id = Ids.newId();
        store.insertFile(id, row.id(), row.tokenSha256(), name, detection.type().contentType(), bytes, sha256(bytes),
                scan.value(), now);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.SYSTEM, null, "guest.file-added",
                "guestLink", row.id().toString(), AuditWriter.Outcome.SUCCESS, Map.of("linkId", row.id().toString(),
                        "runId", row.runId().toString(), "sizeBytes", bytes.length, "contentType",
                        detection.type().contentType(), "scanStatus", scan.value())));
        return new GuestDtos.GuestFile(id, name, detection.type().contentType(), bytes.length);
    }

    @Transactional
    public Submit submit(UUID workspaceId, String token, String idempotencyKey, JsonNode body) {
        String key = IdempotencyService.requireKey(idempotencyKey);
        GuestResponseStore.Row row = resolve(workspaceId, token, true);
        JsonNode result = body == null ? null : body.get("result");
        if (result == null || !result.isObject()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The body must be {\"result\": { ... }}.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/result", "type", "Send an object of answers.")));
        }
        List<UUID> fileIds = new ArrayList<>();
        JsonNode files = body.path("files");
        if (!files.isMissingNode() && !files.isNull()) {
            if (!files.isArray() || files.size() > 5) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "files must be a list of at most 5 file ids.")
                        .withFieldErrors(List.of(new ApiException.FieldError("/files", "type", "Send at most 5 file ids.")));
            }
            for (JsonNode f : files) {
                try {
                    fileIds.add(UUID.fromString(f.asString("")));
                } catch (IllegalArgumentException e) {
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "Unknown file.").withFieldErrors(
                            List.of(new ApiException.FieldError("/files", "enum", "Unknown file.")));
                }
            }
        }
        String hash = idempotency.requestHash("submitGuestResponse", Map.of(), body);
        if (row.submittedAt() != null) {
            if (key.equals(row.submitIdemKey()) && hash.equals(row.submitRequestHash())) {
                return new Submit(json.mapper().treeToValue(row.submitResult(), GuestDtos.GuestSubmitted.class), true);
            }
            throw new ApiException(ProblemCode.GUEST_RESPONSE_SUBMITTED,
                    "This form was already sent; it can be sent only once.");
        }
        Instant now = clock.instant();
        requireOpen(row, now);
        if (suspensions.isSuspended(workspaceId, row.runId())) {
            throw new ApiException(ProblemCode.RUN_SUSPENDED,
                    "The request is paused for now. Send the form again later.");
        }
        byte[] bytes = json.write(result).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Math.min(settings.maxPayloadBytes(), MAX_BODY_BYTES)) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "The answers are too long.");
        }
        List<ApiException.FieldError> shape = MessagePayloads.shape(result);
        if (!shape.isEmpty() || Json.depth(result) > 10) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Some answers are not valid.").withFieldErrors(
                    shape.isEmpty() ? List.of(new ApiException.FieldError("/result", "depth", "Nested too deeply."))
                            : asResult(shape));
        }
        GuestDtos.GuestFileRules rules = fileRules(guestConfig(row));
        Map<UUID, GuestResponseStore.File> staged = new LinkedHashMap<>();
        store.files(row.id(), row.tokenSha256()).forEach(f -> staged.put(f.id(), f));
        if (!fileIds.isEmpty() && rules == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "This form does not take files.").withFieldErrors(
                    List.of(new ApiException.FieldError("/files", "maxItems", "This form does not take files.")));
        }
        for (UUID id : fileIds) {
            if (!staged.containsKey(id)) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Unknown file.").withFieldErrors(
                        List.of(new ApiException.FieldError("/files", "enum", "A file is not one you added here.")));
            }
        }
        if (rules != null && rules.required() && fileIds.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Add a file.").withFieldErrors(
                    List.of(new ApiException.FieldError("/files", "required", "Add at least one file.")));
        }
        MessageStore.Subscription subscription = messages.find(workspaceId, row.subscriptionId())
                .orElseThrow(() -> new ApiException(ProblemCode.GUEST_RESPONSE_CLOSED, "This form is closed."));
        // Files first, so the steps after the wait already see them on the request.
        if (!fileIds.isEmpty()) {
            store.ensureGuestSubject();
        }
        for (UUID id : new LinkedHashSet<>(fileIds)) {
            GuestResponseStore.Content content = store.content(row.id(), id).orElseThrow();
            attachments.addGuestFile(workspaceId, row.runId(), GuestResponseStore.GUEST_SUBJECT, content.file().name(),
                    content.file().contentType(), content.bytes(), row.nodeId());
        }
        UUID messageId = Ids.newId();
        messages.insertInbound(messageId, workspaceId, subscription.messageName(), subscription.correlationKey(),
                result, null, now, now.plus(settings.bufferTtl()));
        MessageDelivery.Outcome outcome;
        try {
            outcome = delivery.deliver(subscription, messageId, result, false, null);
        } catch (EngineCommands.EngineConflictException e) {
            throw new ApiException(ProblemCode.RUN_BUSY, "Busy for a moment; send the form again.")
                    .withHeader("Retry-After", "2");
        }
        switch (outcome.status()) {
            case INVALID -> throw new ApiException(ProblemCode.VALIDATION_FAILED, "Some answers are not valid.")
                    .withFieldErrors(asResult(outcome.errors()));
            case SKIPPED -> throw new ApiException(ProblemCode.GUEST_RESPONSE_CLOSED, "This form is closed.");
            case DELIVERED -> {
            }
        }
        messages.closeInbound(workspaceId, messageId, MessageStore.DELIVERED, 1, now);
        int fileCount = new LinkedHashSet<>(fileIds).size();
        GuestDtos.GuestSubmitted answer = new GuestDtos.GuestSubmitted("submitted", now, fileCount);
        store.deleteFiles(row.id());
        store.submitted(row.id(), now, key, hash, json.toTree(answer));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("linkId", row.id().toString());
        metadata.put("runId", row.runId().toString());
        metadata.put("fieldCount", outcome.fields().size());
        metadata.put("fileCount", fileCount);
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.SYSTEM, null, "guest.submitted",
                "guestLink", row.id().toString(), AuditWriter.Outcome.SUCCESS, metadata));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileCount", fileCount);
        events.append(workspaceId, row.runId(), "GUEST_RESPONSE_SUBMITTED", row.nodeId(), null, data);
        return new Submit(answer, false);
    }

    // ---------------------------------------------------------------- sending (GuestLinkSender)

    /** A link the sender minted: the clear token is used once, in the email. */
    record Minted(String token, Instant expiresAt, String url) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Minted mintForEmail(GuestResponseStore.Row row, String domain) {
        Instant now = clock.instant();
        String token = newToken();
        Instant expires = now.plus(row.validFor());
        store.issue(row.id(), Json.sha256Hex(token), now, expires, null, "sent", domain);
        return new Minted(token, expires, url(row.workspaceId(), token));
    }

    // ---------------------------------------------------------------- helpers

    /** Unknown, malformed, replaced, revoked or purged token, or a token of another workspace: 404, all alike. */
    private GuestResponseStore.Row resolve(UUID workspaceId, String token, boolean lock) {
        if (token == null || !TOKEN.matcher(token.strip()).matches() || workspaceId == null) {
            throw ApiException.notFound();
        }
        String sha = Json.sha256Hex(token.strip());
        GuestResponseStore.Row row = (lock ? store.lockByToken(sha) : store.findByToken(sha))
                .orElseThrow(ApiException::notFound);
        if (!workspaceId.equals(row.workspaceId())) {
            throw ApiException.notFound();
        }
        throttle(sha);
        return row;
    }

    /** {state, reason} for operators: waiting, submitted, expired, revoked or cancelled. */
    static String[] state(GuestResponseStore.Row row, Instant now) {
        if (row.submittedAt() != null) {
            return new String[] {"submitted", null};
        }
        boolean open = MessageStore.WAITING.equals(row.subscriptionStatus()) && ACTIVE.contains(row.lifecycle());
        if (!open) {
            String reason = switch (row.subscriptionStatus()) {
                case MessageStore.TIMED_OUT -> "timedOut";
                case MessageStore.RECEIVED -> "answeredElsewhere";
                default -> "requestEnded";
            };
            return new String[] {"cancelled", reason};
        }
        if (row.tokenSha256() == null && row.revokedAt() != null) {
            return new String[] {"revoked", null};
        }
        if (row.tokenExpiresAt() != null && !now.isBefore(row.tokenExpiresAt())) {
            return new String[] {"expired", null};
        }
        return new String[] {"waiting", null};
    }

    /** The guest's words: open, submitted, expired or closed. */
    private static String guestState(GuestResponseStore.Row row, Instant now) {
        return switch (state(row, now)[0]) {
            case "waiting" -> "open";
            case "submitted" -> "submitted";
            case "expired" -> "expired";
            default -> "closed";
        };
    }

    private static void requireIssuable(GuestResponseStore.Row row, Instant now) {
        String state = state(row, now)[0];
        if ("submitted".equals(state) || "cancelled".equals(state)) {
            throw new ApiException(ProblemCode.GUEST_RESPONSE_CLOSED,
                    "The wait is no longer open (" + state + "), so no link can be issued.");
        }
    }

    private static void requireOpen(GuestResponseStore.Row row, Instant now) {
        String state = state(row, now)[0];
        if ("submitted".equals(state)) {
            throw new ApiException(ProblemCode.GUEST_RESPONSE_SUBMITTED, "This form was already sent.");
        }
        if (!"waiting".equals(state)) {
            throw new ApiException(ProblemCode.GUEST_RESPONSE_CLOSED, "expired".equals(state)
                    ? "This link expired. Ask the person who sent it for a new one."
                    : "This form is closed.");
        }
    }

    private WorkspaceAccess fresh(Caller caller, UUID workspaceId, String operationId) {
        WorkspaceAccess access = policy.freshAccess(caller, workspaceId).filter(WorkspaceAccess::visible)
                .orElseThrow(ApiException::notFound);
        policy.require(access, Permission.RUN_OPERATE, operationId, true);
        return access;
    }

    private void audit(UUID workspaceId, Caller caller, String action, GuestResponseStore.Row row,
            Map<String, ?> metadata) {
        audit.write(new AuditWriter.Entry(workspaceId,
                caller.machine() ? AuditWriter.ActorType.SERVICE : AuditWriter.ActorType.USER, caller.subjectId(),
                action, "guestLink", row.id().toString(), AuditWriter.Outcome.SUCCESS, metadata));
    }

    JsonNode guestConfig(GuestResponseStore.Row row) {
        JsonNode node = releases.require(row.workspaceId(), row.releaseId()).node(row.nodeId());
        JsonNode guest = node == null ? null : node.path("config").get("guestResponse");
        return guest != null && guest.isObject() ? guest : null;
    }

    private List<GuestDtos.GuestQuestion> questions(GuestResponseStore.Row row) {
        JsonNode node = releases.require(row.workspaceId(), row.releaseId()).node(row.nodeId());
        List<GuestDtos.GuestQuestion> questions = new ArrayList<>();
        if (node != null) {
            for (JsonNode f : node.path("config").path("payload")) {
                String name = f.path("name").asString();
                questions.add(new GuestDtos.GuestQuestion(name, f.path("type").asString(),
                        f.path("title").asString(name), f.path("required").asBoolean(false)));
            }
        }
        return questions;
    }

    private GuestDtos.GuestFileRules fileRules(JsonNode guest) {
        JsonNode a = guest == null ? null : guest.get("attachments");
        if (a == null || !a.isObject()) {
            return null;
        }
        List<String> types = new ArrayList<>();
        a.path("acceptTypes").forEach(t -> types.add(t.asString()));
        long max = a.has("maxFileBytes") ? a.get("maxFileBytes").asLong() : attachmentSettings.maxFileBytes();
        return new GuestDtos.GuestFileRules(a.path("maxFiles").asInt(1), a.path("required").asBoolean(false),
                List.copyOf(types), Math.min(max, attachmentSettings.maxFileBytes()));
    }

    private GuestDtos.GuestLink view(GuestResponseStore.Row row, Instant now) {
        String[] state = state(row, now);
        JsonNode node = releases.require(row.workspaceId(), row.releaseId()).node(row.nodeId());
        JsonNode guest = node == null ? null : node.path("config").path("guestResponse");
        return new GuestDtos.GuestLink(row.id(), row.runId(), row.runReference(), row.nodeId(),
                node == null ? row.nodeId() : node.path("label").asString(row.nodeId()),
                guest == null ? null : guest.path("title").asString(null), state[0], state[1], row.sendState(),
                row.sentDomain(), row.tokenSha256() != null, row.tokenExpiresAt(), row.tokensIssued(),
                row.validFor().toString(), row.firstViewedAt(), row.submittedAt(), row.createdAt());
    }

    private String url(UUID workspaceId, String token) {
        // The token is in the fragment: browsers never send it to a server, so it stays out of access logs.
        return origin + "/guest/" + workspaceId + "#" + token;
    }

    /** A sliding minute of calls per link token on this pod (resolved tokens only, so guesses fill nothing). */
    private void throttle(String sha) {
        long now = clock.millis();
        synchronized (recent) {
            if (recent.size() > 50_000) {
                recent.entrySet().removeIf(e -> e.getValue().isEmpty() || now - e.getValue().peekLast() >= 60_000);
            }
            Deque<Long> times = recent.computeIfAbsent(sha, k -> new ArrayDeque<>());
            while (!times.isEmpty() && now - times.peekFirst() >= 60_000) {
                times.pollFirst();
            }
            if (times.size() >= REQUESTS_PER_MINUTE) {
                long wait = Math.max(1, (60_000 - (now - times.peekFirst()) + 999) / 1000);
                throw new ApiException(ProblemCode.RATE_LIMITED, "Too many attempts. Wait a minute and try again.")
                        .withHeader("Retry-After", String.valueOf(wait));
            }
            times.addLast(now);
        }
    }

    private static List<ApiException.FieldError> asResult(List<ApiException.FieldError> errors) {
        return errors.stream().map(e -> new ApiException.FieldError(
                e.pointer().startsWith("/payload") ? "/result" + e.pointer().substring("/payload".length()) : e.pointer(),
                e.code(), e.message())).toList();
    }

    static Duration duration(String text) {
        if (text == null) {
            return null;
        }
        try {
            Duration d = Duration.parse(text);
            return d.compareTo(Duration.ofHours(1)) < 0 || d.compareTo(Duration.ofDays(30)) > 0 ? null : d;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
