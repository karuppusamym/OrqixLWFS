package com.orqix.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowElementsContainer;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.GraphicInfo;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.SubProcess;
import org.flowable.bpmn.model.Event;
import org.flowable.bpmn.model.Gateway;
import org.flowable.bpmn.model.Task;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.common.engine.impl.util.io.InputStreamSource;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import com.orqix.migration.camunda.Analysis;
import com.orqix.migration.camunda.Analyzer;
import com.orqix.migration.camunda.Limits;
import com.orqix.migration.camunda.Report;


/**
 * STU04 BPMN export (docs/build/S97) on PostgreSQL: the .bpmn download of a draft and of a release, its permissions and
 * audit, the safe file name and content type, the file read by Flowable's own BPMN 2.0 converter with schema validation
 * (every element has a shape, every flow an edge, in the shared sizes) and the file re-imported through the Camunda
 * importer, which reports only the documented Orqix extensions.
 */
class BpmnExportApiTest extends com.orqix.support.RuntimeTestBase {

    private static final String DRAFT_EXPORT = "/workspaces/{ws}/workflows/{id}/draft/bpmn/export";
    private static final String RELEASE_EXPORT = "/workspaces/{ws}/workflows/{id}/releases/{r}/export/bpmn";

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID workflow() throws Exception {
        var response = api("dana.designer").post(Map.of("key", unique("bpmnx"), "name", "BPMN export " + unique("w"),
                "templateId", "purchase-approval"), "/workspaces/{ws}/workflows", PURCHASING);
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return UUID.fromString(response.body().path("id").asString());
    }

    private String publish(UUID wf) throws Exception {
        var published = api("paul.publisher").post(Map.of("draftRevision", 1, "acknowledgeWarnings", true),
                UUID.randomUUID().toString(), "/workspaces/{ws}/workflows/{id}/publish", PURCHASING, wf);
        assertThat(published.status()).as(published.toString()).isEqualTo(201);
        return published.body().path("id").asString();
    }

