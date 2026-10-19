package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * UI03 BPMN source for the S55/S66/S68 constructs (docs/build/S77): sections, handlers, raised errors, signals, the
 * Undo step and automatic undo calls, call failures raised as errors and adapter steps round-trip exactly and can be
 * edited in BPMN; what BPMN cannot carry exactly is still reported and refused.
 */
class BpmnSourceScopesTest {

    private static final WorkflowCompiler COMPILER = Compilers.workflowCompiler();

    private static final Map<String, CompileContext.AdapterInfo> ADAPTERS = Map.of(
            "ops-mail", new CompileContext.AdapterInfo("ops-mail", "email", "Operations mail", true,
                    "{\"from\":\"noreply@example.com\",\"allowedRecipientDomains\":[\"example.com\"],\"maxRecipients\":2}"),
            "orders", new CompileContext.AdapterInfo("orders", "kafka", "Orders topic", true,
                    "{\"allowedTopics\":[\"orders.created\"]}"),
            "lookup", new CompileContext.AdapterInfo("lookup", "jdbc", "Customer lookup", true,
                    "{\"statement\":\"SELECT id FROM c WHERE cc = :costCenter\",\"parameters\":[\"costCenter\"],"
                            + "\"maxRows\":100,\"timeoutSeconds\":10}"),
            "chat", new CompileContext.AdapterInfo("chat", "teams", "Team channel", true,
                    "{\"baseUrl\":\"https://teams.example.com\"}"));

    private static final Function<String, CompileContext> CONTEXT =
            json -> BpmnSourceTest.context(json).withAdapters(ADAPTERS);

    private static CompiledWorkflow compile(String json) throws CompilationException {
        return COMPILER.compile(json, CONTEXT.apply(json));
    }

    private static BpmnSource.Check check(String xml, String base) {
        return BpmnSource.check(xml, base, COMPILER, CONTEXT);
    }

    private static String messages(BpmnSource.Check c) {
        return String.join("\n", c.problems().stream().map(p -> p.kind() + " " + p.elementId() + ": " + p.message())
                .toList());
    }

    // ---------------------------------------------------------------- documents

    /** Purchase approval plus a "Send a signal" step after the order and a "Wait for a signal" step before notifying. */
    static ObjectNode signals() {
        ObjectNode d = TestSupport.purchase();
        add(d, "{\"id\":\"announce\",\"type\":\"sendSignal\",\"label\":\"Announce\",\"config\":{\"signal\":\"order-placed\"}}",
                900, 300);
        add(d, "{\"id\":\"await\",\"type\":\"waitForSignal\",\"label\":\"Wait for goods\",\"config\":"
                + "{\"signal\":\"order-placed\"}}", 1100, 300);
        retarget(d, "t9", "announce");
        path(d, "{\"id\":\"s1\",\"from\":\"announce\",\"to\":\"await\",\"on\":\"next\"}");
        path(d, "{\"id\":\"s2\",\"from\":\"await\",\"to\":\"notify\",\"on\":\"next\"}");
        return d;
    }

    /** Purchase approval with an adapter step of the given config between the start and the manager. */
    static ObjectNode adapter(String config) {
        ObjectNode d = TestSupport.purchase();
        add(d, "{\"id\":\"send\",\"type\":\"sendAdapter\",\"label\":\"Tell operations\",\"config\":" + config + "}",
                300, 600);
        for (JsonNode t : d.path("transitions")) {
            if ("start".equals(t.path("from").asString()) && "manager".equals(t.path("to").asString())) {
                ((ObjectNode) t).put("to", "send");
            }
        }
        path(d, "{\"id\":\"a1\",\"from\":\"send\",\"to\":\"manager\",\"on\":\"next\"}");
        return d;
    }

