package com.orqix.migration.internal;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.identity.*;
import com.orqix.migration.camunda.Report;
import com.orqix.migration.pega.rules.NativeConversion;
import com.orqix.migration.pega.rules.RuleArchive;
import com.orqix.migration.pega.rules.RuleResolution;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Native Pega rule archives (docs/build/S112, MIG11): inventory storage at upload, the effective-rule and per-rule
 * conversion view, and the conversion inputs for {@link MigrationService}. Decoding is deterministic, so the view is
 * recomputed from the immutable source bytes; the stored inventory and the recorded per-rule conversion are the audit
 * record of what was uploaded and what each rule became.
 */
@Service
public class PegaRuleArchives {
    public static final String FORMAT = "pegaRules";
    private static final Set<String> REQUEST_FIELDS = Set.of("workflowKey", "name", "groupMappings", "approvalRoutes", "childWorkflowMappings", "acknowledgeInfo", "acknowledgeReview", "pegaStartRule");

    public record EntryView(String path, String sha256, String status, String reason, String detail, String ruleType, String className,
            String ruleName, String ruleset, String version, String availability, boolean circumstanced, JsonNode qualifier) { }
    public record CandidateView(String rule, String outcome) { }
    public record KeyView(String ruleType, String ruleName, String status, String chosen, List<CandidateView> candidates, List<JsonNode> variants) { }
    public record ContextView(String className, List<String> chain, List<KeyView> keys) { }
    public record RulesetView(String name, String version) { }
    public record BlockerView(String code, List<String> paths) { }
    public record StartView(String rule, String kind, String context, String name, String label, boolean blocked,
            Report.CompatibilityReport report, List<NativeConversion.RuleResult> rules, List<NativeConversion.Assignment> assignments,
            List<NativeConversion.Subflow> subflows) { }
    public record ConversionView(String rule, String startRule, String resolution, String conversion, String targetKind,
            List<String> targets, String targetSha256, List<String> reasons) { }
    public record View(UUID migrationId, String status, String decoder, String adapterVersion, String sourceSha256, String application,
            List<RulesetView> stack, List<BlockerView> archiveBlockers, List<EntryView> entries, List<ContextView> contexts,
            List<StartView> startingPoints, String convertedStartRule, List<ConversionView> conversion) { }
    record Prepared(ObjectNode definition, Report.CompatibilityReport report, Map<String, String> sourceMap, NativeConversion.Plan plan) { }

    private final JdbcClient jdbc; private final Json json; private final PolicyService policy;
    public PegaRuleArchives(JdbcClient jdbc, Json json, PolicyService policy) { this.jdbc = jdbc; this.json = json; this.policy = policy; }

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
            if (!REQUEST_FIELDS.contains(name) && !(field.getValue().isObject() && field.getValue().isEmpty())) throw ApiException.malformed(name + " is not used for native Pega rule archives. Supply groupMappings, approvalRoutes, childWorkflowMappings, acknowledgements and pegaStartRule; request fields, decisions and stages come from the resolved rules.");
        });
        var analysis = RuleResolution.analyze(source);
        var starts = analysis.startingPoints();
        if (starts.isEmpty()) throw new ApiException(ProblemCode.MIGRATION_BLOCKED, "The archive has no convertible starting point. Resolve the archive findings first.")
                .with("openItems", NativeConversion.archiveReport(analysis).issues().stream().filter(Report.Issue::blocksPublishing).toList());
        String requested = request.path("pegaStartRule").asString(null);
        RuleResolution.Start start;
        if (requested == null) {
            if (starts.size() > 1) throw ApiException.malformed("pegaStartRule is required: the archive has " + starts.size() + " starting points ("
                    + String.join(", ", starts.stream().map(RuleResolution.Start::rule).toList()) + ").");
            start = starts.getFirst();
        } else start = starts.stream().filter(s -> s.rule().equals(requested)).findFirst()
                .orElseThrow(() -> ApiException.malformed("pegaStartRule must be one of the archive's starting points: " + starts.stream().map(RuleResolution.Start::rule).toList() + "."));
        var plan = NativeConversion.plan(analysis, start, json);
        if (plan.blocked()) throw new ApiException(ProblemCode.MIGRATION_BLOCKED, "Unsupported or unresolved native rules must be rebuilt or corrected before conversion. No draft was created.")
                .with("openItems", plan.report().issues().stream().filter(Report.Issue::blocksPublishing).toList());
        var result = NativeConversion.convert(plan, id, request, json);
        return new Prepared(result.definition(), result.report(), result.sourceMap(), plan);
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
                            l.variants().stream().map(v -> { var q = (tools.jackson.databind.node.ObjectNode) RuleResolution.qualifierJson(v.qualifier(), json); q.put("rule", v.rule().path()); return (JsonNode) q; }).toList()); }).toList())).toList();
        List<StartView> starts = analysis.startingPoints().stream().map(s -> {
            var plan = NativeConversion.plan(analysis, s, json);
            return new StartView(s.rule(), s.kind(), s.context(), plan.name(), plan.label(), plan.blocked(), plan.report(), plan.rules(), plan.assignments(), plan.subflows());
        }).toList();
        List<ConversionView> conversion = jdbc.sql("SELECT entry_path,start_rule,resolution,conversion,target_kind,targets::text,target_sha256,reasons::text FROM migration_pega_rule_conversion WHERE workspace_id=:ws AND migration_id=:id ORDER BY entry_path")
                .param("ws", ws).param("id", id).query((rs, n) -> new ConversionView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        strings(rs.getString(6)), rs.getString(7) == null ? null : rs.getString(7).trim(), strings(rs.getString(8)))).list();
        var app = analysis.application();
        return new View(id, job.status(), RuleResolution.DECODER, job.adapter(), analysis.sourceSha256(), app == null ? null : app.ruleName() + " " + app.version(),
                analysis.stack().stream().map(e -> new RulesetView(e.getKey(), e.getValue())).toList(),
                analysis.archiveBlockers().stream().map(b -> new BlockerView(b.code(), b.paths())).toList(), entries, contexts, starts,
                conversion.isEmpty() ? null : conversion.getFirst().startRule(), conversion);
    }

    private List<String> strings(String array) {
        List<String> out = new ArrayList<>();
        json.parse(array).forEach(n -> out.add(n.asString()));
        return out;
    }
}
