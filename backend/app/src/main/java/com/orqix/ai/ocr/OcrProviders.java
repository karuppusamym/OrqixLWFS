package com.orqix.ai.ocr;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.ai.provider.SigV4;
import com.orqix.ai.skills.AiSkills;
import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.integration.DocumentExtraction;
import com.orqix.integration.EgressPolicy;
import com.orqix.integration.ModelProviderSecrets;
import com.orqix.integration.OcrDocument;
import com.orqix.integration.OcrException;
import com.orqix.integration.OcrProvider;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * OCR provider selection for document extraction (CG-A06). The deployment declares the providers
 * ({@link OcrProperties}); a workspace administrator chooses one ({@code ai_ocr_setting}, default the deployment's
 * {@code default-provider}, itself {@code local} by default). Residency, decided conservatively: {@code local} is always
 * allowed; a cloud provider is allowed only when the workspace AI policy's residency rule names one of the provider's
 * residency tags, or, in a workspace that is not regulated and has no residency rule, when the administrator chose it
 * explicitly. A regulated workspace without a residency rule therefore never sends documents to a cloud service. The
 * rule is checked again before every analysis, so a policy change takes effect at once. Plain text files never leave
 * the deployment, whatever the choice.
 */
@Component
public class OcrProviders {

    /** The provider an analysis uses, or why none may be used. */
    public record Selection(OcrProvider provider, String id, String refusal) {
    }

    private final OcrProperties properties;
    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final PolicyService policy;
    private final AuditWriter audit;
    private final AiSkills skills;
    private final ModelProviderSecrets secrets;
    private final OcrHttp http;
    private final LocalOcr local;

