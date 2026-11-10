package com.orqix.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.shared.json.Json;
import com.orqix.shared.junit.JUnitXml;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import jakarta.validation.constraints.Min;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-Q01 (S148) test as code: runs a test suite against a draft revision or the active release and returns the result
 * per case, the coverage (CG-Q03) and the same result as JUnit XML for CI.
 *
 * <ul>
 * <li>With a {@code suite} (a {@code orqix.workflow-tests/v1} document, {@code contracts/workflow-test-suite-v1.schema.json}):
 * a dry run of every case; needs {@code workflow.read} like {@code simulateWorkflowCases}; nothing is stored, the audit
 * event {@code workflow.simulated} carries counts only.</li>
 * <li>Without one: the workflow's shared suite (designer data, {@code workflow.edit}). Against a draft the run is
 * recorded exactly like {@code runWorkflowTestSuite}, so it satisfies the publication gate; against the active release
 * it is not recorded.</li>
 * </ul>
 */
@Service
public class TestRunService {

    public static final String FORMAT = "orqix.workflow-tests/v1";
    static final int MAX_SUITE_BYTES = 1024 * 1024;

    /** {@code suite}: the test file (omit for the shared suite); {@code revision} or {@code useActiveRelease}. */
    public record RunRequest(JsonNode suite, @Min(1) Integer revision, Boolean useActiveRelease) {
    }

    private record Row(String id, String name, String status, boolean passed, JsonNode outcome, List<JsonNode> failures,
                       String problem, JsonNode fieldErrors) {
    }

    private final WorkflowService workflows;
    private final PolicyService policy;
    private final SimulationService simulations;
    private final TestSuiteService suites;
    private final SimulationLimiter limiter;
    private final AuditWriter audit;
    private final Json json;
    private final Schema schema;

    public TestRunService(WorkflowService workflows, PolicyService policy, SimulationService simulations,
            TestSuiteService suites, SimulationLimiter limiter, AuditWriter audit, Json json) {
        this.workflows = workflows;
        this.policy = policy;
        this.simulations = simulations;
        this.suites = suites;
        this.limiter = limiter;
        this.audit = audit;
        this.json = json;
        this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(SchemaLocation.of("classpath:orqix/schemas/workflow-test-suite-v1.schema.json"));
    }

