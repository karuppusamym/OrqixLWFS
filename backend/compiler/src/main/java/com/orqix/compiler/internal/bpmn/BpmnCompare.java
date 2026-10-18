package com.orqix.compiler.internal.bpmn;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.orqix.compiler.internal.bpmn.BpmnXml.El;

/**
 * Compares the BPMN a person submitted with the BPMN the compiler generates from the document that was read out of it
 * (UI03, docs/build/S56). Both trees are flattened into {@code path -> value} entries (element presence, every
 * attribute, trimmed text); elements directly under the process and the definitions are keyed by id, diagram shapes
 * and edges by the element they show, {@code orqix:input}/{@code orqix:result} by name (they are maps), and all other
 * children by position. Namespace prefixes and attribute order do not matter; numbers that are equal as decimals are
 * equal.
 * <p>
 * Every entry of the submitted text must be produced again: an extra entry is something the document cannot hold, a
 * missing or different entry is something the document always writes differently. Two kinds of entries are computed
 * rather than read and are therefore treated differently:
 * <ul>
 * <li><b>recomputed</b> (hashes, child release pins, the terminate marker and diagram lines/helper shapes/sizes): they
 * change with the document or the workspace, so a stale value is not an edit. Differences are reported as notes.</li>
 * <li><b>from other sections</b> (connection version, retry cycle and attempts, decision table version/resource/field):
 * they are owned by sections the BPMN does not carry, so a different value is refused with the value to use.</li>
 * </ul>
 */
public final class BpmnCompare {

    /** A refusal ({@code notRepresentable} or {@code otherSection}) with the element id when known. */
    public record Difference(String kind, String elementId, String message) {
    }

    /** Problems refuse the text; notes only explain what will be recomputed. */
    public record Result(List<Difference> problems, List<String> notes) {
    }

    public static final String NOT_REPRESENTABLE = "notRepresentable";
    public static final String OTHER_SECTION = "otherSection";

    private static final String PROCESS = "/bpmn:process[0]";
    private static final String PLANE = "/bpmndi:BPMNDiagram[0]/bpmndi:BPMNPlane[0]";
    private static final Pattern RECOMPUTED = Pattern.compile(String.join("|",
            "^" + Pattern.quote(PROCESS) + "/bpmn:extensionElements\\[0\\]/orqix:release\\[0\\]/@(compilerVersion|semanticHash)$",
            "^" + Pattern.quote(PROCESS) + "/#[^/]+/bpmn:extensionElements\\[0\\]/orqix:child\\[0\\]/@(releaseNumber|semanticHash)$",
            "^" + Pattern.quote(PROCESS) + "/#[^/]+/bpmn:terminateEventDefinition\\[0\\]$",
            "^" + Pattern.quote(PROCESS) + "/#[^/]+__[^/]+/@name$",
            "^" + Pattern.quote(PLANE) + "/edge:.*$",
            "^" + Pattern.quote(PLANE) + "/shape:[^/]*__.*$",
            "^" + Pattern.quote(PLANE) + "/shape:[^/]+/dc:Bounds\\[0\\]/@(width|height)$",
            "^" + Pattern.quote(PLANE) + "/shape:[^/]+/@id$"));
    private static final Pattern OTHER = Pattern.compile(String.join("|",
            "/orqix:http\\[0\\]/@connectionVersion$",
            "/flowable:failedJobRetryTimeCycle\\[0\\]/\\$text$",
            "/orqix:external\\[0\\]/@(maxAttempts|retryDelaySeconds)$",
            "/orqix:decision\\[0\\]/@(version|resource|fieldRef)$"));
    private static final int MAX_PROBLEMS = 60;

    private BpmnCompare() {
    }

    public static Result compare(El submitted, El regenerated) {
        Map<String, String> s = flatten(submitted);
        Map<String, String> r = flatten(regenerated);
        Set<String> keys = new LinkedHashSet<>(r.keySet());
        keys.addAll(s.keySet());
        List<Difference> problems = new ArrayList<>();
        Map<String, Integer> recomputed = new TreeMap<>();
        for (String key : keys) {
            String sv = s.get(key);
            String rv = r.get(key);
            if (same(sv, rv)) {
                continue;
            }
            String element = elementOf(key);
            if (RECOMPUTED.matcher(key).find()) {
                recomputed.merge(category(key), 1, Integer::sum);
                continue;
            }
            if (problems.size() >= MAX_PROBLEMS) {
                continue;
            }
            String where = describe(key);
            if (OTHER.matcher(key).find()) {
                problems.add(new Difference(OTHER_SECTION, element, where + " comes from " + section(key)
                        + " and cannot be changed in BPMN: it must be " + quote(rv) + (sv == null ? " (it is missing)."
                        : ", not " + quote(sv) + ".")));
            } else if (rv == null) {
                problems.add(new Difference(NOT_REPRESENTABLE, element, where + (sv.equals("element") ? " is" : " = "
                        + quote(sv) + " is") + " not part of the workflow document and would be lost."));
            } else if (sv == null) {
                problems.add(new Difference(NOT_REPRESENTABLE, element, where + " is missing; the workflow document "
                        + "always writes " + (rv.equals("element") ? "it" : quote(rv)) + " here."));
            } else {
                problems.add(new Difference(NOT_REPRESENTABLE, element, where + " is " + quote(sv)
                        + ", but the workflow document can only express " + quote(rv) + " here."));
            }
        }
        List<String> notes = new ArrayList<>();
        recomputed.forEach((category, count) -> notes.add(category + " (" + count + (count == 1 ? " value" : " values")
                + ")."));
        return new Result(List.copyOf(problems), List.copyOf(notes));
    }