    static final String EMAIL = "{\"adapter\":\"ops-mail\",\"kind\":\"email\",\"to\":[\"ops@example.com\",\"lead@example.com\"],"
            + "\"subject\":\"New request {{title}}\",\"body\":\"Amount {{amount}}\\nfor <{{purpose}}> & more\","
            + "\"inputMapping\":{\"title\":\"/input/title\",\"amount\":\"/input/amount\",\"purpose\":\"/input/purpose\"}}";
    static final String TEAMS = "{\"adapter\":\"chat\",\"kind\":\"teams\",\"title\":\"Request {{title}}\","
            + "\"text\":\"Please look at {{title}}\",\"inputMapping\":{\"title\":\"/input/title\"}}";
    static final String KAFKA = "{\"adapter\":\"orders\",\"kind\":\"kafka\",\"topic\":\"orders.created\",\"key\":\"{{title}}\","
            + "\"inputMapping\":{\"title\":\"/input/title\",\"amount\":\"/input/amount\"}}";
    static final String JDBC = "{\"adapter\":\"lookup\",\"kind\":\"jdbc\",\"inputMapping\":{\"costCenter\":\"/input/costCenter\"}}";

    /** Every S55/S66/S68 construct, alone or combined, as a draft that compiles. */
    static Map<String, ObjectNode> documents() {
        Map<String, ObjectNode> docs = new java.util.LinkedHashMap<>();
        docs.put("section (time limit, error route, raise error, two exits)", ScopesAndEventsTest.section());
        docs.put("handlers (signal interrupting, timer alongside)", ScopesAndEventsTest.handlers());
        docs.put("undo step and automatic undo call", ScopesAndEventsTest.undo());
        docs.put("call failure raised as a section error", ScopesAndEventsTest.callFailure());
        docs.put("send and wait for a signal", signals());
        docs.put("adapter email", adapter(EMAIL));
        docs.put("adapter teams", adapter(TEAMS));
        docs.put("adapter kafka", adapter(KAFKA));
        docs.put("adapter jdbc", adapter(JDBC));
        ObjectNode both = ScopesAndEventsTest.section();
        ObjectNode withHandlers = ScopesAndEventsTest.handlers();
        for (JsonNode n : withHandlers.path("nodes")) {
            if (List.of("withdrawn", "sayWithdrawn", "remind").contains(n.path("id").asString())) {
                ((ArrayNode) both.path("nodes")).add(n.deepCopy());
                ((ObjectNode) both.path("uiLayout").path("positions")).set(n.path("id").asString(),
                        withHandlers.path("uiLayout").path("positions").path(n.path("id").asString()).deepCopy());
            }
        }
        path(both, "{\"id\":\"w1\",\"from\":\"sayWithdrawn\",\"to\":\"withdrawn\",\"on\":\"next\"}");
        withHandlers.path("subprocesses").forEach(s -> ((ArrayNode) both.path("subprocesses")).add(s.deepCopy()));
        docs.put("section and two handlers", both);
        return docs;
    }

    // ---------------------------------------------------------------- exact round trips

    @Test
    void everyNewConstructRoundTripsExactlyAndItsOwnBpmnApplies() throws Exception {
        for (Map.Entry<String, ObjectNode> e : documents().entrySet()) {
            String json = TestSupport.text(e.getValue());
            CompiledWorkflow compiled = compile(json);
            BpmnSource.Export export = BpmnSource.export(json, compiled);
            assertThat(export.identical()).as(e.getKey() + ": " + export.differences()).isTrue();
            assertThat(export.diagram()).as(e.getKey()).isTrue();
            assertThat(export.keptFromDraft()).as(e.getKey()).doesNotContain("subprocesses");
            BpmnSource.Check again = check(export.bpmnXml(), json);
            assertThat(again.applicable()).as(e.getKey() + "\n" + messages(again)).isTrue();
            assertThat(again.changes()).as(e.getKey()).isEmpty();
            assertThat(again.notes()).as(e.getKey()).isEmpty();
            // Applying the unchanged BPMN gives the draft back canonically identical and byte-identical BPMN.
            assertThat(CanonicalJson.canonicalize(again.definitionJson())).as(e.getKey())
                    .isEqualTo(CanonicalJson.canonicalize(json));
            assertThat(compile(again.definitionJson()).bpmnXml()).as(e.getKey()).isEqualTo(export.bpmnXml());
        }
    }

