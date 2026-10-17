package com.orqix.cases;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/** Strict admission boundary for the executable CMMN subset. All other constructs remain inventory-only. */
@Component
public class CmmnSubsetValidator {
    private static final String CMMN = "http://www.omg.org/spec/CMMN/20151109/MODEL";
    private static final String FLOWABLE = "http://flowable.org/cmmn";
    private static final Map<String, Set<String>> CHILDREN = Map.of(
            "definitions", Set.of("case"),
            "case", Set.of("casePlanModel"),
            "casePlanModel", Set.of("planItem", "sentry", "humanTask", "milestone"),
            "planItem", Set.of("entryCriterion"),
            "entryCriterion", Set.of(),
            "sentry", Set.of("planItemOnPart"),
            "planItemOnPart", Set.of("standardEvent"),
            "standardEvent", Set.of(),
            "humanTask", Set.of(),
            "milestone", Set.of());
    private static final Map<String, Set<String>> ATTRIBUTES = Map.of(
            "definitions", Set.of("targetNamespace"),
            "case", Set.of("id", "name"),
            "casePlanModel", Set.of("id", "name"),
            "planItem", Set.of("id", "name", "definitionRef"),
            "entryCriterion", Set.of("id", "sentryRef"),
            "sentry", Set.of("id"),
            "planItemOnPart", Set.of("id", "sourceRef"),
            "standardEvent", Set.of(),
            "humanTask", Set.of("id", "name"),
            "milestone", Set.of("id", "name"));

    public Set<String> validate(String xml, String key) {
        if (xml == null || xml.getBytes(StandardCharsets.UTF_8).length > 65536 || xml.isBlank())
            throw invalid("CMMN source must be 1 to 65536 bytes.");
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            var root = doc.getDocumentElement();
            if (!CMMN.equals(root.getNamespaceURI()) || !"definitions".equals(root.getLocalName()))
                throw invalid("Expected CMMN 1.1 definitions.");
            Map<String, Element> ids = new HashMap<>();
            Set<String> groups = new HashSet<>();
            walk(root, ids, groups, 0);
            var cases = children(root, "case");
            if (cases.size() != 1 || !key.equals(cases.getFirst().getAttribute("id")))
                throw invalid("Exactly one case with an ID matching the catalog key is required.");
            var plans = children(cases.getFirst(), "casePlanModel");
            if (plans.size() != 1) throw invalid("Exactly one case plan model is required.");
            var plan = plans.getFirst();
            var items = children(plan, "planItem");
            if (items.isEmpty() || items.size() > 30 || groups.isEmpty())
                throw invalid("The case needs 1 to 30 plan items and at least one assigned human task.");
            boolean initialHumanTask = false;
            for (var item : items) {
                var definition = ids.get(item.getAttribute("definitionRef"));
                if (definition == null || !("humanTask".equals(definition.getLocalName()) || "milestone".equals(definition.getLocalName())))
                    throw invalid("Every plan item must reference a human task or milestone in this case.");
                if ("humanTask".equals(definition.getLocalName()) && children(item,"entryCriterion").isEmpty())
                    initialHumanTask = true;
                for (var criterion : children(item, "entryCriterion")) {
                    var sentry = ids.get(criterion.getAttribute("sentryRef"));
                    if (sentry == null || !"sentry".equals(sentry.getLocalName()))
                        throw invalid("An entry criterion references a missing sentry.");
                }
            }
            if (!initialHumanTask) throw invalid("At least one human task must be available when the case starts.");
            for (var sentry : children(plan, "sentry")) {
                if (children(sentry, "planItemOnPart").isEmpty()) throw invalid("Empty sentries are unsupported.");
                for (var onPart : children(sentry, "planItemOnPart")) {
                    var source = ids.get(onPart.getAttribute("sourceRef"));
                    if (source == null || !"planItem".equals(source.getLocalName())
                            || children(onPart, "standardEvent").size() != 1
                            || !"complete".equals(children(onPart, "standardEvent").getFirst().getTextContent().trim()))
                        throw invalid("Only plan-item completion sentries are supported.");
                }
            }
            return Set.copyOf(groups);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid("CMMN XML is invalid or contains prohibited XML features.");
        }
    }

    private static void walk(Element element, Map<String, Element> ids, Set<String> groups, int depth) {
        if (depth > 12 || !CMMN.equals(element.getNamespaceURI()) || !CHILDREN.containsKey(element.getLocalName()))
            throw invalid("Unsupported CMMN element or nesting depth.");
        String kind = element.getLocalName();
        var attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            var attr = attributes.item(i);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI())) continue;
            if (FLOWABLE.equals(attr.getNamespaceURI()) && "candidateGroups".equals(attr.getLocalName()) && "humanTask".equals(kind)) {
                for (String group : attr.getNodeValue().split(",", -1)) {
                    if (!group.matches("[a-z][a-z0-9-]{1,62}")) throw invalid("Invalid candidate group key.");
                    groups.add(group);
                }
            } else if (attr.getNamespaceURI() == null && ATTRIBUTES.get(kind).contains(attr.getNodeName())) {
                // Allowlisted standard attribute.
            } else throw invalid("Unsupported CMMN attribute: " + attr.getNodeName());
        }
        String id = element.getAttribute("id");
        if (!id.isEmpty() && ids.putIfAbsent(id, element) != null) throw invalid("Duplicate CMMN ID: " + id);
        if ((kind.equals("planItem") || kind.equals("humanTask") || kind.equals("sentry") || kind.equals("case")) && id.isEmpty())
            throw invalid("A required CMMN ID is missing.");
        if (kind.equals("humanTask") && !element.hasAttributeNS(FLOWABLE, "candidateGroups"))
            throw invalid("Every human task needs candidate groups.");
        if (kind.equals("standardEvent")) {
            if (element.getTextContent().length() > 20) throw invalid("Invalid standard event.");
        } else if (element.getTextContent().strip().length() > 0 && children(element).isEmpty())
            throw invalid("Unexpected text in CMMN model.");
        for (var child : children(element)) {
            if (!CHILDREN.get(kind).contains(child.getLocalName())) throw invalid("Unsupported CMMN element: " + child.getLocalName());
            walk(child, ids, groups, depth + 1);
        }
    }

    private static java.util.List<Element> children(Element parent, String name) {
        return children(parent).stream().filter(e -> name.equals(e.getLocalName())).toList();
    }
    private static java.util.List<Element> children(Element parent) {
        var result = new java.util.ArrayList<Element>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element) result.add(element);
            else if (node.getNodeType() == Node.TEXT_NODE && !node.getTextContent().isBlank() && !"standardEvent".equals(parent.getLocalName()))
                throw invalid("Unexpected CMMN text.");
        }
        return result;
    }
    private static ApiException invalid(String message) { return new ApiException(ProblemCode.WORKFLOW_INVALID, message); }
}
