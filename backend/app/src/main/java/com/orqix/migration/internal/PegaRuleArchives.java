package com.orqix.migration.internal;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.audit.AuditWriter;
import com.orqix.cases.CaseDataModel;
import com.orqix.cases.CaseService;
import com.orqix.cases.CmmnSubsetValidator;
import com.orqix.identity.*;
import com.orqix.migration.camunda.Report;
import com.orqix.migration.pega.rules.NativeCaseDefinition;
import com.orqix.migration.pega.rules.NativeConversion;
import com.orqix.migration.pega.rules.RuleArchive;
import com.orqix.migration.pega.rules.RuleResolution;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.web.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Native Pega rule archives (docs/build/S112, S115, S117, MIG11): inventory storage at upload, the effective-rule and
 * per-rule conversion view, the conversion inputs for {@link MigrationService}, and the conversion of a case type to a
 * CMMN case definition through the case definition API. Decoding is deterministic, so the view is recomputed from the
 * immutable source bytes; the stored inventory and the recorded conversions are the audit record.
 */
@Service
public class PegaRuleArchives {
    public static final String FORMAT = "pegaRules";
    private static final Set<String> REQUEST_FIELDS = Set.of("workflowKey", "name", "groupMappings", "approvalRoutes", "childWorkflowMappings",
            "connectionMappings", "acknowledgeInfo", "acknowledgeReview", "pegaStartRule");
    private static final Set<String> CASE_FIELDS = Set.of("key", "name", "pegaStartRule", "workflowMappings", "roleMappings", "dryRun", "acknowledgeInfo", "acknowledgeReview");

    public record EntryView(String path, String sha256, String status, String reason, String detail, String ruleType, String className,
            String ruleName, String ruleset, String version, String availability, boolean circumstanced, JsonNode qualifier) { }
    public record CandidateView(String rule, String outcome) { }
    public record KeyView(String ruleType, String ruleName, String status, String chosen, List<CandidateView> candidates, List<JsonNode> variants) { }
    public record ContextView(String className, List<String> chain, List<KeyView> keys) { }
    public record RulesetView(String name, String version) { }
    public record BlockerView(String code, List<String> paths) { }
    /** The case-definition target of a case type: the processes and alternate stages that need explicit mappings. */
    public record CaseTargetView(List<NativeCaseDefinition.Process> processes, List<NativeCaseDefinition.Alternate> alternates) { }
    public record StartView(String rule, String kind, String context, String name, String label, boolean blocked,
            Report.CompatibilityReport report, List<NativeConversion.RuleResult> rules, List<NativeConversion.Assignment> assignments,
            List<NativeConversion.Subflow> subflows, List<NativeConversion.ServiceCall> calls, CaseTargetView caseTarget) { }
    public record ConversionView(String rule, String startRule, String resolution, String conversion, String targetKind,
            List<String> targets, String targetSha256, List<String> reasons) { }
    public record CaseConversionView(String startRule, UUID caseDefinitionId, String caseKey, int caseVersion, String sourceSha256) { }
    public record View(UUID migrationId, String status, String decoder, String adapterVersion, String sourceSha256, String application,
            List<RulesetView> stack, List<BlockerView> archiveBlockers, List<EntryView> entries, List<ContextView> contexts,
            List<StartView> startingPoints, String convertedStartRule, List<ConversionView> conversion, CaseConversionView caseConversion) { }
    /** A case definition proposal (dry run) or the created definition. */
    public record CaseDefinitionResult(boolean dryRun, String key, String name, String sourceXml, String sourceSha256, JsonNode dataFields,
            JsonNode workflowTasks, Report.CompatibilityReport report, CaseService.Definition definition) { }
    record Prepared(ObjectNode definition, Report.CompatibilityReport report, Map<String, String> sourceMap, NativeConversion.Plan plan) { }

    private final JdbcClient jdbc; private final Json json; private final PolicyService policy;
    private final CaseService cases; private final CmmnSubsetValidator validator; private final AuditWriter audit;
    public PegaRuleArchives(JdbcClient jdbc, Json json, PolicyService policy, CaseService cases, CmmnSubsetValidator validator, AuditWriter audit) {
        this.jdbc = jdbc; this.json = json; this.policy = policy; this.cases = cases; this.validator = validator; this.audit = audit;
    }