    /** Parses with Flowable's converter with XSD validation on (the BPMN 2.0 schema, DI included). */
    static BpmnModel flowableModel(String xml) {
        return new BpmnXMLConverter().convertToBpmnModel(
                new InputStreamSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))), true, false,
                "UTF-8");
    }

    private static void collect(FlowElementsContainer container, List<FlowElement> out) {
        for (FlowElement e : container.getFlowElements()) {
            out.add(e);
            if (e instanceof SubProcess sub) {
                collect(sub, out);
            }
        }
    }

    /** Every flow node has a shape of the shared size, every sequence flow an edge with at least two waypoints. */
    static void assertCompleteDiagram(BpmnModel model, String name) {
        List<FlowElement> all = new ArrayList<>();
        model.getProcesses().forEach(p -> collect(p, all));
        int nodes = 0;
        int flows = 0;
        for (FlowElement e : all) {
            if (e instanceof SequenceFlow) {
                flows++;
                List<GraphicInfo> waypoints = model.getFlowLocationGraphicInfo(e.getId());
                assertThat(waypoints).as(name + " edge of " + e.getId()).isNotNull().hasSizeGreaterThanOrEqualTo(2);
            } else if (e instanceof FlowNode) {
                nodes++;
                GraphicInfo g = model.getGraphicInfo(e.getId());
                assertThat(g).as(name + " shape of " + e.getId()).isNotNull();
                if (e instanceof Event) {
                    assertThat(g.getWidth()).as(e.getId()).isEqualTo(48);
                    assertThat(g.getHeight()).as(e.getId()).isEqualTo(48);
                } else if (e instanceof Gateway) {
                    assertThat(g.getWidth()).as(e.getId()).isEqualTo(64);
                    assertThat(g.getHeight()).as(e.getId()).isEqualTo(64);
                } else if (e instanceof Task || e instanceof CallActivity) {
                    assertThat(g.getWidth()).as(e.getId()).isEqualTo(200);
                    assertThat(g.getHeight()).as(e.getId()).isEqualTo(80);
                }
            }
        }
        assertThat(nodes).isGreaterThan(3);
        assertThat(flows).isGreaterThan(3);
        assertThat(model.getLocationMap()).as(name + " no shapes for unknown elements").hasSize(nodes);
        assertThat(model.getFlowLocationMap()).as(name + " no edges for unknown flows").hasSize(flows);
    }

    @Test
    void aDraftDownloadsAsAStandardBpmnFileForAnyoneWhoCanReadIt() throws Exception {
        UUID wf = workflow();
        String key = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, wf).body()
                .path("definition").path("workflowKey").asString();
        // A viewer (workflow.read) may download the draft; the response is XML with a safe attachment name.
        var response = api("vic.viewer").get(DRAFT_EXPORT, PURCHASING, wf);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("application/xml");
        assertThat(response.header("Content-Disposition")).contains("attachment").contains(key + "-draft-1.bpmn");
        assertThat(response.header("Cache-Control")).contains("no-store");
        String xml = new String(response.bytes(), StandardCharsets.UTF_8);
        assertThat(xml).startsWith("<?xml").contains("<!--").contains("Orqix compiler version:")
                .contains("draft of workflow " + wf + ", revision 1").contains("Semantic hash")
                .contains("orqix:approval").contains("attributes in the Flowable namespace");
        // The header names ids and numbers only: no workflow name, form data or secrets.
        assertThat(xml.substring(0, xml.indexOf("-->"))).doesNotContain("Purchase");

        BpmnModel model = flowableModel(xml);
        assertThat(model.getMainProcess().getId()).isEqualTo(key);
        assertCompleteDiagram(model, "draft");

        // A named revision; an unknown one is 404.
        assertThat(api("vic.viewer").get(DRAFT_EXPORT + "?revision=1", PURCHASING, wf).status()).isEqualTo(200);
        assertThat(api("vic.viewer").get(DRAFT_EXPORT + "?revision=9", PURCHASING, wf).status()).isEqualTo(404);
        // Outsiders and people without any role in the workspace see nothing.
        assertThat(api("otto.outsider").get(DRAFT_EXPORT, PURCHASING, wf).status()).isEqualTo(404);
        assertThat(api("fred.facilities").get(DRAFT_EXPORT, PURCHASING, wf).status()).isEqualTo(404);

        // Audited as an export with ids only: the revision and the format, never the content.
        assertThat(jdbc.sql("SELECT metadata::text FROM audit_event WHERE workspace_id = :ws AND resource_id = :wf "
                + "AND action = 'workflow.exported' ORDER BY occurred_at DESC LIMIT 1")
                .param("ws", PURCHASING).param("wf", wf.toString()).query(String.class).single())
                .contains("\"format\"").contains("bpmn").contains("\"revision\"").doesNotContain("<");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE workspace_id = :ws AND resource_id = :wf "
                + "AND action = 'workflow.exported'").param("ws", PURCHASING).param("wf", wf.toString())
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void aDraftWithBlockingIssuesHasNoBpmnToDownload() throws Exception {
        UUID wf = workflow();
        JsonNode body = api("dana.designer").get("/workspaces/{ws}/workflows/{id}/draft", PURCHASING, wf).body();
        ObjectNode blocked = (ObjectNode) body.path("definition").deepCopy();
        for (JsonNode n : blocked.path("nodes")) {
            if ("manager".equals(n.path("id").asString())) {
                ((ObjectNode) n.path("config")).put("candidateGroupRef", "no-such-group");
            }
        }
        assertThat(api("dana.designer").put(Map.of("definition", blocked), "\"r1\"",
                "/workspaces/{ws}/workflows/{id}/draft", PURCHASING, wf).status()).isEqualTo(200);
        var response = api("vic.viewer").get(DRAFT_EXPORT, PURCHASING, wf);
        assertThat(response.status()).as(response.toString()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("WORKFLOW_INVALID");
        // Nothing was exported, so nothing is audited as an export.
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE workspace_id = :ws AND resource_id = :wf "
                + "AND action = 'workflow.exported'").param("ws", PURCHASING).param("wf", wf.toString())
                .query(Integer.class).single()).isZero();
    }

    @Test
    void aReleaseDownloadsWithExportPermissionAndKeepsTheDeployedProcess() throws Exception {
        UUID wf = workflow();
        String releaseId = publish(wf);
        // workflow.export: a designer and a publisher have it, a requester and a viewer do not.
        assertThat(api("rhea.requester").get(RELEASE_EXPORT, PURCHASING, wf, releaseId).status()).isEqualTo(403);
        assertThat(api("vic.viewer").get(RELEASE_EXPORT, PURCHASING, wf, releaseId).status()).isEqualTo(403);
        assertThat(api("otto.outsider").get(RELEASE_EXPORT, PURCHASING, wf, releaseId).status()).isEqualTo(404);
        assertThat(api("dana.designer").get(RELEASE_EXPORT, PURCHASING, wf, UUID.randomUUID()).status()).isEqualTo(404);
        var response = api("dana.designer").get(RELEASE_EXPORT, PURCHASING, wf, releaseId);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("application/xml");
        assertThat(response.header("Content-Disposition")).contains("-r1.bpmn");
        String xml = new String(response.bytes(), StandardCharsets.UTF_8);
        assertThat(xml).contains("release " + releaseId + " (version 1)");

        // The process is the stored artifact, unchanged; only the header and the diagram are added.
        String bundleBpmn = zipEntry(api("dana.designer").get("/workspaces/{ws}/workflows/{id}/releases/{r}/export",
                PURCHASING, wf, releaseId).bytes(), "process.bpmn20.xml");
        String diagram = "(?s)  <bpmndi:BPMNDiagram.*?</bpmndi:BPMNDiagram>\\n";
        assertThat(xml.replaceAll("(?s)<!--.*?-->\\n", "").replaceAll(diagram, ""))
                .isEqualTo(bundleBpmn.replaceAll(diagram, ""));
        assertCompleteDiagram(flowableModel(xml), "release");
        assertThat(api("paul.publisher").get(RELEASE_EXPORT, PURCHASING, wf, releaseId).status()).isEqualTo(200);

        assertThat(jdbc.sql("SELECT metadata::text FROM audit_event WHERE workspace_id = :ws AND resource_id = :r "
                + "AND action = 'workflow.exported' ORDER BY occurred_at DESC LIMIT 1")
                .param("ws", PURCHASING).param("r", releaseId).query(String.class).single())
                .contains("\"releaseNumber\"").contains("\"format\"").doesNotContain("<");
    }

    private static String zipEntry(byte[] zip, String name) throws Exception {
        try (var in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (entry.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("no " + name);
    }

    /** The reason codes the Camunda importer may use for an Orqix file: documented extensions or Orqix bindings. */
    private static final java.util.Set<String> ALLOWED = java.util.Set.of("EXTENSION_NOT_IMPORTED",
            "START_EVENT_MAPPED", "END_EVENT_MAPPED", "FLOW_MAPPED", "FLOW_ABSORBED", "TARGET_POLICY_REVIEW",
            "APPROVAL_ROUTE_REQUIRED", "JAVA_DELEGATE_NOT_PORTABLE", "EXPRESSION_NOT_SUPPORTED");

    private static List<String> findings(String xml) {
        return Analyzer.analyze(xml, Limits.DEFAULT).items().stream().map(Analysis.Item::issue)
                .map(i -> i.sourceKind() + "|" + i.sourceElementId() + "|" + i.severity() + "|" + i.reasonCode() + "|"
                        + i.explanation()).toList();
    }

    @Test
    void theCamundaImporterReportsNothingNewForTheExportAndOnlyDocumentedExtensions() throws Exception {
        UUID wf = workflow();
        String releaseId = publish(wf);
        String exported = new String(api("dana.designer").get(RELEASE_EXPORT, PURCHASING, wf, releaseId).bytes(),
                StandardCharsets.UTF_8);
        String deployed = zipEntry(api("dana.designer").get("/workspaces/{ws}/workflows/{id}/releases/{r}/export",
                PURCHASING, wf, releaseId).bytes(), "process.bpmn20.xml");

        // The header comment and the complete diagram add nothing to what the importer reports about the deployed BPMN.
        List<String> report = findings(exported);
        assertThat(report).isNotEmpty().isEqualTo(findings(deployed));

        // Every extension it does not import is a documented Orqix one: the orqix namespace elements, and the one
        // flowable element the generator writes (failedJobRetryTimeCycle); everything else is a mapped element or an
        // Orqix execution binding (delegate beans, route conditions, approval outcomes) that the Camunda importer
        // cannot interpret by design and reports as its own findings.
        for (Report.Issue issue : Analyzer.analyze(exported, Limits.DEFAULT).items().stream()
                .map(Analysis.Item::issue).toList()) {
            assertThat(ALLOWED).as(issue.sourceElementId() + " " + issue.explanation()).contains(issue.reasonCode());
            if ("EXTENSION_NOT_IMPORTED".equals(issue.reasonCode())) {
                assertThat(issue.explanation()).as(issue.sourceElementId())
                        .matches("The extension element \\{(https://orqix\\.example/bpmn/v0\\}[A-Za-z]+"
                                + "|http://flowable\\.org/bpmn\\}failedJobRetryTimeCycle) is not imported\\.");
                assertThat(issue.severity()).isEqualTo("info");
            }
        }
        assertThat(report.stream().filter(f -> f.contains("|EXTENSION_NOT_IMPORTED|")).count()).isGreaterThan(5);
        // Diagram and comment never produce a finding.
        assertThat(String.join(" ", report)).doesNotContain("BPMNDiagram").doesNotContain("BPMNShape")
                .doesNotContain("Bounds").doesNotContain("waypoint");
    }
}
