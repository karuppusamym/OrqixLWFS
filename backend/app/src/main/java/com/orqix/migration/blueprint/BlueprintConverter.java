package com.orqix.migration.blueprint;

import java.util.*;
import com.orqix.migration.camunda.*;
import com.orqix.migration.blueprint.Transcription.*;
import com.orqix.shared.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Converts the admitted subset of a reviewed transcription into a canonical definition (docs/build/S91), through the same
 * BPMN adapter and converter the XPDL import uses: approval steps become approvals whose role is mapped to a workspace
 * group through {@code groupMappings}, decisions become exclusive routes on request-field comparisons, outcomes become
 * ends, fields become the typed request form and elapsed SLAs become approval due times. The caller has already checked
 * the transcription has no open blocker; the result still passes the normal compiler before any draft exists.
 */
public final class BlueprintConverter {
    public record Result(ObjectNode definition, Map<String, String> sourceMap, Report.CompatibilityReport converterReport) { }

    private BlueprintConverter() { }

    public static Result convert(Transcription t, JsonNode request, Json json) {
        Map<String, Element> active = new LinkedHashMap<>();
        for (Element e : t.elements()) if (!e.meta().excluded()) active.put(e.id(), e);
        String process = t.caseType() == null ? "blueprint" : Identifiers.sanitize(t.caseType().name(), false);
        StringBuilder xml = new StringBuilder("<definitions xmlns='" + Namespaces.BPMN + "' xmlns:c='" + Namespaces.CAMUNDA7
                + "' targetNamespace='urn:orqix:pega-blueprint'><process isExecutable='true' id='").append(esc(process)).append("' name='")
                .append(esc(t.caseType() == null ? "Blueprint" : t.caseType().name())).append("'>");
        xml.append("<startEvent id='blueprint-start' name='Start'/>");
        flow(xml, "blueprint-start-flow", "blueprint-start", t.start(), null, null);
        ObjectNode effective = json.object();
        effective.put("workflowKey", request.path("workflowKey").asString("blueprint"));
        effective.put("name", request.path("name").asString("Blueprint"));
        effective.set("groupMappings", request.path("groupMappings").isObject() ? request.path("groupMappings").deepCopy() : json.object());
        ObjectNode routes = effective.putObject("approvalRoutes");
        for (Step s : t.steps()) {
            if (s.meta().excluded()) continue;
            xml.append("<userTask id='").append(esc(s.id())).append("' name='").append(esc(s.name())).append("' c:candidateGroups='").append(esc(s.role())).append("'/>");
            flow(xml, s.id() + "-approved", s.id(), s.next(), "Approved", null);
            flow(xml, s.id() + "-rejected", s.id(), s.onReject(), "Rejected", null);
            routes.putObject(s.id()).put("approvedFlowId", s.id() + "-approved").put("rejectedFlowId", s.id() + "-rejected");
        }
        Map<String, Field> fields = new HashMap<>();
        for (Field f : t.fields()) if (!f.meta().excluded()) fields.put(f.id(), f);
        for (Decision d : t.decisions()) {
            if (d.meta().excluded()) continue;
            xml.append("<exclusiveGateway id='").append(esc(d.id())).append("' name='").append(esc(d.name())).append("' default='").append(esc(d.id() + "-otherwise")).append("'/>");
            for (int i = 0; i < d.rules().size(); i++) {
                Rule r = d.rules().get(i);
                Field f = fields.get(r.field());
                String literal = "string".equals(f.type()) ? "'" + r.value() + "'" : r.value();
                flow(xml, d.id() + "-rule-" + (i + 1), d.id(), r.target(), d.name() + " " + (i + 1), "${" + r.field() + " " + r.operator() + " " + literal + "}");
            }
            flow(xml, d.id() + "-otherwise", d.id(), d.otherwise(), "Otherwise", null);
        }
        for (Outcome o : t.outcomes()) if (!o.meta().excluded()) xml.append("<endEvent id='").append(esc(o.id())).append("' name='").append(esc(o.name())).append("'/>");
        xml.append("</process></definitions>");

        ObjectNode schema = effective.putObject("inputSchema");
        schema.put("type", "object"); schema.put("additionalProperties", false);
        var required = schema.putArray("required");
        ObjectNode properties = schema.putObject("properties");
        for (Field f : fields.values().stream().sorted(Comparator.comparingInt(t.fields()::indexOf)).toList()) {
            ObjectNode p = properties.putObject(f.id());
            switch (f.type()) {
                case "date" -> { p.put("type", "string"); p.put("format", "date"); }
                case "string" -> { p.put("type", "string"); p.put("maxLength", 1000); if (f.required()) p.put("minLength", 1); }
                default -> p.put("type", f.type());
            }
            p.put("title", f.name().length() > 120 ? f.name().substring(0, 120) : f.name());
            if (f.required()) required.add(f.id());
        }
        effective.put("acknowledgeInfo", request.path("acknowledgeInfo").asBoolean(false));
        effective.put("acknowledgeReview", request.path("acknowledgeReview").asBoolean(false));
        Analysis analysis = Analyzer.analyze(xml.toString(), Limits.DEFAULT);
        var converted = Converter.convert(analysis, effective, json, Map.of(), Map.of(), Map.of());
        if (converted.definition() == null) return new Result(null, converted.sourceMap() == null ? Map.of() : converted.sourceMap(), converted.report());
        Map<String, String> map = new LinkedHashMap<>(converted.sourceMap());
        // Post-processing from the transcription: outcome kinds and elapsed SLAs on approvals.
        Map<String, Sla> slaByStep = new HashMap<>();
        for (Sla s : t.slas()) if (!s.meta().excluded() && s.step() != null) slaByStep.put(s.step(), s);
        for (JsonNode node : converted.definition().path("nodes")) {
            String source = map.entrySet().stream().filter(e -> e.getValue().equals(node.path("id").asString())).map(Map.Entry::getKey).findFirst().orElse(null);
            if (source == null) continue;
            ObjectNode config = (ObjectNode) node.path("config");
            if (active.get(source) instanceof Outcome o && o.type() != null) config.put("outcomeKind", o.type());
            Sla sla = slaByStep.get(source);
            if (sla != null && "approval".equals(node.path("type").asString())) {
                long minutes = BlueprintFindings.minutes(sla);
                config.remove("dueAfterHours"); config.remove("dueAfterMinutes");
                if (minutes % 60 == 0) config.put("dueAfterHours", (int) (minutes / 60)); else config.put("dueAfterMinutes", (int) minutes);
                map.put(sla.id(), node.path("id").asString());
            }
        }
        for (Field f : fields.values()) map.put(f.id(), "/input/" + f.id());
        return new Result(converted.definition(), map, converted.report());
    }

    private static void flow(StringBuilder xml, String id, String from, String to, String name, String condition) {
        xml.append("<sequenceFlow id='").append(esc(id)).append("' sourceRef='").append(esc(from)).append("' targetRef='").append(esc(to)).append("'");
        if (name != null) xml.append(" name='").append(esc(name)).append("'");
        xml.append(">");
        if (condition != null) xml.append("<conditionExpression>").append(esc(condition)).append("</conditionExpression>");
        xml.append("</sequenceFlow>");
    }

    private static String esc(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;").replace("\"", "&quot;");
    }
}