    /** The upload report: the first starting point's plan, or the archive-level findings when nothing can be planned. */
    Report.CompatibilityReport report(RuleResolution.Analysis analysis) {
        if (analysis.startingPoints().isEmpty()) return NativeConversion.archiveReport(analysis);
        return NativeConversion.plan(analysis, analysis.startingPoints().getFirst(), json).report();
    }

    void storeInventory(UUID ws, UUID id, RuleResolution.Analysis analysis) {
        for (RuleArchive.Entry e : analysis.entries())
            jdbc.sql("""
                INSERT INTO migration_pega_rule(workspace_id,migration_id,entry_path,entry_sha256,status,reason,rule_type,class_name,rule_name,ruleset,ruleset_version,availability,circumstanced,qualifier)
                VALUES(:ws,:id,:path,:sha,:status,:reason,:type,:cls,:name,:ruleset,:version,:availability,:circumstanced,CAST(:qualifier AS jsonb))
                """).param("ws", ws).param("id", id).param("path", e.path()).param("sha", e.sha256()).param("status", e.status())
                .param("reason", e.reason()).param("type", e.ruleType()).param("cls", e.className()).param("name", e.ruleName())
                .param("ruleset", e.ruleset()).param("version", e.version()).param("availability", e.availability()).param("circumstanced", e.circumstanced())
                .param("qualifier", e.qualifier() == null ? null : json.write(RuleResolution.qualifierJson(e.qualifier(), json))).update();
    }

    /** Checks the request, selects the starting point and builds the draft definition. Nothing is stored here. */
    Prepared prepare(UUID id, byte[] source, JsonNode request) {
        request.properties().forEach(field -> {
            String name = field.getKey();
            // Empty mapping objects carry no intent (the shared review form always sends them); anything else is refused.
            if (!REQUEST_FIELDS.contains(name) && !(field.getValue().isObject() && field.getValue().isEmpty())) throw ApiException.malformed(name + " is not used for native Pega rule archives. Supply groupMappings, approvalRoutes, childWorkflowMappings, connectionMappings, acknowledgements and pegaStartRule; request fields, decisions and stages come from the resolved rules.");
        });
        connectionMappings(request.path("connectionMappings"));
        var analysis = RuleResolution.analyze(source);
        var start = start(analysis, request);
        var plan = NativeConversion.plan(analysis, start, json);
        if (plan.blocked()) throw new ApiException(ProblemCode.MIGRATION_BLOCKED, "Unsupported or unresolved native rules must be rebuilt or corrected before conversion. No draft was created.")
                .with("openItems", plan.report().issues().stream().filter(Report.Issue::blocksPublishing).toList());
        var result = NativeConversion.convert(plan, id, request, json);
        return new Prepared(result.definition(), result.report(), result.sourceMap(), plan);
    }

    private RuleResolution.Start start(RuleResolution.Analysis analysis, JsonNode request) {
        var starts = analysis.startingPoints();
        if (starts.isEmpty()) throw new ApiException(ProblemCode.MIGRATION_BLOCKED, "The archive has no convertible starting point. Resolve the archive findings first.")
                .with("openItems", NativeConversion.archiveReport(analysis).issues().stream().filter(Report.Issue::blocksPublishing).toList());
        String requested = request.path("pegaStartRule").asString(null);
        if (requested == null) {
            if (starts.size() > 1) throw ApiException.malformed("pegaStartRule is required: the archive has " + starts.size() + " starting points ("
                    + String.join(", ", starts.stream().map(RuleResolution.Start::rule).toList()) + ").");
            return starts.getFirst();
        }
        return starts.stream().filter(s -> s.rule().equals(requested)).findFirst()
                .orElseThrow(() -> ApiException.malformed("pegaStartRule must be one of the archive's starting points: " + starts.stream().map(RuleResolution.Start::rule).toList() + "."));
    }

