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
    private static final Set<String> PLAN_CHILDREN = Set.of("planItem", "sentry", "humanTask", "milestone", "stage", "userEventListener");
    private static final Map<String, Set<String>> CHILDREN = Map.ofEntries(
            Map.entry("definitions", Set.of("case")),
            Map.entry("case", Set.of("casePlanModel", "caseRoles")),
            Map.entry("caseRoles", Set.of("role")),
            Map.entry("role", Set.of()),
            Map.entry("userEventListener", Set.of()),
            Map.entry("casePlanModel", PLAN_CHILDREN),
            Map.entry("stage", union(PLAN_CHILDREN,"defaultControl")),
            Map.entry("planItem", Set.of("entryCriterion", "exitCriterion", "itemControl")),
            Map.entry("entryCriterion", Set.of()),
            Map.entry("exitCriterion", Set.of()),
            Map.entry("sentry", Set.of("planItemOnPart")),
            Map.entry("planItemOnPart", Set.of("standardEvent")),
            Map.entry("standardEvent", Set.of()),
            Map.entry("humanTask", Set.of("defaultControl")),
            Map.entry("milestone", Set.of("defaultControl")),
            Map.entry("defaultControl", Set.of("repetitionRule", "manualActivationRule", "requiredRule")),
            Map.entry("itemControl", Set.of("repetitionRule", "manualActivationRule", "requiredRule")),
            Map.entry("repetitionRule", Set.of("condition")),
            Map.entry("manualActivationRule", Set.of("condition")),
            Map.entry("requiredRule", Set.of("condition")),
            Map.entry("condition", Set.of()));
    private static final Map<String, Set<String>> ATTRIBUTES = Map.ofEntries(
            Map.entry("definitions", Set.of("targetNamespace")),
            Map.entry("case", Set.of("id", "name")),
            Map.entry("caseRoles", Set.of()),
            Map.entry("role", Set.of("id", "name")),
            Map.entry("userEventListener", Set.of("id", "name", "authorizedRoleRefs")),
            Map.entry("casePlanModel", Set.of("id", "name", "autoComplete")),
            Map.entry("stage", Set.of("id", "name", "autoComplete")),
            Map.entry("planItem", Set.of("id", "name", "definitionRef")),
            Map.entry("entryCriterion", Set.of("id", "sentryRef")),
            Map.entry("exitCriterion", Set.of("id", "sentryRef")),
            Map.entry("sentry", Set.of("id")),
            Map.entry("planItemOnPart", Set.of("id", "sourceRef")),
            Map.entry("standardEvent", Set.of()),
            Map.entry("humanTask", Set.of("id", "name")),
            Map.entry("milestone", Set.of("id", "name")),
            Map.entry("defaultControl", Set.of("id")),
            Map.entry("itemControl", Set.of("id")),
            Map.entry("repetitionRule", Set.of("id")),
            Map.entry("manualActivationRule", Set.of("id")),
            Map.entry("requiredRule", Set.of("id")),
            Map.entry("condition", Set.of()));

    public Set<String> validate(String xml, String key) {
        if (xml == null || xml.getBytes(StandardCharsets.UTF_8).length > 65536 || xml.isBlank())
            throw invalid("CMMN source must be 1 to 65536 bytes.");
        try {
            var doc = parse(xml);
            var root = doc.getDocumentElement();
            if (!CMMN.equals(root.getNamespaceURI()) || !"definitions".equals(root.getLocalName()))
                throw invalid("Expected CMMN 1.1 definitions.");
            Map<String, Element> ids = new HashMap<>();
            Set<String> groups = new HashSet<>();
            walk(root, ids, groups, 0);
            var cases = children(root, "case");
            if (cases.size() != 1 || !key.equals(cases.getFirst().getAttribute("id")))
                throw invalid("Exactly one case with an ID matching the catalog key is required.");
            var roles = children(cases.getFirst(), "caseRoles");
            if (roles.size() > 1) throw invalid("A case may contain only one caseRoles element.");
            for (var listener : ids.values()) if ("userEventListener".equals(listener.getLocalName())) {
                var refs = listener.getAttribute("authorizedRoleRefs").strip();
                if (refs.isEmpty() || refs.split("\\s+").length > 10) throw invalid("Every user event needs 1 to 10 authorized case roles.");
                for (var ref : refs.split("\\s+")) {
                    var role = ids.get(ref);
                    if (role == null || !"role".equals(role.getLocalName()) || roles.isEmpty() || role.getParentNode() != roles.getFirst())
                        throw invalid("User event authorizedRoleRefs must refer to roles in this case.");
                    groups.add(role.getAttribute("name"));
                }
            }
            var plans = children(cases.getFirst(), "casePlanModel");
            if (plans.size() != 1) throw invalid("Exactly one case plan model is required.");
            long count = ids.values().stream().filter(e -> "planItem".equals(e.getLocalName())).count();
            if (count < 1 || count > 30 || groups.isEmpty())
                throw invalid("The case needs 1 to 30 plan items and at least one assigned human task or authorized user event.");
            if (!validatePlan(plans.getFirst(), ids, 0))
                throw invalid("At least one human task or user event must be available when the case starts.");
            return Set.copyOf(groups);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid("CMMN XML is invalid or contains prohibited XML features.");
        }
    }

    private static Set<String> union(Set<String> values,String extra) {
        var out=new HashSet<>(values); out.add(extra); return Set.copyOf(out);
    }

    private static org.w3c.dom.Document parse(String xml) throws Exception {
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
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    /** The source was admitted and pinned at publication; parsing still prohibits external XML resolution. */
    public Map<String,Set<String>> humanTaskGroups(String xml) {
        try {
            var out=new HashMap<String,Set<String>>();
            var tasks=parse(xml).getElementsByTagNameNS(CMMN,"humanTask");
            for(int index=0;index<tasks.getLength();index++) {
                var task=(Element)tasks.item(index);
                out.put(task.getAttribute("id"),Set.copyOf(java.util.List.of(task.getAttributeNS(FLOWABLE,"candidateGroups").split(","))));
            }
            return Map.copyOf(out);
        } catch(Exception ex) { throw invalid("The pinned case definition cannot be read."); }
    }

    public Map<String,Set<String>> userEventGroups(String xml) {
        try {
            var document=parse(xml); var roles=new HashMap<String,String>();
            var roleNodes=document.getElementsByTagNameNS(CMMN,"role");
            for(int i=0;i<roleNodes.getLength();i++) {
                var role=(Element)roleNodes.item(i); roles.put(role.getAttribute("id"),role.getAttribute("name"));
            }
            var result=new HashMap<String,Set<String>>();
            var listeners=document.getElementsByTagNameNS(CMMN,"userEventListener");
            for(int i=0;i<listeners.getLength();i++) {
                var listener=(Element)listeners.item(i);var allowed=new HashSet<String>();
                for(var ref:listener.getAttribute("authorizedRoleRefs").strip().split("\\s+")) allowed.add(roles.get(ref));
                result.put(listener.getAttribute("id"),Set.copyOf(allowed));
            }
            return Map.copyOf(result);
        } catch(Exception ex) { throw invalid("The pinned user event roles cannot be read."); }
    }

    public Set<String> repeatedPlanItems(String xml) {
        try {
            var document=parse(xml);var definitions=new HashMap<String,Element>();
            var elements=document.getElementsByTagNameNS(CMMN,"*");
            for(int i=0;i<elements.getLength();i++) {var element=(Element)elements.item(i);if(element.hasAttribute("id")) definitions.put(element.getAttribute("id"),element);}
            var result=new HashSet<String>();
            for(var element:definitions.values()) if("planItem".equals(element.getLocalName())) {
                var controls=new java.util.ArrayList<>(children(element,"itemControl"));
                var definition=definitions.get(element.getAttribute("definitionRef"));
                if(definition!=null) controls.addAll(children(definition,"defaultControl"));
                if(controls.stream().anyMatch(control->!children(control,"repetitionRule").isEmpty())) result.add(element.getAttribute("id"));
            }
            return Set.copyOf(result);
        } catch(Exception ex) {throw invalid("The pinned repetition rules cannot be read.");}
    }

    public Set<String> requiredPlanItems(String xml) {
        try {
            var document=parse(xml); var byId=new HashMap<String,Element>();
            var elements=document.getElementsByTagNameNS(CMMN,"*");
            for(int index=0;index<elements.getLength();index++) {
                var element=(Element)elements.item(index);
                if(element.hasAttribute("id")) byId.put(element.getAttribute("id"),element);
            }
            var required=new HashSet<String>();
            for(var element:byId.values()) {
                if(!"planItem".equals(element.getLocalName())) continue;
                Element rule=null;
                for(var control:children(element,"itemControl")) {
                    var rules=children(control,"requiredRule"); if(!rules.isEmpty()) rule=rules.getFirst();
                }
                if(rule==null) {
                    var definition=byId.get(element.getAttribute("definitionRef"));
                    if(definition!=null) for(var control:children(definition,"defaultControl")) {
                        var rules=children(control,"requiredRule"); if(!rules.isEmpty()) rule=rules.getFirst();
                    }
                }
                if(rule!=null && (children(rule,"condition").isEmpty()
                        || !"${false}".equals(children(rule,"condition").getFirst().getTextContent().strip())))
                    required.add(element.getAttribute("id"));
            }
            return Set.copyOf(required);
        } catch(Exception ex) { throw invalid("The pinned case controls cannot be read."); }
    }

    /** CMMN stage scopes are lexical: definitions, sentries and event sources must belong to the same plan. */
    private static boolean validatePlan(Element plan, Map<String, Element> ids, int depth) {
        if (depth > 4) throw invalid("At most four nested stages are supported.");
        if (plan.hasAttribute("autoComplete") && !Set.of("true", "false").contains(plan.getAttribute("autoComplete")))
            throw invalid("Stage autoComplete must be a literal boolean.");
        if(plan.getElementsByTagNameNS(CMMN,"repetitionRule").getLength()>0 && !"false".equals(plan.getAttribute("autoComplete")))
            throw invalid("Stages containing repeated work require explicit autoComplete=false.");
        var items = children(plan, "planItem");
        if (items.isEmpty()) throw invalid("Every stage needs at least one plan item.");
        boolean initialHuman = false;
        Set<String> used = new HashSet<>();
        for (var stage : children(plan, "stage")) {
            if (stage.hasAttribute("autoComplete") && !Set.of("true", "false").contains(stage.getAttribute("autoComplete")))
                throw invalid("Stage autoComplete must be a literal boolean.");
        }
        for (var item : items) {
            var definition = ids.get(item.getAttribute("definitionRef"));
            if (definition == null || definition.getParentNode() != plan
                    || !Set.of("humanTask", "milestone", "stage", "userEventListener").contains(definition.getLocalName()))
                throw invalid("Every plan item must reference a human task, user event, milestone or stage defined in its own stage.");
            if (!used.add(definition.getAttribute("id")))
                throw invalid("Each task, milestone or stage definition may be instantiated only once in this subset.");
            if ("milestone".equals(definition.getLocalName())) {
                for (var control : children(item,"itemControl"))
                    if (!children(control,"manualActivationRule").isEmpty()) throw invalid("Milestones cannot be manually activated.");
            }
            if ("userEventListener".equals(definition.getLocalName()) && (!children(item,"entryCriterion").isEmpty()
                    || !children(item,"exitCriterion").isEmpty() || !children(item,"itemControl").isEmpty()))
                throw invalid("User event listeners cannot have criteria, required/manual controls or repetition.");
            var controls=new java.util.ArrayList<>(children(item,"itemControl"));
            controls.addAll(children(definition,"defaultControl"));
            if(controls.stream().anyMatch(control->!children(control,"repetitionRule").isEmpty())
                    && (!"humanTask".equals(definition.getLocalName()) || !children(item,"entryCriterion").isEmpty() || !children(item,"exitCriterion").isEmpty()))
                throw invalid("Repetition currently supports only sequential human tasks without entry or exit criteria.");
            boolean stageHuman = "stage".equals(definition.getLocalName()) && validatePlan(definition, ids, depth + 1);
            if (children(item, "entryCriterion").isEmpty() && ("humanTask".equals(definition.getLocalName()) || "userEventListener".equals(definition.getLocalName()) || stageHuman))
                initialHuman = true;
            for (var criterion : children(item)) {
                if ("itemControl".equals(criterion.getLocalName())) continue;
                if ("milestone".equals(definition.getLocalName()) && "exitCriterion".equals(criterion.getLocalName()))
                    throw invalid("A milestone cannot have an exit criterion.");
                var sentry = ids.get(criterion.getAttribute("sentryRef"));
                if (sentry == null || sentry.getParentNode() != plan || !"sentry".equals(sentry.getLocalName()))
                    throw invalid("A criterion must reference a sentry in its own stage.");
            }
        }
        for (var definition : children(plan)) {
            if (Set.of("humanTask", "milestone", "stage", "userEventListener").contains(definition.getLocalName())
                    && !used.contains(definition.getAttribute("id")))
                throw invalid("Every task, milestone or stage definition needs a plan item in its own stage.");
        }
        for (var sentry : children(plan, "sentry")) {
            if (children(sentry, "planItemOnPart").isEmpty()) throw invalid("Empty sentries are unsupported.");
            for (var onPart : children(sentry, "planItemOnPart")) {
                var source = ids.get(onPart.getAttribute("sourceRef"));
                var events = children(onPart, "standardEvent");
                if (source == null || source.getParentNode() != plan || !"planItem".equals(source.getLocalName()) || events.size() != 1)
                    throw invalid("A sentry event must reference a plan item in its own stage.");
                var sourceDefinition = ids.get(source.getAttribute("definitionRef"));
                String expected = sourceDefinition != null && Set.of("milestone","userEventListener").contains(sourceDefinition.getLocalName()) ? "occur" : "complete";
                if (!expected.equals(events.getFirst().getTextContent().trim()))
                    throw invalid("Sentries support human/stage completion or milestone/user event occurrence events.");
            }
        }
        return initialHuman;
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
        if ((kind.equals("planItem") || kind.equals("humanTask") || kind.equals("sentry") || kind.equals("case") || kind.equals("stage") || kind.equals("milestone") || kind.equals("casePlanModel") || kind.equals("role") || kind.equals("userEventListener")) && id.isEmpty())
            throw invalid("A required CMMN ID is missing.");
        if (kind.equals("role") && !element.getAttribute("name").matches("[a-z][a-z0-9-]{1,62}"))
            throw invalid("Case role names must identify workspace group keys.");
        if (kind.equals("humanTask") && !element.hasAttributeNS(FLOWABLE, "candidateGroups"))
            throw invalid("Every human task needs candidate groups.");
        if (kind.equals("planItem")) {
            boolean criterion=false;
            for(var child:children(element)) {
                if(Set.of("entryCriterion","exitCriterion").contains(child.getLocalName())) criterion=true;
                else if(criterion) throw invalid("Plan-item controls must precede criteria in CMMN XML.");
            }
        }
        if (Set.of("defaultControl","itemControl").contains(kind)) {
            for (String rule : Set.of("repetitionRule","manualActivationRule","requiredRule"))
                if (children(element,rule).size()>1) throw invalid("An item control may define each rule only once.");
            int previous=-1;
            for(var rule : children(element)) {
                int order=java.util.List.of("repetitionRule","requiredRule","manualActivationRule").indexOf(rule.getLocalName());
                if(order<previous) throw invalid("CMMN controls must order repetition, required, then manual activation rules.");
                previous=order;
            }
            Element controlled = (Element) element.getParentNode();
            if ("milestone".equals(controlled.getLocalName()) && !children(element,"manualActivationRule").isEmpty())
                throw invalid("Milestones cannot be manually activated.");
        }
        if (Set.of("planItem","humanTask","stage","milestone").contains(kind)
                && (children(element,"itemControl").size()>1 || children(element,"defaultControl").size()>1))
            throw invalid("A plan item or definition may have only one control.");
        if (Set.of("repetitionRule","manualActivationRule","requiredRule").contains(kind) && children(element,"condition").size()>1)
            throw invalid("An item rule may have at most one condition.");
        if (kind.equals("repetitionRule") && children(element,"condition").size()!=1)
            throw invalid("Repetition needs an explicit finite counter condition.");
        if (kind.equals("condition") && "repetitionRule".equals(element.getParentNode().getLocalName())) {
            if(!element.getTextContent().strip().matches("\\$\\{repetitionCounter < (?:[2-9]|[12][0-9]|30)\\}"))
                throw invalid("Use a finite repetitionCounter < N condition with N from 2 to 30.");
        } else if (kind.equals("condition")) {
            if (!Set.of("${true}","${false}").contains(element.getTextContent().strip()))
                throw invalid("Item controls currently support only constant boolean conditions.");
        } else if (kind.equals("standardEvent")) {
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
            else if (node.getNodeType() == Node.TEXT_NODE && !node.getTextContent().isBlank() && !Set.of("standardEvent","condition").contains(parent.getLocalName()))
                throw invalid("Unexpected CMMN text.");
        }
        return result;
    }
    private static ApiException invalid(String message) { return new ApiException(ProblemCode.WORKFLOW_INVALID, message); }
}