    /** Flattens an element tree; see the class comment for the keys. */
    static Map<String, String> flatten(El root) {
        Map<String, String> out = new LinkedHashMap<>();
        walk(root, "", out);
        return out;
    }

    private static void walk(El el, String path, Map<String, String> out) {
        put(out, path.isEmpty() ? "/" : path, "element");
        for (Map.Entry<String, String> a : el.attributes().entrySet()) {
            put(out, path + "/@" + BpmnXml.attributeLabel(a.getKey()), a.getValue());
        }
        if (!el.text().isEmpty()) {
            put(out, path + "/$text", el.text());
        }
        boolean keyedById = el.is(BpmnGenerator.BPMN_NS, "process") || el.is(BpmnGenerator.BPMN_NS, "definitions");
        Map<String, Integer> index = new HashMap<>();
        for (El child : el.children()) {
            String label = child.label();
            String segment;
            if (keyedById && child.attr("id") != null && !child.is(BpmnGenerator.BPMN_NS, "process")
                    && !child.is(BpmnGenerator.BPMNDI_NS, "BPMNDiagram")) {
                segment = "#" + child.attr("id");
                put(out, path + "/" + segment + "/=type", label);
            } else if (child.is(BpmnGenerator.BPMNDI_NS, "BPMNShape") && child.attr("bpmnElement") != null) {
                segment = "shape:" + child.attr("bpmnElement");
            } else if (child.is(BpmnGenerator.BPMNDI_NS, "BPMNEdge") && child.attr("bpmnElement") != null) {
                segment = "edge:" + child.attr("bpmnElement");
            } else if ((child.is(BpmnGenerator.ORQIX_NS, "input") || child.is(BpmnGenerator.ORQIX_NS, "result"))
                    && child.attr("name") != null) {
                segment = label + "[" + child.attr("name") + "]";
            } else {
                int i = index.merge(label, 1, Integer::sum) - 1;
                segment = label + "[" + i + "]";
            }
            String childPath = path + "/" + segment;
            if (out.containsKey(childPath)) {
                childPath = childPath + "~" + out.size();
            }
            walk(child, childPath, out);
        }
    }

    private static void put(Map<String, String> out, String key, String value) {
        out.put(out.containsKey(key) ? key + "~" + out.size() : key, value);
    }

    private static boolean same(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.equals(b)) {
            return true;
        }
        try {
            return new BigDecimal(a.trim()).compareTo(new BigDecimal(b.trim())) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String elementOf(String key) {
        int hash = key.lastIndexOf("/#");
        if (hash >= 0) {
            int end = key.indexOf('/', hash + 2);
            return key.substring(hash + 2, end < 0 ? key.length() : end);
        }
        int shape = key.indexOf("/shape:");
        int edge = key.indexOf("/edge:");
        int at = Math.max(shape, edge);
        if (at >= 0) {
            int start = key.indexOf(':', at) + 1;
            int end = key.indexOf('/', start);
            return key.substring(start, end < 0 ? key.length() : end);
        }
        return null;
    }

    /** "Element 'procure' > orqix:http > attribute connectionVersion" style text. */
    static String describe(String key) {
        String rest = key;
        StringBuilder text = new StringBuilder();
        int hash = key.lastIndexOf("/#");
        if (hash >= 0) {
            int end = key.indexOf('/', hash + 2);
            text.append("Element '").append(key, hash + 2, end < 0 ? key.length() : end).append("'");
            rest = end < 0 ? "" : key.substring(end);
        } else if (key.startsWith(PLANE + "/shape:") || key.startsWith(PLANE + "/edge:")) {
            String tail = key.substring(PLANE.length() + 1);
            int end = tail.indexOf('/');
            String head = end < 0 ? tail : tail.substring(0, end);
            text.append(head.startsWith("shape:") ? "Diagram shape of '" : "Diagram edge of '")
                    .append(head.substring(head.indexOf(':') + 1)).append("'");
            rest = end < 0 ? "" : tail.substring(end);
        } else if (key.startsWith(PROCESS)) {
            text.append("The process");
            rest = key.substring(PROCESS.length());
        } else {
            text.append("The definitions");
        }
        for (String part : rest.split("/")) {
            if (part.isEmpty() || part.equals("bpmn:extensionElements[0]")) {
                continue;
            }
            text.append(" > ");
            if (part.startsWith("@")) {
                text.append("attribute ").append(part.substring(1));
            } else if (part.equals("$text")) {
                text.append("text");
            } else if (part.equals("=type")) {
                text.append("element type");
            } else {
                text.append(part.replaceAll("\\[0\\]$", ""));
            }
        }
        return text.toString();
    }

    private static String category(String key) {
        if (key.contains("orqix:release")) {
            return "The release hash and compiler version are recomputed for the new document";
        }
        if (key.contains("orqix:child")) {
            return "Child workflow release pins are recomputed from the workspace";
        }
        if (key.endsWith("/@name")) {
            return "Names of generated helper elements are recomputed from the step labels";
        }
        if (key.contains("terminateEventDefinition")) {
            return "Terminate markers on rejected/failed ends are recomputed from whether the workflow runs steps in parallel";
        }
        return "Diagram lines, helper shapes and shape sizes are recomputed from the step positions";
    }

    private static String section(String key) {
        if (key.contains("connectionVersion")) {
            return "the workflow's connection dependencies";
        }
        if (key.contains("failedJobRetryTimeCycle") || key.contains("orqix:external")) {
            return "the workflow's execution settings (attempts and retry delay)";
        }
        return "the decision table the step uses";
    }

    private static String quote(String value) {
        if (value == null) {
            return "(nothing)";
        }
        String shown = value.length() > 80 ? value.substring(0, 77) + "..." : value;
        return "'" + shown + "'";
    }
}