    public OcrProviders(OcrProperties properties, JdbcClient jdbc, Json json, Clock clock, PolicyService policy,
            AuditWriter audit, AiSkills skills, ModelProviderSecrets secrets, EgressPolicy egress,
            DocumentExtraction pdf) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.policy = policy;
        this.audit = audit;
        this.skills = skills;
        this.secrets = secrets;
        this.http = new OcrHttp(json, egress);
        this.local = new LocalOcr(pdf, new TesseractOcr(properties.tesseract()));
    }

    /** The workspace's chosen provider id (the deployment default when none was chosen). */
    public String chosen(UUID workspaceId) {
        return jdbc.sql("SELECT provider FROM ai_ocr_setting WHERE workspace_id = :ws").param("ws", workspaceId)
                .query(String.class).optional().orElse(properties.defaultProvider());
    }

    /** @return null when allowed, otherwise the reason */
    String refusal(String id, AiSkills.Policy p, boolean explicitChoice) {
        if (OcrProperties.LOCAL.equals(id)) {
            return null;
        }
        OcrProperties.Provider provider = properties.providers().get(id);
        if (provider == null || !List.of("azureDocumentIntelligence", "textract").contains(provider.kind())) {
            return "The OCR provider " + id + " is not configured in this deployment.";
        }
        if (!p.residency().isEmpty()) {
            return provider.residency().stream().anyMatch(p.residency()::contains) ? null
                    : "The workspace residency rule (" + String.join(", ", p.residency()) + ") does not admit the OCR"
                            + " provider " + id + ".";
        }
        if (p.regulated()) {
            return "A regulated workspace sends documents to a cloud OCR service only under a residency rule that"
                    + " admits it.";
        }
        return explicitChoice ? null : "Cloud OCR is used only after a workspace administrator chooses it.";
    }

    /** The provider for one analysis of a file of {@code contentType}, after the residency check. */
    public Selection select(UUID workspaceId, String contentType) {
        if ("text/plain".equals(contentType) || "text/csv".equals(contentType)) {
            return new Selection(local, OcrProperties.LOCAL, null);
        }
        boolean explicit = jdbc.sql("SELECT count(*) FROM ai_ocr_setting WHERE workspace_id = :ws")
                .param("ws", workspaceId).query(Integer.class).single() > 0;
        String id = chosen(workspaceId);
        String refused = refusal(id, skills.policy(workspaceId), explicit);
        if (refused != null) {
            return new Selection(null, id, refused);
        }
        return new Selection(build(id, workspaceId), id, null);
    }

    /** Builds the adapter of a provider with its credentials resolved through the S132 secret providers. */
    OcrProvider build(String id, UUID workspaceId) {
        if (OcrProperties.LOCAL.equals(id)) {
            return local;
        }
        OcrProperties.Provider config = properties.providers().get(id);
        String secret = secrets.resolve(config.secretRef(), workspaceId).orElse("");
        return switch (config.kind()) {
            case "azureDocumentIntelligence" -> new AzureDocumentIntelligenceOcr(id, config, secret, http);
            case "textract" -> {
                SigV4.Credentials credentials;
                try {
                    JsonNode keys = json.parse(secret.isBlank() ? "{}" : secret);
                    credentials = new SigV4.Credentials(keys.path("accessKeyId").asString(""),
                            keys.path("secretAccessKey").asString(""), keys.path("sessionToken").asString(null));
                } catch (IllegalArgumentException e) {
                    throw new OcrException(OcrException.Kind.CREDENTIALS_REJECTED, 0, id + ": unreadable AWS keys");
                }
                yield new TextractOcr(id, config, credentials, http);
            }
            default -> throw new OcrException(OcrException.Kind.UNAVAILABLE, 0, id + ": unknown kind");
        };
    }

    // ================================================================== settings API

    @Transactional(readOnly = true)
    public ObjectNode view(Caller caller, UUID workspaceId) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        if (!access.has(Permission.WORKSPACE_MANAGE_ACCESS) && !access.has(Permission.WORKFLOW_READ)
                && !access.has(Permission.AI_EVALUATE) && !access.has(Permission.AI_APPROVE_SKILL)) {
            policy.require(access, Permission.WORKFLOW_READ, "getAiOcrSettings", false);
        }
        return view(workspaceId);
    }

    private ObjectNode view(UUID workspaceId) {
        AiSkills.Policy p = skills.policy(workspaceId);
        record Row(String provider, int version, Instant updatedAt) {
        }
        Row row = jdbc.sql("SELECT provider, version, updated_at FROM ai_ocr_setting WHERE workspace_id = :ws")
                .param("ws", workspaceId).query((rs, n) -> new Row(rs.getString("provider"), rs.getInt("version"),
                        Sql.instant(rs, "updated_at"))).optional().orElse(null);
        ObjectNode o = json.object();
        o.put("provider", row == null ? properties.defaultProvider() : row.provider());
        o.put("chosen", row != null);
        o.put("version", row == null ? 0 : row.version());
        if (row == null) {
            o.putNull("updatedAt");
        } else {
            o.put("updatedAt", row.updatedAt().toString());
        }
        String current = refusal(o.path("provider").asString(), p, row != null);
        if (current == null) {
            o.putNull("refusal");
        } else {
            o.put("refusal", current);
        }
        ArrayNode list = o.putArray("providers");
        Map<String, OcrProperties.Provider> all = new LinkedHashMap<>();
        all.put(OcrProperties.LOCAL, null);
        all.putAll(properties.providers());
        all.forEach((id, provider) -> {
            ObjectNode e = list.addObject();
            e.put("id", id);
            e.put("kind", provider == null ? "local" : provider.kind());
            e.put("label", provider == null ? "On this deployment" + (properties.tesseract().enabled()
                    ? " (text, PDF text layer and Tesseract for images)" : " (text and PDF text layer)")
                    : provider.label() == null ? id : provider.label());
            ArrayNode tags = e.putArray("residency");
            if (provider == null) {
                tags.add("private");
            } else {
                provider.residency().forEach(tags::add);
            }
            String why = refusal(id, p, true);
            e.put("allowed", why == null);
            if (why == null) {
                e.putNull("refusal");
            } else {
                e.put("refusal", why);
            }
        });
        return o;
    }

    @Transactional
    public ObjectNode update(Caller caller, UUID workspaceId, JsonNode body) {
        WorkspaceAccess access = policy.requireVisible(caller, workspaceId);
        policy.require(access, Permission.WORKSPACE_MANAGE_ACCESS, "updateAiOcrSettings", true);
        String provider = body == null ? "" : body.path("provider").asString("");
        int expected = body == null ? -1 : body.path("expectedVersion").asInt(-1);
        int current = jdbc.sql("SELECT version FROM ai_ocr_setting WHERE workspace_id = :ws FOR UPDATE")
                .param("ws", workspaceId).query(Integer.class).optional().orElse(0);
        if (expected != current) {
            throw new ApiException(ProblemCode.CONFIGURATION_VERSION_CONFLICT,
                    "The OCR settings changed since you loaded them.");
        }
        if (!OcrProperties.LOCAL.equals(provider) && !properties.providers().containsKey(provider)) {
            throw invalid("/provider", "Choose local or one of the OCR providers this deployment declares.");
        }
        String refused = refusal(provider, skills.policy(workspaceId), true);
        if (refused != null) {
            throw invalid("/provider", refused);
        }
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO ai_ocr_setting (workspace_id, provider, version, updated_by, updated_at)
                VALUES (:ws, :provider, :version, :by, :now)
                ON CONFLICT (workspace_id) DO UPDATE SET provider = EXCLUDED.provider, version = EXCLUDED.version,
                    updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                """).param("ws", workspaceId).param("provider", provider).param("version", current + 1)
                .param("by", caller.subjectId()).param("now", Sql.ts(now)).update();
        audit.write(new AuditWriter.Entry(workspaceId, AuditWriter.ActorType.USER, caller.subjectId(),
                "ai.ocr-settings.updated", "aiOcrSettings", workspaceId.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("provider", provider, "version", current + 1)));
        return view(workspaceId);
    }

    private static ApiException invalid(String pointer, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message)
                .withFieldErrors(List.of(new ApiException.FieldError(pointer, "invalid", message)));
    }

    // ================================================================== stored layouts

    /** The layout as stored with the S52 OCR result (lines without words, to bound its size). */
    public ObjectNode toJson(OcrDocument document) {
        ObjectNode o = json.object();
        o.put("provider", document.provider()).put("model", document.model());
        ArrayNode pages = o.putArray("pages");
        for (OcrDocument.Page p : document.pages()) {
            ObjectNode page = pages.addObject();
            page.put("number", p.number()).put("width", p.width()).put("height", p.height()).put("unit", p.unit());
            ArrayNode lines = page.putArray("lines");
            for (OcrDocument.Line l : p.lines()) {
                ObjectNode line = lines.addObject();
                line.put("text", l.text());
                box(line, "box", l.box());
                if (l.confidence() == null) {
                    line.putNull("confidence");
                } else {
                    line.put("confidence", round(l.confidence()));
                }
            }
        }
        ArrayNode kv = o.putArray("keyValues");
        for (OcrDocument.KeyValue k : document.keyValues()) {
            ObjectNode e = kv.addObject();
            if (k.field() == null) {
                e.putNull("field");
            } else {
                e.put("field", k.field());
            }
            e.put("key", k.key()).put("value", k.value());
            if (k.confidence() == null) {
                e.putNull("confidence");
            } else {
                e.put("confidence", round(k.confidence()));
            }
            e.put("page", k.page() == null ? 1 : k.page());
            box(e, "box", k.box());
        }
        ArrayNode items = o.putArray("lineItemAmounts");
        document.lineItemAmounts().forEach(items::add);
        return o;
    }

    public static OcrDocument fromJson(JsonNode o) {
        List<OcrDocument.Page> pages = new ArrayList<>();
        for (JsonNode p : o.path("pages")) {
            List<OcrDocument.Line> lines = new ArrayList<>();
            for (JsonNode l : p.path("lines")) {
                lines.add(new OcrDocument.Line(l.path("text").asString(""), box(l.path("box")),
                        l.path("confidence").isNumber() ? l.path("confidence").asDouble() : null, List.of()));
            }
            pages.add(new OcrDocument.Page(p.path("number").asInt(), p.path("width").asDouble(),
                    p.path("height").asDouble(), p.path("unit").asString(""), lines));
        }
        List<OcrDocument.KeyValue> kv = new ArrayList<>();
        for (JsonNode k : o.path("keyValues")) {
            kv.add(new OcrDocument.KeyValue(k.path("field").asString(null), k.path("key").asString(""),
                    k.path("value").asString(""), k.path("confidence").isNumber() ? k.path("confidence").asDouble()
                    : null, k.path("page").asInt(1), box(k.path("box"))));
        }
        List<java.math.BigDecimal> items = new ArrayList<>();
        o.path("lineItemAmounts").forEach(i -> items.add(i.decimalValue()));
        return new OcrDocument(o.path("provider").asString(""), o.path("model").asString(""), pages, kv, items);
    }

    private static void box(ObjectNode o, String name, OcrDocument.Box box) {
        if (box == null) {
            o.putNull(name);
            return;
        }
        o.putObject(name).put("x", box.x()).put("y", box.y()).put("w", box.w()).put("h", box.h());
    }

    private static OcrDocument.Box box(JsonNode b) {
        return b.isObject() ? OcrDocument.Box.of(b.path("x").asDouble(), b.path("y").asDouble(), b.path("w").asDouble(),
                b.path("h").asDouble()) : null;
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
