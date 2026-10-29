package com.orqix.apps;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.audit.AuditWriter;
import com.orqix.cases.CaseService;
import com.orqix.catalog.ApplicationPages;
import com.orqix.catalog.ApplicationViewQueries;
import com.orqix.identity.Caller;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.runtime.PageActions;
import com.orqix.shared.idempotency.IdempotencyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs one chain of a routed, pinned page (S111). The chain comes from the immutable page version, never from the
 * request. Steps run in order, each in its own transaction through the same service as its public operation (so
 * membership, permissions, eligibility, versions and idempotency are checked again), and the chain stops at the first
 * failed step. Earlier successful steps are not rolled back; the result says exactly which steps ran.
 */
@Service
public class ChainRunner {
    private static final Logger LOG = LoggerFactory.getLogger(ChainRunner.class);

    public record Input(JsonNode values, Integer expectedVersion, String reason, String comment) { }
    public record Request(Integer expectedRevision, String context, String source, Map<String, Input> inputs) { }
    public record Step(int index, String action, String component, String status, String detail, String problemCode,
            UUID runId) { }
    public record Result(boolean completed, String navigateTo, List<Step> steps) { }

    private final JdbcClient jdbc;
    private final PolicyService policy;
    private final AppPages pages;
    private final PageActions actions;
    private final CaseService cases;
    private final IdempotencyService idempotency;
    private final Json json;
    private final AuditWriter audit;
    private final TransactionTemplate tx;

    public ChainRunner(JdbcClient jdbc, PolicyService policy, AppPages pages, PageActions actions, CaseService cases,
            IdempotencyService idempotency, Json json, AuditWriter audit, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.pages = pages;
        this.actions = actions;
        this.cases = cases;
        this.idempotency = idempotency;
        this.json = json;
        this.audit = audit;
        this.tx = tx;
    }

    public Result run(Caller caller, UUID ws, String slug, String chainId, String idempotencyKey, Request request) {
        WorkspaceAccess access = policy.requireMember(caller, ws);
        String key = IdempotencyService.requireKey(idempotencyKey);
        if (key.length() > 120) throw ApiException.malformed("Idempotency-Key is too long for a chain.");
        if (request == null || request.expectedRevision() == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Supply the page revision you loaded.");
        }
        AppPages.Routed routed = pages.routed(ws, slug);
        if (routed.revision() != request.expectedRevision()) {
            throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "The page changed. Reload it before running an action.")
                    .with("currentRevision", routed.revision());
        }
        ApplicationPages.Page page = routed.page();
        JsonNode chain = page.chains().get(chainId);
        if (chain == null) throw ApiException.notFound();
        placed(page, chainId, request.source());
        String context = request.context();
        if (!page.context().equals("none") && needsContext(chain)
                && (context == null || !AppPages.CONTEXT.matcher(context).matches())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Open the page for a " + page.context() + " before running this action.");
        }
        Map<String, Input> inputs = request.inputs() == null ? Map.of() : request.inputs();
        if (inputs.size() > 10 || !page.components().keySet().containsAll(inputs.keySet())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Inputs must name components of this page.");
        }
        String hash = idempotency.requestHash("runAppPageChain", Map.of("workspaceId", ws.toString(), "slug", slug,
                "chainId", chainId), request);
        Result previous = begin(ws, caller.subjectId(), key, hash, slug, chainId);
        if (previous != null) return previous;