    /** connectionMappings: call step id to {connectionRef, operation, definitionVersion?, timeoutSeconds?} (S117). */
    private static void connectionMappings(JsonNode mappings) {
        if (mappings.isMissingNode() || mappings.isNull()) return;
        if (!mappings.isObject()) throw ApiException.malformed("connectionMappings must be an object.");
        mappings.properties().forEach(e -> {
            String path = "connectionMappings/" + e.getKey(); JsonNode m = e.getValue();
            if (!m.isObject()) throw ApiException.malformed(path + " must be an object.");
            m.propertyNames().forEach(n -> { if (!Set.of("connectionRef", "operation", "definitionVersion", "timeoutSeconds").contains(n)) throw ApiException.malformed(path + "/" + n + " is not a supported field."); });
            if (!m.path("connectionRef").asString("").matches("^[a-z][a-z0-9-]{0,62}[a-z0-9]$")) throw ApiException.malformed(path + "/connectionRef must be a connection key.");
            if (!m.path("operation").asString("").matches("^[a-z][A-Za-z0-9]{0,63}$")) throw ApiException.malformed(path + "/operation must be an operation name.");
            if (m.has("definitionVersion") && (!m.path("definitionVersion").isIntegralNumber() || m.path("definitionVersion").asInt() < 1)) throw ApiException.malformed(path + "/definitionVersion must be a positive integer.");
            if (m.has("timeoutSeconds") && (!m.path("timeoutSeconds").isIntegralNumber() || m.path("timeoutSeconds").asInt() < 1 || m.path("timeoutSeconds").asInt() > 30)) throw ApiException.malformed(path + "/timeoutSeconds must be 1 to 30.");
        });
    }

    void recordConversion(UUID ws, UUID id, NativeConversion.Plan plan) {
        for (NativeConversion.RuleResult r : plan.rules())
            jdbc.sql("""
                INSERT INTO migration_pega_rule_conversion(workspace_id,migration_id,entry_path,start_rule,resolution,conversion,target_kind,targets,target_sha256,reasons,circumstance,delegated_to)
                VALUES(:ws,:id,:path,:start,:resolution,:conversion,:kind,CAST(:targets AS jsonb),:sha,CAST(:reasons AS jsonb),:circumstance,CAST(:delegated AS jsonb))
                """).param("ws", ws).param("id", id).param("path", r.rule()).param("start", plan.start().rule()).param("resolution", r.resolution())
                .param("conversion", r.conversion()).param("kind", r.targetKind()).param("targets", json.write(r.targets()))
                .param("sha", r.targetSha256()).param("reasons", json.write(r.reasons())).param("circumstance", r.circumstance())
                .param("delegated", json.write(r.delegatedTo())).update();
    }