    @Test
    void theOrderInWhichASectionListsItsStepsIsKeptFromTheDraft() throws Exception {
        // The review section lists its steps in the order they were picked (limit, tooBig, manager, ...), which is not
        // the document order; BPMN cannot show it, nothing depends on it, so it is kept and listed.
        ObjectNode d = ScopesAndEventsTest.section();
        String json = TestSupport.text(d);
        BpmnSource.Export export = BpmnSource.export(json, compile(json));
        assertThat(export.identical()).isTrue();
        assertThat(export.keptFromDraft()).contains("the step order of section 'review'");
        BpmnSource.Check c = check(export.bpmnXml(), json);
        assertThat(StrictJson.parse(c.definitionJson()).path("subprocesses").get(0).path("nodes").toString())
                .isEqualTo("[\"limit\",\"tooBig\",\"manager\",\"amountPolicy\",\"route\",\"finance\"]");
        // In document order nothing is listed.
        ObjectNode sorted = ScopesAndEventsTest.callFailure();
        assertThat(BpmnSource.keptFromDraft(TestSupport.text(sorted), true)).noneMatch(k -> k.contains("step order"));
    }

    @Test
    void anOmittedOrWrittenInterruptingFalseIsTheSameAndKeepsTheDraftsSpelling() throws Exception {
        ObjectNode omitted = ScopesAndEventsTest.handlers();
        ((ObjectNode) omitted.path("subprocesses").get(1)).remove("interrupting");
        ObjectNode sectionFalse = ScopesAndEventsTest.callFailure();
        ((ObjectNode) sectionFalse.path("subprocesses").get(0)).put("interrupting", false);
        for (ObjectNode d : List.of(omitted, sectionFalse)) {
            String json = TestSupport.text(d);
            BpmnSource.Export export = BpmnSource.export(json, compile(json));
            assertThat(export.identical()).as(export.differences().toString()).isTrue();
            BpmnSource.Check c = check(export.bpmnXml(), json);
            assertThat(c.applicable()).as(messages(c)).isTrue();
            assertThat(CanonicalJson.canonicalize(c.definitionJson())).isEqualTo(CanonicalJson.canonicalize(json));
        }
        // An interrupting handler is not a default: true is carried.
        ObjectNode handlers = ScopesAndEventsTest.handlers();
        String json = TestSupport.text(handlers);
        BpmnSource.Check c = check(compile(json).bpmnXml(), json);
        assertThat(StrictJson.parse(c.definitionJson()).path("subprocesses").get(0).path("interrupting").booleanValue())
                .isTrue();
    }

    // ---------------------------------------------------------------- what still cannot round-trip

    @Test
    void anEmptySubprocessListIsNotTheSameAsNoneAndIsReported() throws Exception {
        // ReleaseCompatibility refuses release moves for any document that has a subprocesses member, so [] is not a
        // default; BPMN cannot show it.
        ObjectNode d = TestSupport.purchase();
        d.putArray("subprocesses");
        String json = TestSupport.text(d);
        BpmnSource.Export export = BpmnSource.export(json, compile(json));
        assertThat(export.identical()).isFalse();
        assertThat(export.differences()).extracting(BpmnSource.Difference::path).containsExactly("/subprocesses");
        assertThat(export.differences().get(0).message()).contains("list of sections and handlers")
                .contains("after the BPMN round trip");
        assertThat(check(export.bpmnXml(), json).applicable()).isFalse();
    }

