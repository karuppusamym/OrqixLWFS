package com.orqix.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import com.orqix.compiler.internal.bpmn.BpmnCompare;
import com.orqix.compiler.internal.bpmn.BpmnReader;
import com.orqix.compiler.internal.bpmn.BpmnXml;
import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * BPMN as an editable source of a workflow draft (UI03, docs/build/S56).
 * <p>
 * The BPMN is exactly what {@link WorkflowCompiler#compile} generates. It carries the process part of the document:
 * the workflow name, the steps ({@code nodes}), the connections ({@code transitions}) and, when the diagram is present,
 * the canvas positions. Every other section is kept from the draft unchanged ({@link #keptFromDraft}).
 * <p>
 * {@link #export} proves the round trip document &rarr; BPMN &rarr; document for a draft and lists every difference.
 * {@link #check} reads edited BPMN back and accepts it only when (1) the text is safe XML in the generated dialect with
 * nothing unsupported, (2) the document built from it compiles without blockers, (3) regenerating BPMN from that document
 * reproduces every element, attribute and text of the submitted BPMN (computed values excepted, see
 * {@code BpmnCompare}) and (4) reading the regenerated BPMN gives the same document again. Anything else is refused with
 * the specific element and difference; nothing is dropped silently.
 */
public final class BpmnSource {

    /** One difference between two documents: a JSON Pointer into the workflow document and an explanation. */
    public record Difference(String path, String message) {
    }

    /**
     * Why BPMN cannot be applied. {@code kind}: {@code xml} (unreadable or unsafe text), {@code unsupported} (a construct
     * the document cannot hold), {@code identity} (another workflow key), {@code blocker} (the resulting draft does not
     * compile), {@code notRepresentable}/{@code otherSection} (the BPMN differs from what the document produces) or
     * {@code roundTrip} (the resulting document does not read back identically) or {@code draftRoundTrip} (the current
     * draft itself has no exact BPMN form, so applying BPMN would lose what BPMN cannot carry).
     */
    public record Problem(String kind, String elementId, String message) {
    }

    /** One line of "what applying would change": {@code kind} is added, removed or changed. */
    public record Change(String kind, String what) {
    }

    /**
     * @param identical   the draft converts to BPMN and back without any difference in the carried sections
     * @param differences why it does not (empty when identical)
     * @param diagram     the BPMN contains a diagram (every step has a canvas position)
     */
    public record Export(String bpmnXml, boolean identical, List<Difference> differences, boolean diagram,
            List<String> keptFromDraft) {
    }

    /**
     * @param definitionJson the new draft (base with the BPMN's process part), only when {@code applicable}
     * @param blockers       compiler blockers of the resulting draft (also listed as problems)
     */
    public record Check(boolean applicable, List<Problem> problems, List<String> notes, List<Change> changes,
            String definitionJson, List<ValidationIssue> blockers) {
    }

    private static final Set<String> CARRIED = Set.of("workflowKey", "name", "nodes", "transitions", "uiLayout");
    private static final int MAX_DIFFERENCES = 50;
    private static final int MAX_CHANGES = 30;

    private BpmnSource() {
    }

    /** Sections of the document that BPMN does not carry and that applying BPMN keeps from the draft. */
    public static List<String> keptFromDraft(String definitionJson, boolean diagram) {
        JsonNode def = StrictJson.parse(definitionJson);
        List<String> kept = new ArrayList<>();
        def.propertyNames().stream().filter(name -> !CARRIED.contains(name)).forEach(kept::add);
        if (def.path("uiLayout").has("viewport")) {
            kept.add("uiLayout.viewport");
        }
        if (!diagram && def.path("uiLayout").has("positions")) {
            kept.add("uiLayout.positions");
        }
        return List.copyOf(kept);
    }

    /** Round trip of a draft that compiled: reads {@code compiled.bpmnXml()} back and compares with the draft. */
    public static Export export(String definitionJson, CompiledWorkflow compiled) {
        JsonNode def = StrictJson.parse(definitionJson);
        BpmnXml.Parsed parsed = BpmnXml.parse(compiled.bpmnXml());
        BpmnReader.Result read = BpmnReader.read(parsed.root());
        List<Difference> differences = new ArrayList<>();
        read.findings().forEach(f -> differences.add(new Difference("", f.message())));
        boolean diagram = read.graph() != null && read.graph().get("positions") != null
                && !read.graph().get("positions").isNull();
        if (read.graph() != null) {
            differences.addAll(diff(project(def, diagram), read.graph()));
        }
        return new Export(compiled.bpmnXml(), differences.isEmpty(),
                List.copyOf(differences.subList(0, Math.min(differences.size(), MAX_DIFFERENCES))), diagram,
                keptFromDraft(definitionJson, diagram));
    }

    /**
     * Checks edited BPMN against a base draft.
     *
     * @param contextFor compile context for a candidate document (child workflows depend on the document)
     */
    public static Check check(String submittedXml, String baseDefinitionJson, WorkflowCompiler compiler,
            Function<String, CompileContext> contextFor) {
        List<Problem> problems = new ArrayList<>();
        // The draft itself must be exactly representable; otherwise applying BPMN would drop what BPMN cannot carry.
        try {
            Export own = export(baseDefinitionJson, compiler.compile(baseDefinitionJson,
                    contextFor.apply(baseDefinitionJson)));
            own.differences().forEach(d -> problems.add(new Problem("draftRoundTrip", null, "The current draft does "
                    + "not convert to BPMN and back exactly, so BPMN cannot be applied to it: " + d.message())));
        } catch (CompilationException e) {
            problems.add(new Problem("draftRoundTrip", null, "The current draft has blocking issues, so it has no BPMN "
                    + "form to edit. Fix them in the studio first (Check workflow lists them)."));
        }
        if (!problems.isEmpty()) {
            return refused(problems);
        }
        BpmnXml.Parsed parsed;
        try {
            parsed = BpmnXml.parse(submittedXml);
        } catch (BpmnXml.Unreadable e) {
            return refused(List.of(new Problem("xml", null, e.getMessage())));
        }
        parsed.ignorable().forEach(text -> problems.add(new Problem(BpmnReader.UNSUPPORTED, null, text
                + " cannot be kept: the workflow document has no place for XML comments or processing instructions.")));
        BpmnReader.Result read = BpmnReader.read(parsed.root());
        read.findings().forEach(f -> problems.add(new Problem(f.kind(), f.elementId(), f.message())));
        if (read.graph() == null) {
            return refused(problems);
        }
        ObjectNode base = (ObjectNode) StrictJson.parse(baseDefinitionJson);
        String key = base.path("workflowKey").asString(null);
        String processId = read.graph().path("workflowKey").asString(null);
        if (key == null || !key.equals(processId)) {
            problems.add(new Problem("identity", processId, "The process id is '" + processId + "', but this workflow's "
                    + "key is '" + key + "'. The workflow key cannot be changed in BPMN."));
        }
        if (!problems.isEmpty()) {
            return refused(problems);
        }
        ObjectNode candidate = merge(base, read.graph());
        String candidateJson = write(candidate);
        CompiledWorkflow compiled;
        try {
            compiled = compiler.compile(candidateJson, contextFor.apply(candidateJson));
        } catch (CompilationException e) {
            List<ValidationIssue> blockers = e.report().issues().stream()
                    .filter(i -> i.severity() == Severity.BLOCKER).toList();
            blockers.forEach(b -> problems.add(new Problem("blocker", b.nodeId() != null ? b.nodeId() : b.transitionId(),
                    b.message() + " (" + b.code() + (b.path() == null ? "" : " at " + b.path()) + ")")));
            return new Check(false, List.copyOf(problems), List.of(), changes(base, candidate), null,
                    List.copyOf(blockers));
        }
        BpmnXml.El regenerated = BpmnXml.parse(compiled.bpmnXml()).root();
        BpmnCompare.Result compared = BpmnCompare.compare(parsed.root(), regenerated);
        compared.problems().forEach(d -> problems.add(new Problem(d.kind(), d.elementId(), d.message())));
        BpmnReader.Result again = BpmnReader.read(regenerated);
        again.findings().forEach(f -> problems.add(new Problem("roundTrip", f.elementId(), f.message())));
        if (again.graph() != null) {
            boolean diagram = again.graph().get("positions") != null && !again.graph().get("positions").isNull();
            diff(project(candidate, diagram), again.graph())
                    .forEach(d -> problems.add(new Problem("roundTrip", null, d.message())));
        }
        boolean ok = problems.isEmpty();
        return new Check(ok, List.copyOf(problems), compared.notes(), changes(base, candidate),
                ok ? candidateJson : null, List.of());
    }

    // ------------------------------------------------------------------------------------------------ document

    /** The base draft with the process part replaced by what the BPMN says. */
    static ObjectNode merge(ObjectNode base, ObjectNode graph) {
        ObjectNode candidate = base.deepCopy();
        candidate.set("name", graph.get("name"));
        candidate.set("nodes", graph.get("nodes"));
        candidate.set("transitions", graph.get("transitions"));
        JsonNode positions = graph.get("positions");
        Set<String> ids = new LinkedHashSet<>();
        graph.get("nodes").forEach(n -> ids.add(n.path("id").asString()));
        if (positions != null && !positions.isNull()) {
            ObjectNode layout = candidate.path("uiLayout").isObject() ? (ObjectNode) candidate.get("uiLayout")
                    : candidate.putObject("uiLayout");
            ObjectNode rebuilt = candidate.objectNode();
            rebuilt.set("positions", positions);
            layout.properties().forEach(e -> {
                if (!e.getKey().equals("positions")) {
                    rebuilt.set(e.getKey(), e.getValue());
                }
            });
            candidate.set("uiLayout", rebuilt);
        } else if (candidate.path("uiLayout").path("positions").isObject()) {
            // Without a diagram, positions stay as they were; those of steps that no longer exist are dropped.
            ObjectNode kept = (ObjectNode) candidate.get("uiLayout").get("positions");
            kept.propertyNames().stream().filter(id -> !ids.contains(id)).toList().forEach(kept::remove);
        }
        return candidate;
    }

    /** The carried sections of a document in the shape {@link BpmnReader} produces. */
    private static ObjectNode project(JsonNode def, boolean diagram) {
        ObjectNode p = StrictJson.mapper().createObjectNode();
        p.set("workflowKey", def.get("workflowKey"));
        p.set("name", def.get("name"));
        p.set("nodes", def.get("nodes"));
        p.set("transitions", def.get("transitions"));
        if (diagram) {
            JsonNode positions = def.path("uiLayout").get("positions");
            p.set("positions", positions == null ? p.objectNode() : positions);
        }
        return p;
    }

    /** Differences between a projected document (the expectation) and what was read from BPMN. */
    private static List<Difference> diff(ObjectNode expected, ObjectNode actual) {
        List<Difference> out = new ArrayList<>();
        for (String field : List.of("workflowKey", "name", "nodes", "transitions", "positions")) {
            if (!expected.has(field) && field.equals("positions")) {
                continue;
            }
            JsonNode e = expected.get(field);
            JsonNode a = actual.get(field);
            String pointer = field.equals("positions") ? "/uiLayout/positions" : "/" + field;
            compare(e, a, pointer, expected, out);
        }
        return out;
    }

    private static void compare(JsonNode e, JsonNode a, String pointer, ObjectNode doc, List<Difference> out) {
        if (out.size() >= MAX_DIFFERENCES) {
            return;
        }
        boolean eMissing = e == null || e.isNull() || e.isMissingNode();
        boolean aMissing = a == null || a.isNull() || a.isMissingNode();
        if (eMissing || aMissing) {
            if (eMissing != aMissing) {
                out.add(new Difference(pointer, where(pointer, doc) + " is " + show(e) + " in the draft but "
                        + show(a) + " after the BPMN round trip."));
            }
            return;
        }
        if (e.isObject() && a.isObject()) {
            Set<String> names = new TreeSet<>(e.propertyNames());
            names.addAll(a.propertyNames());
            for (String name : names) {
                compare(e.get(name), a.get(name), pointer + "/" + name.replace("~", "~0").replace("/", "~1"), doc, out);
            }
            return;
        }
        if (e.isArray() && a.isArray()) {
            for (int i = 0; i < Math.max(e.size(), a.size()); i++) {
                compare(e.get(i), a.get(i), pointer + "/" + i, doc, out);
            }
            return;
        }
        if (!sameScalar(e, a)) {
            out.add(new Difference(pointer, where(pointer, doc) + " is " + show(e) + " in the draft but " + show(a)
                    + " after the BPMN round trip."));
        }
    }

    private static boolean sameScalar(JsonNode e, JsonNode a) {
        if (e.isNumber() && a.isNumber()) {
            return e.decimalValue().compareTo(a.decimalValue()) == 0;
        }
        return e.equals(a);
    }

    /** "Step 'manager' config.dueAfterHours" for /nodes/1/config/dueAfterHours. */
    private static String where(String pointer, ObjectNode doc) {
        String[] parts = pointer.substring(1).split("/");
        String section = parts[0];
        if ((section.equals("nodes") || section.equals("transitions")) && parts.length > 1) {
            JsonNode item = doc.path(section).path(Integer.parseInt(parts[1]));
            String id = item.path("id").asString("#" + parts[1]);
            String rest = parts.length > 2 ? " " + String.join(".", java.util.Arrays.copyOfRange(parts, 2, parts.length))
                    : "";
            return (section.equals("nodes") ? "Step '" : "Connection '") + id + "'" + rest;
        }
        if (section.equals("uiLayout") && parts.length > 2) {
            return "The canvas position of '" + parts[2] + "'" + (parts.length > 3 ? " " + parts[3] : "");
        }
        return "The " + (section.equals("workflowKey") ? "workflow key" : section.equals("name") ? "workflow name"
                : section);
    }

    private static String show(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "absent";
        }
        String text = value.isNumber() ? value.decimalValue().stripTrailingZeros().toPlainString() : value.toString();
        return text.length() > 120 ? text.substring(0, 117) + "..." : text;
    }

    /** What applying would add, remove or change, keyed by step and connection id (at most 30 lines). */
    static List<Change> changes(ObjectNode base, ObjectNode candidate) {
        List<Change> out = new ArrayList<>();
        if (!base.path("name").equals(candidate.path("name"))) {
            out.add(new Change("changed", "Workflow name: " + show(base.get("name")) + " to " + show(candidate.get("name"))));
        }
        listChanges(base.path("nodes"), candidate.path("nodes"), "Step", out);
        listChanges(base.path("transitions"), candidate.path("transitions"), "Connection", out);
        Map<String, JsonNode> before = byId(base.path("nodes"));
        Map<String, JsonNode> after = byId(candidate.path("nodes"));
        List<String> keptOrderBefore = before.keySet().stream().filter(after::containsKey).toList();
        List<String> keptOrderAfter = after.keySet().stream().filter(before::containsKey).toList();
        if (!keptOrderBefore.equals(keptOrderAfter)) {
            out.add(new Change("changed", "Order of the steps"));
        }
        JsonNode p0 = base.path("uiLayout").path("positions");
        JsonNode p1 = candidate.path("uiLayout").path("positions");
        int moved = 0;
        for (String id : after.keySet()) {
            if (before.containsKey(id) && p0.has(id) && p1.has(id) && !samePosition(p0.get(id), p1.get(id))) {
                moved++;
            }
        }
        if (moved > 0) {
            out.add(new Change("changed", "Canvas position of " + moved + (moved == 1 ? " step" : " steps")));
        }
        return out.size() > MAX_CHANGES ? List.copyOf(out.subList(0, MAX_CHANGES)) : List.copyOf(out);
    }

    private static boolean samePosition(JsonNode a, JsonNode b) {
        return sameNumber(a.get("x"), b.get("x")) && sameNumber(a.get("y"), b.get("y"));
    }

    private static boolean sameNumber(JsonNode a, JsonNode b) {
        return a != null && b != null && a.isNumber() && b.isNumber()
                && a.decimalValue().compareTo(b.decimalValue()) == 0;
    }

    private static void listChanges(JsonNode before, JsonNode after, String what, List<Change> out) {
        Map<String, JsonNode> b = byId(before);
        Map<String, JsonNode> a = byId(after);
        for (Map.Entry<String, JsonNode> e : a.entrySet()) {
            JsonNode old = b.get(e.getKey());
            if (old == null) {
                out.add(new Change("added", what + " '" + e.getKey() + "'" + label(e.getValue())));
            } else if (!semanticallyEqual(old, e.getValue())) {
                out.add(new Change("changed", what + " '" + e.getKey() + "'" + label(e.getValue())));
            }
        }
        for (Map.Entry<String, JsonNode> e : b.entrySet()) {
            if (!a.containsKey(e.getKey())) {
                out.add(new Change("removed", what + " '" + e.getKey() + "'" + label(e.getValue())));
            }
        }
    }

    private static boolean semanticallyEqual(JsonNode a, JsonNode b) {
        List<Difference> out = new ArrayList<>();
        ObjectNode doc = StrictJson.mapper().createObjectNode();
        compare(a, b, "/x", doc, out);
        return out.isEmpty();
    }

    private static String label(JsonNode item) {
        if (item.has("label")) {
            return " (" + item.get("label").asString() + ")";
        }
        if (item.has("from")) {
            return " (" + item.path("from").asString() + " to " + item.path("to").asString() + ", "
                    + item.path("on").asString() + ")";
        }
        return "";
    }

    private static Map<String, JsonNode> byId(JsonNode list) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        if (list.isArray()) {
            list.forEach(item -> map.putIfAbsent(item.path("id").asString(""), item));
        }
        return map;
    }

    private static Check refused(List<Problem> problems) {
        return new Check(false, List.copyOf(problems), List.of(), List.of(), null, List.of());
    }

    private static String write(JsonNode node) {
        return StrictJson.mapper().writeValueAsString(node);
    }
}
