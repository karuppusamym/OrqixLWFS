package com.orqix.migration;

import static org.assertj.core.api.Assertions.*;

import com.orqix.migration.camunda.*;
import java.io.StringReader;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** S11 section 5: every element of every fixture has a disposition; each blocked construct reports its reason code. */
class FixtureDispositionTest {
    private static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("../../examples/migration/" + name));
    }

    /** Ids of all BPMN-namespace elements, read with a parser that refuses DTDs (the fixtures under test have none). */
    private static Set<String> bpmnIds(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        var ids = new TreeSet<String>();
        var all = doc.getElementsByTagNameNS(BPMN, "*");
        for (int i = 0; i < all.getLength(); i++) {
            var e = (Element) all.item(i);
            if (e.hasAttribute("id")) ids.add(e.getAttribute("id"));
        }
        return ids;
    }

    private static Map<String, Set<String>> reasonsById(Analysis analysis) {
        return analysis.items().stream().map(Analysis.Item::issue).collect(Collectors.groupingBy(
                Report.Issue::sourceElementId, TreeMap::new, Collectors.mapping(Report.Issue::reasonCode, Collectors.toSet())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"camunda7-purchase.bpmn", "camunda8-purchase.bpmn", "unsupported-features.bpmn", "parallel-timer-child.bpmn",
            "expression-operators.bpmn", "expression-operators-feel.bpmn", "service-task-groups.bpmn", "multi-instance-call.bpmn",
            "multi-instance-unsupported.bpmn", "business-rule-task.bpmn"})
    void everyElementOfEveryFixtureHasADisposition(String name) throws Exception {
        String xml = fixture(name);
        var analysis = Analyzer.analyze(xml, Limits.DEFAULT);
        assertThat(reasonsById(analysis).keySet()).as("elements without a disposition in %s", name)
                .containsAll(bpmnIds(xml));
    }

    @Test
    void eachBlockedConstructReportsItsReasonCode() throws Exception {
        var analysis = Analyzer.analyze(fixture("unsupported-features.bpmn"), Limits.DEFAULT);
        var reasons = reasonsById(analysis);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("reviewTimeout", "EVENT_NOT_SUPPORTED_IN_V0");
        expected.put("waitOneDay", "DURATION_TIMER_MAPPED");
        for (String task : List.of("calculate", "sendConfirmation", "awaitPayment", "fileRecord")) {
            expected.put(task, "TASK_TYPE_NOT_SUPPORTED");
        }
        expected.put("riskRules", "DMN_IMPORT_NOT_SUPPORTED");
        expected.put("fork", "GATEWAY_NOT_SUPPORTED_IN_V0");
        expected.put("options", "GATEWAY_NOT_SUPPORTED_IN_V0");
        expected.put("nested", "SUBPROCESS_NOT_SUPPORTED");
        expected.put("nestedStart", "INSIDE_UNSUPPORTED_SCOPE");
        expected.put("nestedFlow", "INSIDE_UNSUPPORTED_SCOPE");
        expected.put("callChild", "CHILD_WORKFLOW_MAPPING_REQUIRED");
        expected.forEach((id, code) -> assertThat(reasons.get(id)).as("reason codes of %s", id).contains(code));

        var issues = analysis.items().stream().map(Analysis.Item::issue).toList();
        assertThat(issues).extracting(Report.Issue::reasonCode).contains("LISTENER_DROPPED", "EXPRESSION_NOT_SUPPORTED");
        assertThat(issues.stream().filter(i -> i.severity().equals(Report.BLOCKER)).count()).isGreaterThanOrEqualTo(expected.size() - 2);
        assertThat(Converter.report(analysis, issues).publishable()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"camunda7-purchase.bpmn", "camunda8-purchase.bpmn", "expression-operators.bpmn", "expression-operators-feel.bpmn",
            "multi-instance-call.bpmn", "parallel-timer-child.bpmn"})
    void reviewItemsAreInTheCountsAndNeverBlock(String name) throws Exception {
        var analysis = Analyzer.analyze(fixture(name), Limits.DEFAULT);
        var issues = analysis.items().stream().map(Analysis.Item::issue).toList();
        var report = Converter.report(analysis, issues);
        long review = issues.stream().filter(i -> Report.REVIEW.equals(i.severity())).count();
        assertThat(review).as("review items of %s", name).isGreaterThan(0);
        assertThat(report.elementCounts().review()).isEqualTo((int) review);
        assertThat(report.elementCounts().total()).isEqualTo(issues.size());
        assertThat(issues.stream().filter(i -> Report.REVIEW.equals(i.severity()))).noneMatch(Report.Issue::blocksPublishing);
        // Removing every review item must not change whether the report is ready.
        var withoutReview = issues.stream().filter(i -> !Report.REVIEW.equals(i.severity())).toList();
        assertThat(Converter.report(analysis, withoutReview).publishable()).isEqualTo(report.publishable());
    }

    @Test
    void businessRuleTaskWithALiteralDecisionIdWaitsForAMappedDmnDecision() throws Exception {
        var analysis = Analyzer.analyze(fixture("business-rule-task.bpmn"), Limits.DEFAULT);
        assertThat(analysis.decisions()).containsOnlyKeys("routeRule");
        assertThat(analysis.decisions().get("routeRule").decisionId()).isEqualTo("reviewRoute");
        var issues = analysis.items().stream().map(Analysis.Item::issue).toList();
        assertThat(issues).anySatisfy(i -> {
            assertThat(i.sourceElementId()).isEqualTo("routeRule");
            assertThat(i.reasonCode()).isEqualTo("DMN_IMPORT_NOT_SUPPORTED");
            assertThat(i.severity()).isEqualTo(Report.BLOCKER);
            assertThat(i.explanation()).contains("reviewRoute", "decisionMappings");
        });
        assertThat(Converter.report(analysis, issues).requiredMappings().decisionTasks()).containsExactly("routeRule");
        assertThat(Converter.report(analysis, issues).publishable()).isFalse();
    }

    @Test
    void externalEntitiesAreRejectedWithoutBeingResolved() throws Exception {
        String xml = fixture("xxe-attack.bpmn");
        assertThatThrownBy(() -> Analyzer.analyze(xml, Limits.DEFAULT))
                .isInstanceOf(SourceException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("root:").doesNotContain("127.0.0.1"));
    }
}