    /**
     * Converts a case type to a CMMN case definition (S117). A dry run builds the proposal and checks it against the case
     * subset; otherwise the definition is created through the case definition API, which deploys it and pins the release
     * of every mapped workflow. Repeating the same request returns the recorded definition; a different one is a conflict.
     */
    @Transactional
    public CaseDefinitionResult caseDefinition(Caller caller, UUID ws, UUID id, JsonNode request) {
        var access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.WORKFLOW_EDIT, "convertPegaCaseDefinition", false);
        if (request == null || !request.isObject()) throw ApiException.malformed("Expected a JSON object.");
        request.propertyNames().forEach(n -> { if (!CASE_FIELDS.contains(n)) throw ApiException.malformed(n + " is not a supported field."); });
        for (String map : List.of("workflowMappings", "roleMappings")) if (request.has(map)) {
            if (!request.path(map).isObject()) throw ApiException.malformed(map + " must be an object.");
            request.path(map).properties().forEach(e -> { if (!e.getValue().isString()) throw ApiException.malformed(map + "/" + e.getKey() + " must be a string."); });
        }
        if (!request.path("key").isString() || !request.path("name").isString() || request.path("name").asString().isBlank() || request.path("name").asString().length() > 200)
            throw ApiException.malformed("key and name are required.");
        for (String flag : List.of("dryRun", "acknowledgeInfo", "acknowledgeReview")) if (request.has(flag) && !request.path(flag).isBoolean()) throw ApiException.malformed(flag + " must be boolean.");
        record Job(String format, byte[] source) { }
        Job job = jdbc.sql("SELECT source_format,source FROM migration_job WHERE workspace_id=:ws AND id=:id FOR UPDATE")
                .param("ws", ws).param("id", id).query((rs, n) -> new Job(rs.getString(1), rs.getBytes(2))).optional().orElseThrow(ApiException::notFound);
        if (!FORMAT.equals(job.format())) throw ApiException.notFound();
        var analysis = RuleResolution.analyze(job.source());
        var start = start(analysis, request);
        if (!start.kind().equals("caseType")) throw ApiException.malformed("pegaStartRule must be a case type to convert to a case definition.");
        boolean dryRun = request.path("dryRun").asBoolean(false);
        ObjectNode stored = (ObjectNode) request.deepCopy(); stored.remove("dryRun");
        var existing = recordedCase(ws, id);
        if (existing != null && !dryRun) {
            if (!json.parse(existing.request()).equals(stored)) throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT, "This archive was already converted to a case definition with a different request.");
            var proposal = NativeCaseDefinition.build(analysis, start, request, json);
            return new CaseDefinitionResult(false, proposal.key(), proposal.name(), proposal.sourceXml(), existing.view().sourceSha256(), proposal.dataFields(), proposal.workflowTasks(),
                    proposal.report(), cases.definitions(caller, ws).stream().filter(d -> d.id().equals(existing.view().caseDefinitionId())).findFirst().orElse(null));
        }
        var proposal = NativeCaseDefinition.build(analysis, start, request, json);
        String sha = Json.sha256Hex(proposal.sourceXml().getBytes(StandardCharsets.UTF_8));
        if (!proposal.report().publishable())
            throw new ApiException(ProblemCode.MIGRATION_BLOCKED, "Resolve the case definition findings, map every workflow and group, and acknowledge the review items. Nothing was created.")
                    .with("openItems", proposal.report().issues().stream().filter(i -> Report.OPEN.equals(i.resolutionStatus()) && !Report.SUPPORTED.equals(i.severity())).toList());
        if (dryRun) {
            validator.validate(proposal.sourceXml(), proposal.key());
            return new CaseDefinitionResult(true, proposal.key(), proposal.name(), proposal.sourceXml(), sha, proposal.dataFields(), proposal.workflowTasks(), proposal.report(), null);
        }
        List<CaseDataModel.Field> fields = new ArrayList<>();
        proposal.dataFields().forEach(f -> fields.add(json.convert(f, CaseDataModel.Field.class)));
        List<CaseService.WorkflowTaskMapping> tasks = new ArrayList<>();
        proposal.workflowTasks().forEach(t -> {
            Map<String, String> in = new LinkedHashMap<>(), out = new LinkedHashMap<>();
            t.path("inputMapping").properties().forEach(e -> in.put(e.getKey(), e.getValue().asString()));
            t.path("resultMapping").properties().forEach(e -> out.put(e.getKey(), e.getValue().asString()));
            tasks.add(new CaseService.WorkflowTaskMapping(t.path("taskId").asString(), in, out));
        });
        var definition = cases.createDefinition(caller, ws, new CaseService.CreateDefinition(proposal.key(), proposal.name(), proposal.sourceXml(), fields, null, tasks));
        jdbc.sql("""
            INSERT INTO migration_pega_case_conversion(workspace_id,migration_id,start_rule,case_definition_id,case_key,case_version,source_sha256,request,report,created_by)
            VALUES(:ws,:id,:start,:def,:key,:version,:sha,CAST(:request AS jsonb),CAST(:report AS jsonb),:by)
            """).param("ws", ws).param("id", id).param("start", start.rule()).param("def", definition.id()).param("key", definition.key())
            .param("version", definition.version()).param("sha", sha).param("request", json.write(stored)).param("report", json.write(proposal.report()))
            .param("by", caller.subjectId()).update();
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "migration.case.converted", "migration", id.toString(), AuditWriter.Outcome.SUCCESS,
                Map.of("migrationId", id.toString(), "caseDefinitionId", definition.id().toString(), "caseKey", definition.key(), "sourceSha256", sha)));
        return new CaseDefinitionResult(false, proposal.key(), proposal.name(), proposal.sourceXml(), sha, proposal.dataFields(), proposal.workflowTasks(), proposal.report(), definition);
    }

    private record RecordedCase(CaseConversionView view, String request) { }

    private RecordedCase recordedCase(UUID ws, UUID id) {
        return jdbc.sql("SELECT start_rule,case_definition_id,case_key,case_version,source_sha256,request::text FROM migration_pega_case_conversion WHERE workspace_id=:ws AND migration_id=:id")
                .param("ws", ws).param("id", id).query((rs, n) -> new RecordedCase(new CaseConversionView(rs.getString(1), Sql.uuid(rs, "case_definition_id"), rs.getString(3),
                        rs.getInt(4), rs.getString(5).trim()), rs.getString(6))).optional().orElse(null);
    }

    @Transactional(readOnly = true)
    public View view(Caller caller, UUID ws, UUID id) {
        var access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.WORKFLOW_READ, "getPegaRuleArchive", false);
        record Job(String format, byte[] source, String status, String adapter) { }
        Job job = jdbc.sql("SELECT source_format,source,status,adapter_version FROM migration_job WHERE workspace_id=:ws AND id=:id")
                .param("ws", ws).param("id", id).query((rs, n) -> new Job(rs.getString(1), rs.getBytes(2), rs.getString(3), rs.getString(4)))
                .optional().orElseThrow(ApiException::notFound);
        if (!FORMAT.equals(job.format())) throw ApiException.notFound();
        var analysis = RuleResolution.analyze(job.source());
        List<EntryView> entries = analysis.entries().stream().map(e -> new EntryView(e.path(), e.sha256(), e.status(), e.reason(), e.detail(), e.ruleType(),
                e.className(), e.ruleName(), e.ruleset(), e.version(), e.availability(), e.circumstanced(),
                e.isRule() ? RuleResolution.qualifierJson(e.qualifier(), json) : null)).toList();
        List<ContextView> contexts = analysis.contexts().values().stream().map(c -> new ContextView(c.className(), c.chain(), c.keys().values().stream()
                .map(l -> { String type = l.key().substring(0, l.key().indexOf(' ')); var any = l.candidates().isEmpty() ? null : analysis.entry(l.candidates().getFirst().rule());
                    return new KeyView(type, any == null ? l.key().substring(type.length() + 1) : any.ruleName(), l.status(), l.chosen() == null ? null : l.chosen().path(),
                            l.candidates().stream().map(cd -> new CandidateView(cd.rule(), cd.outcome())).toList(),
                            l.variants().stream().map(v -> { var q = (ObjectNode) RuleResolution.qualifierJson(v.qualifier(), json); q.put("rule", v.rule().path()); return (JsonNode) q; }).toList()); }).toList())).toList();
        List<StartView> starts = analysis.startingPoints().stream().map(s -> {
            var plan = NativeConversion.plan(analysis, s, json);
            CaseTargetView target = null;
            if (s.kind().equals("caseType")) {
                var preview = NativeCaseDefinition.preview(analysis, s, json);
                target = new CaseTargetView(preview.processes(), preview.alternates());
            }
            return new StartView(s.rule(), s.kind(), s.context(), plan.name(), plan.label(), plan.blocked(), plan.report(), plan.rules(), plan.assignments(),
                    plan.subflows(), plan.calls(), target);
        }).toList();
        List<ConversionView> conversion = jdbc.sql("SELECT entry_path,start_rule,resolution,conversion,target_kind,targets::text,target_sha256,reasons::text FROM migration_pega_rule_conversion WHERE workspace_id=:ws AND migration_id=:id ORDER BY entry_path")
                .param("ws", ws).param("id", id).query((rs, n) -> new ConversionView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        strings(rs.getString(6)), rs.getString(7) == null ? null : rs.getString(7).trim(), strings(rs.getString(8)))).list();
        var app = analysis.application();
        var recorded = recordedCase(ws, id);
        return new View(id, job.status(), RuleResolution.DECODER, job.adapter(), analysis.sourceSha256(), app == null ? null : app.ruleName() + " " + app.version(),
                analysis.stack().stream().map(e -> new RulesetView(e.getKey(), e.getValue())).toList(),
                analysis.archiveBlockers().stream().map(b -> new BlockerView(b.code(), b.paths())).toList(), entries, contexts, starts,
                conversion.isEmpty() ? null : conversion.getFirst().startRule(), conversion, recorded == null ? null : recorded.view());
    }

    private List<String> strings(String array) {
        List<String> out = new ArrayList<>();
        json.parse(array).forEach(n -> out.add(n.asString()));
        return out;
    }
}