    @Test
    void withoutADiagramTheStepOrderAroundSectionsIsLostAndReportedOnce() throws Exception {
        ObjectNode d = ScopesAndEventsTest.callFailure();
        d.remove("uiLayout");
        String json = TestSupport.text(d);
        BpmnSource.Export export = BpmnSource.export(json, compile(json));
        assertThat(export.diagram()).isFalse();
        assertThat(export.identical()).isFalse();
        // One line each for the steps and the connections, with the reason; no per-index noise.
        assertThat(export.differences()).extracting(BpmnSource.Difference::path).containsExactly("/nodes",
                "/transitions");
        assertThat(export.differences().get(0).message()).contains("order of the steps")
                .contains("Give every step a canvas position");
        assertThat(export.differences().get(1).message()).contains("order of the connections");
        BpmnSource.Check c = check(export.bpmnXml(), json);
        assertThat(c.applicable()).isFalse();
        assertThat(c.problems()).extracting(BpmnSource.Problem::kind).containsOnly("draftRoundTrip");
        // When the draft already lists the section's steps last, the order is kept without a diagram as well.
        ObjectNode ordered = d.deepCopy();
        ArrayNode nodes = (ArrayNode) ordered.get("nodes");
        ArrayNode sorted = ordered.arrayNode();
        nodes.forEach(n -> { if (!List.of("procure", "notify").contains(n.path("id").asString())) sorted.add(n); });
        nodes.forEach(n -> { if (List.of("procure", "notify").contains(n.path("id").asString())) sorted.add(n); });
        ordered.set("nodes", sorted);
        ArrayNode ts = (ArrayNode) ordered.get("transitions");
        ArrayNode sortedTs = ordered.arrayNode();
        ts.forEach(t -> { if (!List.of("procure", "notify").contains(t.path("from").asString())) sortedTs.add(t); });
        ts.forEach(t -> { if (List.of("procure", "notify").contains(t.path("from").asString())) sortedTs.add(t); });
        ordered.set("transitions", sortedTs);
        String orderedJson = TestSupport.text(ordered);
        BpmnSource.Export kept = BpmnSource.export(orderedJson, compile(orderedJson));
        assertThat(kept.identical()).as(kept.differences().toString()).isTrue();
    }

    @Test
    void approvalSettingsBpmnDoesNotCarryStillBlockEditingWithTheirPlace() throws Exception {
        ObjectNode d = ScopesAndEventsTest.section();
        ((ObjectNode) node(d, "finance").get("config")).putArray("separateFrom").add("manager");
        String json = TestSupport.text(d);
        BpmnSource.Export export = BpmnSource.export(json, compile(json));
        assertThat(export.identical()).isFalse();
        assertThat(export.differences()).extracting(BpmnSource.Difference::path)
                .containsExactly("/nodes/" + index(d, "finance") + "/config/separateFrom");
        // Approval e-mails (notifications) are not in the BPMN either.
        ObjectNode mails = ScopesAndEventsTest.section();
        String email = "{\"subject\":\"Request\",\"body\":\"A request changed\",\"recipients\":[\"requester\"]}";
        ((ObjectNode) node(mails, "manager").get("config")).set("notifications", StrictJson.parse(
                "{\"assignment\":" + email + ",\"completion\":" + email + ",\"rejection\":" + email + ",\"overdue\":"
                        + email.replace("\"requester\"", "\"operators\"") + "}"));
        String mailsJson = TestSupport.text(mails);
        assertThat(COMPILER.validate(mailsJson, CONTEXT.apply(mailsJson)).issues().stream()
                .filter(i -> i.severity() == Severity.BLOCKER).map(i -> i.code() + " " + i.message()).toList()).isEmpty();
        BpmnSource.Export mailExport = BpmnSource.export(mailsJson, compile(mailsJson));
        assertThat(mailExport.differences()).extracting(BpmnSource.Difference::path)
                .containsExactly("/nodes/" + index(mails, "manager") + "/config/notifications");
        assertThat(check(mailExport.bpmnXml(), mailsJson).applicable()).isFalse();
    }

    // ---------------------------------------------------------------- accepted edits

