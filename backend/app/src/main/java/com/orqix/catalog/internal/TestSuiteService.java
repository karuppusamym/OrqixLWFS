package com.orqix.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.catalog.api.CatalogDtos;
import com.orqix.compiler.CompiledWorkflow;
import com.orqix.compiler.WorkflowCompiler;
import com.orqix.compiler.CompilationException;
import com.orqix.audit.AuditWriter;
import com.orqix.identity.*;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.*;
import tools.jackson.databind.JsonNode;

/** Saved test samples are designer data. Payloads and traces never enter the publication attestation. */
@Service
public class TestSuiteService {
    /** A shared case; {@code assertions} (STU03) are checked besides the expected outcome {@code expect}. */
    public record Case(@NotBlank @Size(max=80) String id, @NotBlank @Size(max=120) String name,
            @NotNull JsonNode input, @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode scenario, @NotBlank @Size(max=200) String expect,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode assertions) {
        public Case(String id, String name, JsonNode input, JsonNode scenario, String expect) {
            this(id, name, input, scenario, expect, null);
        }
    }
    public record Save(@Min(0) int expectedVersion, @NotNull @Size(max=20) List<@Valid Case> cases) {}
    public record Run(@Min(1) int revision, @Min(1) int suiteVersion) {}
    public record Suite(int version, List<Case> cases) {}
    /** Returned to the designer who ran the suite; {@code failures} are the failed assertions (expected and actual). */
    public record CaseResult(String id, boolean passed, String status,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<JsonNode> failures) {}
    /** What is kept of a run: pass or fail per case, never values. */
    record StoredResult(String id, boolean passed, String status) {}
    public record Report(int suiteVersion, int revision, boolean passed, List<CaseResult> results) {}
    /** "N of M shared tests pass" for everyone who can read the workflow (counts only, no samples). */
    public record Summary(int suiteVersion, int cases, @JsonInclude(JsonInclude.Include.ALWAYS) LastRun lastRun) {}
    public record LastRun(int revision, boolean passed, int passedCases, int totalCases, java.time.Instant testedAt) {}
    private final JdbcClient jdbc;
    private final Json json;
    private final WorkflowService workflows;
    private final CatalogStore store;
    private final PolicyService policy;
    private final SimulationService simulations;
    private final CompileContextFactory contexts;
    private final WorkflowCompiler compiler;
    private final AuditWriter audit;
    private final SimulationLimiter limiter;
    public TestSuiteService(JdbcClient jdbc, Json json, WorkflowService workflows, CatalogStore store,
            PolicyService policy, SimulationService simulations, CompileContextFactory contexts, WorkflowCompiler compiler,
            AuditWriter audit, SimulationLimiter limiter) {
        this.jdbc=jdbc; this.json=json; this.workflows=workflows; this.store=store;
        this.policy=policy; this.simulations=simulations;
        this.contexts=contexts; this.compiler=compiler;
        this.audit=audit; this.limiter=limiter;
    }
    /**
     * STU03: the size of the shared suite and its latest recorded run, for everyone who can read the workflow. Counts
     * only: no sample, name or expected value. Informational; publishing is still decided by {@link #requirePublish}.
     */
    @Transactional(readOnly=true)
    public Summary summary(Caller caller, UUID ws, UUID wf) {
        var visible=workflows.visible(caller,ws,wf,"getWorkflowTestSummary");
        policy.require(visible.access(),Permission.WORKFLOW_READ,"getWorkflowTestSummary",false);
        Suite suite=latest(ws,wf);
        LastRun last=suite.version()==0?null:jdbc.sql("""
                SELECT draft_revision, passed, tested_at, jsonb_array_length(results) AS total,
                       (SELECT count(*) FROM jsonb_array_elements(results) e WHERE (e->>'passed')::boolean) AS ok
                FROM workflow_test_result WHERE workspace_id=:ws AND workflow_id=:wf AND suite_version=:v
                ORDER BY tested_at DESC LIMIT 1""")
            .param("ws",ws).param("wf",wf).param("v",suite.version())
            .query((rs,n)->new LastRun(rs.getInt("draft_revision"),rs.getBoolean("passed"),rs.getInt("ok"),rs.getInt("total"),
                    rs.getTimestamp("tested_at").toInstant())).optional().orElse(null);
        return new Summary(suite.version(),suite.cases().size(),last);
    }
    private void requireDesigner(Caller caller, UUID ws, UUID wf, String action) {
        var visible=workflows.visible(caller,ws,wf,action);
        policy.require(visible.access(),Permission.WORKFLOW_EDIT,action,!"getWorkflowTestSuite".equals(action));
    }
    private Suite latest(UUID ws, UUID wf) {
        return jdbc.sql("SELECT version,cases::text FROM workflow_test_suite WHERE workspace_id=:ws AND workflow_id=:wf ORDER BY version DESC LIMIT 1")
            .param("ws",ws).param("wf",wf).query((rs,n)-> {
                var cases=new ArrayList<Case>();
                for (JsonNode c:json.parse(rs.getString(2))) cases.add(new Case(c.path("id").asString(),c.path("name").asString(),c.path("input"),c.hasNonNull("scenario")?c.path("scenario"):null,c.path("expect").asString(),c.hasNonNull("assertions")?c.path("assertions"):null));
                return new Suite(rs.getInt(1),List.copyOf(cases));
            }).optional().orElse(new Suite(0,List.of()));
    }
    @Transactional(readOnly=true)
    public Suite get(Caller caller, UUID ws, UUID wf) {
        requireDesigner(caller,ws,wf,"getWorkflowTestSuite"); return latest(ws,wf);
    }
    @Transactional
    public Suite save(Caller caller, UUID ws, UUID wf, Save request) {
        requireDesigner(caller,ws,wf,"saveWorkflowTestSuite");
        store.lockWorkflow(ws,wf).orElseThrow(ApiException::notFound);
        Suite old=latest(ws,wf);
        if(old.version()!=request.expectedVersion()) throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT,"The shared tests changed. Reload before saving.").with("currentVersion",old.version());
        String text=json.write(request.cases());
        if(text.getBytes(StandardCharsets.UTF_8).length>64*1024) throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE,"Shared tests exceed 64 KiB.");
        var ids=new HashSet<String>();
        for(Case c:request.cases()) {
            if(!ids.add(c.id()) || !c.input().isObject() || (c.scenario()!=null && !c.scenario().isObject()) || Json.depth(c.input())>16 || (c.scenario()!=null && Json.depth(c.scenario())>16))
                throw new ApiException(ProblemCode.VALIDATION_FAILED,"Use unique case IDs, object samples and settings, and nesting at most 16 levels.");
            if(c.scenario()!=null && c.scenario().path("simulateChildren").asBoolean(false))
                throw new ApiException(ProblemCode.VALIDATION_FAILED,"Shared publication tests use explicit child outcomes. Recursive child simulation is available for personal dry runs.");
            if(c.assertions()!=null) {
                if(Json.depth(c.assertions())>16) throw ApiException.malformed("The assertions are nested too deeply.");
                try { com.orqix.compiler.SimulationAssertions.validate(c.assertions()); }
                catch(IllegalArgumentException e) {
                    throw new ApiException(ProblemCode.VALIDATION_FAILED,"The assertions of a shared test are not valid.")
                        .withFieldErrors(List.of(new ApiException.FieldError("/cases/"+request.cases().indexOf(c)+"/assertions","type",e.getMessage())));
                }
            }
        }
        jdbc.sql("INSERT INTO workflow_test_suite(workspace_id,workflow_id,version,cases,created_by) VALUES(:ws,:wf,:v,CAST(:cases AS jsonb),:by)")
            .param("ws",ws).param("wf",wf).param("v",old.version()+1).param("cases",text).param("by",caller.subjectId()).update();
        audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.USER,caller.subjectId(),"workflow.tests.saved",
            "workflow",wf.toString(),AuditWriter.Outcome.SUCCESS,java.util.Map.of("suiteVersion",old.version()+1,"cases",request.cases().size())));
        return new Suite(old.version()+1,List.copyOf(request.cases()));
    }
    @Transactional
    public Report run(Caller caller, UUID ws, UUID wf, Run request) {
        requireDesigner(caller,ws,wf,"runWorkflowTestSuite");
        store.lockWorkflow(ws,wf).orElseThrow(ApiException::notFound);
        Suite suite=latest(ws,wf);
        if(suite.version()!=request.suiteVersion()) throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT,"The shared tests changed. Reload before testing.");
        var draft=store.draft(ws,wf,request.revision()).orElseThrow(ApiException::notFound);
        var visible=workflows.visible(caller,ws,wf,"runWorkflowTestSuite");
        String bindingHash;
        try {
            bindingHash=bindingHash(compiler.compile(draft.definitionJson(),contexts.create(visible.access().workspace(),draft.definitionJson())),suite);
        } catch (CompilationException ex) {
            throw new ApiException(ProblemCode.WORKFLOW_INVALID,"Fix blocking workflow issues before running shared tests.")
                .with("issues",WorkflowService.issues(ex.report().issues()));
        }
        List<CaseResult> results=new ArrayList<>();
        // One dry run for the rate limit (STU03); each case is the same pure evaluation as simulateWorkflow.
        limiter.check(caller.subjectId());
        SimulationService.Source source=null;
        for(Case c:suite.cases()) {
            try {
                if(source==null) source=simulations.source(visible,ws,wf,request.revision(),false);
                simulations.checkShape(c.input(),c.scenario(),c.assertions(),"");
                // The expected outcome is the outcome assertion; other assertions are checked with it.
                var assertions=c.assertions()==null?json.object():(tools.jackson.databind.node.ObjectNode)c.assertions().deepCopy();
                assertions.put("outcome",c.expect());
                SimulationService.CaseOutcome outcome=simulations.outcome(c.id(),source,c.input(),c.scenario(),assertions);
                List<JsonNode> failures=new ArrayList<>();
                if(outcome.assertions()!=null) outcome.assertions().path("results").forEach(r -> { if(!r.path("passed").asBoolean()) failures.add(r); });
                results.add(new CaseResult(c.id(),outcome.passed(),outcome.status(),List.copyOf(failures)));
            } catch(ApiException ex) {
                if (ex.code()==ProblemCode.RATE_LIMITED || ex.code()==ProblemCode.WORKFLOW_INVALID) throw ex;
                results.add(new CaseResult(c.id(),false,"invalid",List.of()));
            }
        }
        boolean passed=!results.isEmpty() && results.stream().allMatch(CaseResult::passed);
        List<StoredResult> stored=results.stream().map(r -> new StoredResult(r.id(),r.passed(),r.status())).toList();
        jdbc.sql("INSERT INTO workflow_test_result(workspace_id,workflow_id,suite_version,draft_revision,binding_hash,passed,results,tested_by) VALUES(:ws,:wf,:v,:rev,:hash,:passed,CAST(:results AS jsonb),:by)")
            .param("ws",ws).param("wf",wf).param("v",suite.version()).param("rev",request.revision()).param("hash",bindingHash).param("passed",passed).param("results",json.write(stored)).param("by",caller.subjectId()).update();
        audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.USER,caller.subjectId(),"workflow.tests.run",
            "workflow",wf.toString(),AuditWriter.Outcome.SUCCESS,java.util.Map.of("suiteVersion",suite.version(),"revision",request.revision(),"cases",results.size(),"passed",passed)));
        return new Report(suite.version(),request.revision(),passed,List.copyOf(results));
    }
    /** Caller has already passed publication authorization; no sample or trace is returned to a publisher. */
    void requirePublish(UUID ws, UUID wf, int revision, CompiledWorkflow compiled) {
        Suite suite=latest(ws,wf);
        if(suite.cases().isEmpty()) return;
        boolean passed=jdbc.sql("SELECT passed AND binding_hash=:hash FROM workflow_test_result WHERE workspace_id=:ws AND workflow_id=:wf AND suite_version=:v AND draft_revision=:rev ORDER BY tested_at DESC LIMIT 1")
            .param("ws",ws).param("wf",wf).param("v",suite.version()).param("rev",revision).param("hash",bindingHash(compiled,suite)).query(Boolean.class).optional().orElse(false);
        if(!passed) throw new ApiException(ProblemCode.WORKFLOW_INVALID,"Run and pass the current shared tests against this exact draft before publishing.").with("testSuiteVersion",suite.version()).with("draftRevision",revision);
    }
    private String bindingHash(CompiledWorkflow compiled, Suite suite) {
        return Json.canonicalSha256(json.write(java.util.Map.of("compilerVersion",compiled.compilerVersion(),
            "semanticHash",compiled.semanticHash(),"connections",compiled.connections(),"children",compiled.children(),
            "suiteHash",Json.canonicalSha256(json.write(suite.cases())))));
    }
}