        List<Step> steps = new ArrayList<>();
        String navigateTo = null;
        Integer taskVersion = null;
        boolean failed = false;
        int index = 0;
        for (JsonNode step : chain.path("steps")) {
            String action = step.path("action").asString();
            String component = step.has("component") ? step.path("component").asString() : null;
            if (failed) {
                steps.add(new Step(index++, action, component, "skipped", null, null, null));
                continue;
            }
            String stepKey = key + ":" + index;
            Input input = component == null ? null : inputs.get(component);
            try {
                UUID runId = null;
                switch (action) {
                    case "navigate" -> navigateTo = step.path("target").asString();
                    case "submit" -> {
                        ApplicationViewQueries.Pinned view = page.views().get(component);
                        UUID workflow = UUID.fromString(page.components().get(component).path("workflowId").asString());
                        runId = actions.start(caller, ws, stepKey, workflow, bound(view, "request.", values(input)));
                        UUID pageId = page.id();
                        tx.executeWithoutResult(s -> jdbc.sql("""
                                DELETE FROM application_page_draft
                                WHERE workspace_id = :ws AND view_id = :page AND component_id = :component AND subject_id = :me
                                """).param("ws", ws).param("page", pageId).param("component", component)
                                .param("me", caller.subjectId()).update());
                    }
                    case "saveDraft" -> saveDraft(caller, access, ws, slug, page, component, input);
                    case "claim" -> taskVersion = actions.claim(caller, ws, context, version(input, null));
                    case "approve", "reject" -> actions.decide(caller, ws, context, stepKey, version(input, taskVersion),
                            action, input == null ? null : input.reason(), input == null ? null : input.comment());
                    case "saveCaseData" -> {
                        ApplicationViewQueries.Pinned view = page.views().get(component);
                        cases.updateData(caller, ws, AppPages.uuid(context), stepKey, new CaseService.UpdateData(
                                version(input, null), input == null ? null : input.reason(), bound(view, "case.", values(input))));
                    }
                    default -> throw new ApiException(ProblemCode.VALIDATION_FAILED, "The step is not in the action allowlist.");
                }
                steps.add(new Step(index++, action, component, "succeeded", null, null, runId));
            } catch (ApiException e) {
                failed = true;
                navigateTo = null;
                steps.add(new Step(index++, action, component, "failed",
                        e.detail() == null ? e.code().title() : e.detail(), e.code().name(), null));
            } catch (RuntimeException e) {
                LOG.warn("Page chain step {} of {}/{} failed unexpectedly", index, slug, chainId, e);
                failed = true;
                navigateTo = null;
                steps.add(new Step(index++, action, component, "failed", "The step could not be completed.", "INTERNAL_ERROR", null));
            }
        }
        Result result = new Result(!failed, navigateTo, List.copyOf(steps));
        int done = (int) steps.stream().filter(s -> s.status().equals("succeeded")).count();
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                    UPDATE application_page_chain_run SET result = CAST(:result AS jsonb), completed_at = now()
                    WHERE workspace_id = :ws AND subject_id = :me AND idem_key = :key
                    """).param("result", json.write(result)).param("ws", ws).param("me", caller.subjectId())
                    .param("key", key).update();
            audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "page.chain.run",
                    "applicationPage", page.id().toString(), result.completed() ? AuditWriter.Outcome.SUCCESS
                            : AuditWriter.Outcome.FAILED,
                    Map.of("slug", slug, "chainId", chainId, "version", page.version(), "completed", result.completed(),
                            "steps", done)));
        });
        return result;
    }

    /** A chain runs only where the pinned page places it: an actions component, or the extension it is granted to. */
    private static void placed(ApplicationPages.Page page, String chainId, String source) {
        if (source == null) {
            for (JsonNode component : page.components().values()) {
                if ("actions".equals(component.path("type").asString()) && contains(component.path("chains"), chainId)) return;
            }
            throw new ApiException(ProblemCode.FORBIDDEN, "This chain is not placed on the page.");
        }
        JsonNode component = page.components().get(source);
        ApplicationPages.Extension extension = page.extensions().get(source);
        if (component == null || !"extension".equals(component.path("type").asString())
                || !contains(component.path("chains"), chainId)) {
            throw new ApiException(ProblemCode.FORBIDDEN, "The extension component is not granted this chain.");
        }
        if (extension == null || !extension.status().equals("approved")) {
            throw new ApiException(ProblemCode.FORBIDDEN, "This extension version is no longer approved.");
        }
    }

    private void saveDraft(Caller caller, WorkspaceAccess access, UUID ws, String slug, ApplicationPages.Page page,
            String component, Input input) {
        UUID workflow = UUID.fromString(page.components().get(component).path("workflowId").asString());
        if (!access.canStart(workflow)) throw ApiException.forbidden();
        JsonNode values = bound(page.views().get(component), "request.", values(input));
        String text = json.write(values);
        if (text.length() > 32_768) throw new ApiException(ProblemCode.VALIDATION_FAILED, "The draft is too large.");
        UUID pageId = page.id();
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                    INSERT INTO application_page_draft (workspace_id, view_id, component_id, subject_id, draft_values)
                    VALUES (:ws, :page, :component, :me, CAST(:values AS jsonb))
                    ON CONFLICT (workspace_id, view_id, component_id, subject_id)
                    DO UPDATE SET draft_values = EXCLUDED.draft_values, saved_at = now()
                    """).param("ws", ws).param("page", pageId).param("component", component).param("me", caller.subjectId())
                    .param("values", text).update();
            audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "page.draft.saved",
                    "applicationPage", pageId.toString(), AuditWriter.Outcome.SUCCESS,
                    Map.of("slug", slug, "componentId", component)));
        });
    }

    /** Records the request, or returns the recorded result of an identical earlier request with the same key. */
    private Result begin(UUID ws, UUID me, String key, String hash, String slug, String chainId) {
        return tx.execute(s -> {
            int inserted = jdbc.sql("""
                    INSERT INTO application_page_chain_run (workspace_id, subject_id, idem_key, request_hash, slug, chain_id)
                    VALUES (:ws, :me, :key, :hash, :slug, :chain) ON CONFLICT DO NOTHING
                    """).param("ws", ws).param("me", me).param("key", key).param("hash", hash).param("slug", slug)
                    .param("chain", chainId).update();
            if (inserted == 1) return null;
            var existing = jdbc.sql("""
                    SELECT request_hash, result::text FROM application_page_chain_run
                    WHERE workspace_id = :ws AND subject_id = :me AND idem_key = :key
                    """).param("ws", ws).param("me", me).param("key", key)
                    .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2) == null ? "" : rs.getString(2))).single();
            if (!existing.getKey().equals(hash)) throw new ApiException(ProblemCode.IDEMPOTENCY_KEY_REUSED);
            if (existing.getValue().isEmpty()) {
                throw new ApiException(ProblemCode.RUN_BUSY, "This action is still running. Reload the page to see its outcome.");
            }
            return json.convert(json.parse(existing.getValue()), Result.class);
        });
    }

    /** Only the fields the pinned view binds, so a page never submits data its view does not show. */
    private ObjectNode bound(ApplicationViewQueries.Pinned view, String prefix, JsonNode values) {
        Set<String> allowed = new HashSet<>();
        for (JsonNode section : view.definition().path("sections")) {
            for (JsonNode field : section.path("fields")) {
                String binding = field.path("binding").asString();
                if (binding.startsWith(prefix)) allowed.add(binding.substring(prefix.length()));
            }
        }
        ObjectNode result = json.object();
        Map<String, JsonNode> unknown = new HashMap<>();
        for (String name : values.propertyNames()) {
            if (allowed.contains(name)) result.set(name, values.get(name));
            else unknown.put(name, values.get(name));
        }
        if (!unknown.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Values must belong to fields of the pinned view.");
        }
        return result;
    }

    private static JsonNode values(Input input) {
        if (input == null || input.values() == null || !input.values().isObject()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Supply the form values for this step.");
        }
        return input.values();
    }

    private static int version(Input input, Integer carried) {
        if (carried != null) return carried;
        if (input == null || input.expectedVersion() == null || input.expectedVersion() < 1) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Supply the version you loaded.");
        }
        return input.expectedVersion();
    }

    private static boolean needsContext(JsonNode chain) {
        for (JsonNode step : chain.path("steps")) {
            if (Set.of("claim", "approve", "reject", "saveCaseData").contains(step.path("action").asString())) return true;
        }
        return false;
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) if (value.equals(item.asString())) return true;
        return false;
    }
}