    @Test
    void sectionHandlerAndStepSettingsCanBeEditedInBpmn() throws Exception {
        // Section label and time limit (the orqix:scopeTimeout value and the timer it generates).
        String section = TestSupport.text(ScopesAndEventsTest.section());
        String xml = compile(section).bpmnXml();
        BpmnSource.Check c = check(xml.replace("<subProcess id=\"review\" name=\"Review\">",
                "<subProcess id=\"review\" name=\"Approval review\">")
                .replace("<orqix:scopeTimeout scope=\"review\" after=\"P5D\"/>",
                        "<orqix:scopeTimeout scope=\"review\" after=\"P3D\"/>")
                .replace("<timeDuration>P5D</timeDuration>", "<timeDuration>P3D</timeDuration>"), section);
        assertThat(c.applicable()).as(messages(c)).isTrue();
        assertThat(c.changes()).extracting(BpmnSource.Change::what)
                .containsExactly("Section or handler 'review' (Approval review)");
        JsonNode review = StrictJson.parse(c.definitionJson()).path("subprocesses").get(0);
        assertThat(review.path("label").stringValue()).isEqualTo("Approval review");
        assertThat(review.path("timeoutAfter").stringValue()).isEqualTo("P3D");
        assertThat(c.notes()).anySatisfy(n -> assertThat(n).contains("helper elements"));

        // A handler's timer.
        String handlers = TestSupport.text(ScopesAndEventsTest.handlers());
        BpmnSource.Check timer = check(compile(handlers).bpmnXml().replace("<timeDuration>P3D</timeDuration>",
                "<timeDuration>P2D</timeDuration>"), handlers);
        assertThat(timer.applicable()).as(messages(timer)).isTrue();
        assertThat(StrictJson.parse(timer.definitionJson()).path("subprocesses").get(1).path("trigger").toString())
                .isEqualTo("{\"type\":\"timer\",\"after\":\"P2D\"}");

        // An adapter step's subject (Base64 in the attribute, so line breaks survive).
        String email = TestSupport.text(adapter(EMAIL));
        String subject = java.util.Base64.getEncoder().encodeToString("New request {{title}}".getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        String changed = java.util.Base64.getEncoder().encodeToString("Please check {{title}}".getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        BpmnSource.Check mail = check(compile(email).bpmnXml().replace(subject, changed), email);
        assertThat(mail.applicable()).as(messages(mail)).isTrue();
        assertThat(node((ObjectNode) StrictJson.parse(mail.definitionJson()), "send").path("config").path("subject")
                .stringValue()).isEqualTo("Please check {{title}}");

        // A recipient the adapter does not approve is refused by the same compile check as in the studio.
        BpmnSource.Check evil = check(compile(email).bpmnXml().replace("ops@example.com", "ops@evil.example.org"),
                email);
        assertThat(evil.applicable()).isFalse();
        assertThat(evil.blockers()).extracting(ValidationIssue::code).contains("WF_ADAPTER_RECIPIENT");
    }

    @Test
    void aRaisedErrorCodeIsRenamedConsistentlyOrRefused() throws Exception {
        ObjectNode d = ScopesAndEventsTest.section();
        String json = TestSupport.text(d);
        String xml = compile(json).bpmnXml();
        // Rename the raised error code everywhere it is written: step, section error route and the error definition.
        BpmnSource.Check c = check(xml.replace("TOO_EXPENSIVE", "OVER_LIMIT"), json);
        assertThat(c.applicable()).as(messages(c)).isTrue();
        JsonNode applied = StrictJson.parse(c.definitionJson());
        assertThat(node((ObjectNode) applied, "tooBig").path("config").path("errorCode").stringValue())
                .isEqualTo("OVER_LIMIT");
        assertThat(applied.path("subprocesses").get(0).path("onError").path("errorCode").stringValue())
                .isEqualTo("OVER_LIMIT");
        // Changing only the step leaves the error definition and the route inconsistent: refused, nothing guessed.
        BpmnSource.Check half = check(xml.replace("<orqix:raise errorCode=\"TOO_EXPENSIVE\"/>",
                "<orqix:raise errorCode=\"OVER_LIMIT\"/>"), json);
        assertThat(half.applicable()).isFalse();
    }

    // ---------------------------------------------------------------- refusals

    @Test
    void wrongSectionAndHandlerStructuresAreNamedNeverDropped() throws Exception {
        String section = TestSupport.text(ScopesAndEventsTest.section());
        String xml = compile(section).bpmnXml();
        // A helper id the generator does not write.
        assertThat(problem(check(xml.replace("<startEvent id=\"review__start\"", "<startEvent id=\"review__bogus\""),
                section), "review__bogus")).contains("not a generated part of the section 'review'");
        // A plain sub-process without orqix:scope.
        assertThat(problem(check(xml.replace("<orqix:scope kind=\"embedded\"/>", ""), section), "review"))
                .contains("Sub-processes");
        // A section inside a section.
        String nested = xml.replace("<startEvent id=\"review__start\"",
                "<subProcess id=\"inner\" name=\"Inner\"><extensionElements><orqix:scope kind=\"embedded\"/>"
                        + "</extensionElements></subProcess><startEvent id=\"review__start\"");
        assertThat(problem(check(nested, section), "inner")).contains("cannot be nested");
        // The section's generated exit gateway removed: the document always writes it.
        BpmnSource.Check noListener = check(xml.replaceAll("<flowable:executionListener event=\"start\" expression="
                + "\"\\$\\{orqixRoute\\.markExit\\(execution, 'review', 'procure'\\)\\}\"/>", ""), section);
        assertThat(noListener.applicable()).isFalse();
        assertThat(noListener.problems()).extracting(BpmnSource.Problem::kind).contains("notRepresentable");

        String handlers = TestSupport.text(ScopesAndEventsTest.handlers());
        String hx = compile(handlers).bpmnXml();
        // A path into a handler.
        assertThat(problem(check(hx.replace("<sequenceFlow id=\"t1\" sourceRef=\"start\" targetRef=\"manager\"/>",
                "<sequenceFlow id=\"t1\" sourceRef=\"start\" targetRef=\"withdraw\"/>"), handlers), "t1"))
                .contains("started by its trigger only");
        // A trigger that is not error, signal or timer.
        assertThat(problem(check(hx.replace("trigger=\"timer\"", "trigger=\"message\""), handlers), "reminder"))
                .contains("not error, signal or timer");
    }

    // ---------------------------------------------------------------- helpers

    private static String problem(BpmnSource.Check c, String elementId) {
        assertThat(c.applicable()).isFalse();
        BpmnSource.Problem found = c.problems().stream().filter(p -> elementId.equals(p.elementId())).findFirst()
                .orElse(null);
        assertThat(found).as("problem for " + elementId + " in\n" + messages(c)).isNotNull();
        return found.message();
    }

    private static void add(ObjectNode d, String node, int x, int y) {
        JsonNode parsed = StrictJson.parse(node);
        ((ArrayNode) d.path("nodes")).add(parsed);
        ((ObjectNode) d.path("uiLayout").path("positions")).set(parsed.path("id").asString(),
                StrictJson.parse("{\"x\":" + x + ",\"y\":" + y + "}"));
    }

    private static void path(ObjectNode d, String json) {
        ((ArrayNode) d.path("transitions")).add(StrictJson.parse(json));
    }

    private static void retarget(ObjectNode d, String id, String to) {
        for (JsonNode t : d.path("transitions")) {
            if (id.equals(t.path("id").asString())) {
                ((ObjectNode) t).put("to", to);
            }
        }
    }

    private static ObjectNode node(ObjectNode doc, String id) {
        for (JsonNode n : (ArrayNode) doc.get("nodes")) {
            if (id.equals(n.get("id").stringValue())) {
                return (ObjectNode) n;
            }
        }
        throw new IllegalArgumentException(id);
    }

    private static int index(ObjectNode doc, String id) {
        ArrayNode nodes = (ArrayNode) doc.get("nodes");
        for (int i = 0; i < nodes.size(); i++) {
            if (id.equals(nodes.get(i).path("id").asString())) {
                return i;
            }
        }
        throw new IllegalArgumentException(id);
    }
}