    @Transactional
    public JsonNode run(Caller caller, UUID ws, UUID wf, RunRequest request) {
        WorkflowService.Visible visible = workflows.visible(caller, ws, wf, "runWorkflowTests");
        boolean release = Boolean.TRUE.equals(request.useActiveRelease());
        if (request.suite() == null || request.suite().isNull()) {
            policy.require(visible.access(), Permission.WORKFLOW_EDIT, "runWorkflowTests", true);
            return release ? sharedOnRelease(caller, ws, wf, visible) : sharedOnDraft(caller, ws, wf, visible, request);
        }
        policy.require(visible.access(), Permission.WORKFLOW_READ, "runWorkflowTests", false);
        JsonNode suite = checkFile(request.suite(), visible.workflow().key());
        limiter.check(caller.subjectId());
        SimulationService.Source source = simulations.source(visible, ws, wf, request.revision(), release);
        List<TestSuiteService.Case> cases = new ArrayList<>();
        for (JsonNode c : suite.path("cases")) {
            cases.add(new TestSuiteService.Case(c.path("id").asString(), c.path("name").asString(), c.path("input"),
                    c.hasNonNull("scenario") ? c.path("scenario") : null, c.path("expect").asString(),
                    c.hasNonNull("assertions") ? c.path("assertions") : null));
        }
        ObjectNode body = evaluate(visible, source, cases, suite.get("coverageMinimum"), null, false);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (source.revision() != null) {
            metadata.put("revision", source.revision());
        } else {
            metadata.put("releaseNumber", source.releaseNumber());
        }
        metadata.put("cases", cases.size());
        metadata.put("passed", body.path("passedCount").asInt());
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "workflow.simulated",
                "workflow", wf.toString(), AuditWriter.Outcome.SUCCESS, metadata));
        return body;
    }

    /** Shape (the v1 schema), size, the workflow it names, unique case IDs and each case's shape and assertions. */
    JsonNode checkFile(JsonNode suite, String workflowKey) {
        if (json.write(suite).getBytes(StandardCharsets.UTF_8).length > MAX_SUITE_BYTES) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "The test suite exceeds 1 MiB.");
        }
        if (Json.depth(suite) > 24) {
            throw ApiException.malformed("The test suite is nested too deeply.");
        }
        List<Error> errors = schema.validate(suite);
        if (!errors.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The test suite does not follow " + FORMAT + ".")
                    .withFieldErrors(errors.stream().limit(20).map(e -> new ApiException.FieldError(
                            "/suite" + pointer(e), "schema", e.getMessage())).toList());
        }
        if (suite.has("workflowKey") && !suite.path("workflowKey").asString().equals(workflowKey)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The test suite is for another workflow.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/suite/workflowKey", "workflow",
                            "These cases test " + suite.path("workflowKey").asString() + ", not " + workflowKey + ".")));
        }
        Set<String> ids = new HashSet<>();
        int i = 0;
        for (JsonNode c : suite.path("cases")) {
            String at = "/suite/cases/" + i++;
            if (!ids.add(c.path("id").asString())) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Every case needs its own ID.")
                        .withFieldErrors(List.of(new ApiException.FieldError(at + "/id", "unique",
                                "This ID is used by another case.")));
            }
            simulations.checkShape(c.path("input"), c.hasNonNull("scenario") ? c.path("scenario") : null,
                    c.hasNonNull("assertions") ? c.path("assertions") : null, at);
        }
        return suite;
    }

    /** networknt reports "$.cases[0].id" or a JSON pointer, depending on its path type; both become a pointer. */
    private static String pointer(Error e) {
        String location = e.getInstanceLocation() == null ? "" : e.getInstanceLocation().toString();
        if (location.startsWith("/") || location.isEmpty()) {
            return location;
        }
        return location.replaceFirst("^\\$", "").replaceAll("\\[(\\d+)\\]", ".$1").replace('.', '/');
    }

    private JsonNode sharedOnDraft(Caller caller, UUID ws, UUID wf, WorkflowService.Visible visible, RunRequest request) {
        TestSuiteService.Suite suite = suites.get(caller, ws, wf);
        if (suite.version() == 0) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "This workflow has no shared tests yet.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/suite", "required",
                            "Send a test suite, or share tests from the Test tab first.")));
        }
        int revision = request.revision() == null ? visible.workflow().draftRevision() : request.revision();
        TestSuiteService.Report report = suites.run(caller, ws, wf, new TestSuiteService.Run(revision, suite.version()));
        Map<String, String> names = new LinkedHashMap<>();
        suite.cases().forEach(c -> names.put(c.id(), c.name()));
        List<Row> rows = report.results().stream().map(r -> new Row(r.id(), names.getOrDefault(r.id(), r.id()),
                r.status(), r.passed(), null, r.failures() == null ? List.of() : r.failures(), null, null)).toList();
        ObjectNode source = json.object().put("revision", revision);
        source.putNull("releaseNumber");
        return body(visible, source, rows, report.coverage(), report.coverageMinimum(), suite.version(), true);
    }

    private JsonNode sharedOnRelease(Caller caller, UUID ws, UUID wf, WorkflowService.Visible visible) {
        TestSuiteService.Suite suite = suites.get(caller, ws, wf);
        if (suite.version() == 0) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "This workflow has no shared tests yet.");
        }
        limiter.check(caller.subjectId());
        SimulationService.Source source = simulations.source(visible, ws, wf, null, true);
        return evaluate(visible, source, suite.cases(), suite.coverageMinimum(), suite.version(), false);
    }

    /** The cases against one definition: the expected outcome is the outcome assertion, checked with the others. */
    private ObjectNode evaluate(WorkflowService.Visible visible, SimulationService.Source source,
            List<TestSuiteService.Case> cases, JsonNode minimum, Integer suiteVersion, boolean recorded) {
        List<Row> rows = new ArrayList<>();
        List<SimulationService.CaseOutcome> outcomes = new ArrayList<>();
        List<JsonNode> inputs = new ArrayList<>();
        for (TestSuiteService.Case c : cases) {
            ObjectNode assertions = c.assertions() == null ? json.object() : (ObjectNode) c.assertions().deepCopy();
            assertions.put("outcome", c.expect());
            SimulationService.CaseOutcome o = simulations.outcome(c.id(), source, c.input(), c.scenario(), assertions);
            outcomes.add(o);
            inputs.add(c.input());
            List<JsonNode> failures = new ArrayList<>();
            if (o.assertions() != null) {
                o.assertions().path("results").forEach(r -> {
                    if (!r.path("passed").asBoolean()) {
                        failures.add(r);
                    }
                });
            }
            ArrayNode fieldErrors = null;
            if (o.problem() != null) {
                fieldErrors = json.object().putArray("e");
                for (ApiException.FieldError e : o.fieldErrors()) {
                    fieldErrors.addObject().put("pointer", e.pointer()).put("code", e.code()).put("message", e.message());
                }
            }
            rows.add(new Row(c.id(), c.name(), o.status(), o.passed(), o.outcome(), failures, o.problem(), fieldErrors));
        }
        JsonNode coverage = simulations.coverage(source, inputs, outcomes);
        return body(visible, source.json(json), rows, coverage, minimum, suiteVersion, recorded);
    }

    private ObjectNode body(WorkflowService.Visible visible, ObjectNode source, List<Row> rows, JsonNode coverage,
            JsonNode minimum, Integer suiteVersion, boolean recorded) {
        String key = visible.workflow().key();
        ObjectNode body = json.object();
        body.put("workflowId", visible.workflow().id().toString());
        body.put("workflowKey", key);
        body.set("source", source);
        body.put("suite", suiteVersion == null ? "file" : "shared");
        if (suiteVersion == null) {
            body.putNull("suiteVersion");
        } else {
            body.put("suiteVersion", suiteVersion);
        }
        body.put("recorded", recorded);
        long passedCount = rows.stream().filter(Row::passed).count();
        long invalid = rows.stream().filter(r -> "invalid".equals(r.status())).count();
        boolean casesPassed = !rows.isEmpty() && passedCount == rows.size();
        List<Map<String, Object>> shortfalls = SuiteCoverage.shortfalls(coverage, minimum);
        body.put("passed", casesPassed && shortfalls.isEmpty());
        body.put("casesPassed", casesPassed);
        body.put("total", rows.size());
        body.put("passedCount", passedCount);
        body.put("failedCount", rows.size() - passedCount - invalid);
        body.put("invalidCount", invalid);
        ArrayNode results = body.putArray("results");
        for (Row r : rows) {
            ObjectNode o = results.addObject();
            o.put("id", r.id());
            o.put("name", r.name());
            o.put("status", r.status());
            o.put("passed", r.passed());
            if (r.outcome() != null) {
                o.set("outcome", r.outcome());
            }
            ArrayNode failures = o.putArray("failures");
            r.failures().forEach(failures::add);
            if (r.problem() != null) {
                o.put("problem", r.problem());
                o.set("fieldErrors", r.fieldErrors() == null ? json.object().putArray("e") : r.fieldErrors());
            }
        }
        if (coverage == null) {
            body.putNull("coverage");
        } else {
            body.set("coverage", coverage);
        }
        if (minimum == null || minimum.isNull()) {
            body.putNull("coverageMinimum");
        } else {
            body.set("coverageMinimum", minimum);
        }
        body.set("coverageShortfalls", json.parse(json.write(shortfalls)));
        body.put("junitXml", junit(key, source, rows, coverage, minimum, shortfalls));
        return body;
    }

    static String junit(String key, JsonNode source, List<Row> rows, JsonNode coverage, JsonNode minimum,
            List<Map<String, Object>> shortfalls) {
        String className = "orqix.workflow." + key;
        List<JUnitXml.Case> cases = new ArrayList<>();
        for (Row r : rows) {
            String name = r.name() + " [" + r.id() + "]";
            if ("invalid".equals(r.status())) {
                StringBuilder detail = new StringBuilder();
                if (r.fieldErrors() != null) {
                    r.fieldErrors().forEach(e -> detail.append(e.path("pointer").asString()).append(": ")
                            .append(e.path("message").asString()).append('\n'));
                }
                cases.add(JUnitXml.Case.error(className, name,
                        r.problem() == null ? "The case could not run." : r.problem(), detail.toString()));
            } else if (r.passed()) {
                cases.add(JUnitXml.Case.passed(className, name));
            } else {
                StringBuilder detail = new StringBuilder();
                for (JsonNode f : r.failures()) {
                    detail.append(f.path("label").asString()).append(": expected ").append(f.path("expected").asString())
                            .append(", got ").append(f.path("actual").asString()).append('\n');
                }
                String first = r.failures().isEmpty() ? "The case failed."
                        : r.failures().get(0).path("label").asString() + ": expected "
                                + r.failures().get(0).path("expected").asString() + ", got "
                                + r.failures().get(0).path("actual").asString();
                cases.add(JUnitXml.Case.failed(className, name, first, detail.toString()));
            }
        }
        if (minimum != null && !minimum.isNull()) {
            String name = "Coverage minimum";
            if (shortfalls.isEmpty()) {
                cases.add(JUnitXml.Case.passed(className, name));
            } else {
                StringBuilder message = new StringBuilder("Coverage is below the minimum: ");
                for (int i = 0; i < shortfalls.size(); i++) {
                    Map<String, Object> s = shortfalls.get(i);
                    message.append(i == 0 ? "" : ", ").append(s.get("kind")).append(' ').append(s.get("percent"))
                            .append("% (at least ").append(s.get("minimum")).append("%)");
                }
                cases.add(JUnitXml.Case.failed(className, name, message.toString(),
                        coverage == null ? "No case ran." : coverage.path("summary").asString()));
            }
        }
        List<String[]> properties = new ArrayList<>();
        properties.add(new String[] {"workflowKey", key});
        if (source.hasNonNull("revision")) {
            properties.add(new String[] {"draftRevision", source.path("revision").asString()});
        } else {
            properties.add(new String[] {"releaseNumber", source.path("releaseNumber").asString()});
        }
        if (coverage != null) {
            for (String kind : SuiteCoverage.KINDS) {
                properties.add(new String[] {"coverage." + kind, coverage.path(kind).path("covered").asInt() + "/"
                        + coverage.path(kind).path("total").asInt()});
            }
        }
        return JUnitXml.write(key, properties, cases);
    }
}
