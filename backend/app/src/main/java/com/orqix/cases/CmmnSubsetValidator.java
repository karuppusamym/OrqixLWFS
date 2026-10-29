package com.orqix.cases;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/**
 * Strict admission boundary for the executable CMMN subset. All other constructs remain inventory-only.
 *
 * <p>Two standard constructs have no native support in the pinned engine and are mapped at deployment
 * ({@link #engineXml}): a {@code processTask} that names an Orqix workflow becomes a blocking task that Orqix drives,
 * and a discretionary item of a planning table becomes a plan item released by a hidden planning event. The stored
 * and exported source keeps the standard elements and their IDs.
 */
@Component
public class CmmnSubsetValidator {
    static final String CMMN = "http://www.omg.org/spec/CMMN/20151109/MODEL";
    static final String CMMNDI = "http://www.omg.org/spec/CMMN/20151109/CMMNDI";
    static final String DC = "http://www.omg.org/spec/CMMN/20151109/DC";
    static final String DI = "http://www.omg.org/spec/CMMN/20151109/DI";
    private static final String FLOWABLE = "http://flowable.org/cmmn";
    /** Namespace of the QName in a process externalRef that names a workspace workflow by key (prefix:key). */
    public static final String WORKFLOW_NS = "urn:orqix:workflow";
    /** Suffixes of the elements generated for a discretionary item at deployment. */
    public static final String PLANNER = "__planner";
    private static final Set<String> WORK = Set.of("humanTask", "processTask", "milestone", "stage", "userEventListener");
    private static final Set<String> PLAN_CHILDREN = Set.of("planItem", "sentry", "humanTask", "processTask", "milestone", "stage",
            "userEventListener", "planningTable");
    private static final Map<String, Set<String>> CHILDREN = Map.ofEntries(
            Map.entry("definitions", Set.of("case", "process")),
            Map.entry("process", Set.of()),
            Map.entry("case", Set.of("casePlanModel", "caseRoles")),
            Map.entry("caseRoles", Set.of("role")),
            Map.entry("role", Set.of()),
            Map.entry("userEventListener", Set.of()),
            Map.entry("casePlanModel", PLAN_CHILDREN),
            Map.entry("stage", union(PLAN_CHILDREN,"defaultControl")),
            Map.entry("planItem", Set.of("entryCriterion", "exitCriterion", "itemControl")),
            Map.entry("planningTable", Set.of("discretionaryItem")),
            Map.entry("discretionaryItem", Set.of("itemControl")),
            Map.entry("entryCriterion", Set.of()),
            Map.entry("exitCriterion", Set.of()),
            Map.entry("sentry", Set.of("planItemOnPart")),
            Map.entry("planItemOnPart", Set.of("standardEvent")),
            Map.entry("standardEvent", Set.of()),
            Map.entry("humanTask", Set.of("defaultControl")),
            Map.entry("processTask", Set.of("defaultControl")),
            Map.entry("milestone", Set.of("defaultControl")),
            Map.entry("defaultControl", Set.of("repetitionRule", "manualActivationRule", "requiredRule")),
            Map.entry("itemControl", Set.of("repetitionRule", "manualActivationRule", "requiredRule")),
            Map.entry("repetitionRule", Set.of("condition")),
            Map.entry("manualActivationRule", Set.of("condition")),
            Map.entry("requiredRule", Set.of("condition")),
            Map.entry("condition", Set.of()));
    private static final Map<String, Set<String>> ATTRIBUTES = Map.ofEntries(
            Map.entry("definitions", Set.of("targetNamespace", "id", "name", "exporter", "exporterVersion")),
            Map.entry("process", Set.of("id", "name", "externalRef", "implementationType")),
            Map.entry("case", Set.of("id", "name")),
            Map.entry("caseRoles", Set.of("id")),
            Map.entry("role", Set.of("id", "name")),
            Map.entry("userEventListener", Set.of("id", "name", "authorizedRoleRefs")),
            Map.entry("casePlanModel", Set.of("id", "name", "autoComplete")),
            Map.entry("stage", Set.of("id", "name", "autoComplete")),
            Map.entry("planItem", Set.of("id", "name", "definitionRef")),
            Map.entry("planningTable", Set.of("id")),
            Map.entry("discretionaryItem", Set.of("id", "name", "definitionRef", "authorizedRoleRefs")),
            Map.entry("entryCriterion", Set.of("id", "sentryRef")),
            Map.entry("exitCriterion", Set.of("id", "sentryRef")),
            Map.entry("sentry", Set.of("id")),
            Map.entry("planItemOnPart", Set.of("id", "sourceRef")),
            Map.entry("standardEvent", Set.of()),
            Map.entry("humanTask", Set.of("id", "name")),
            Map.entry("processTask", Set.of("id", "name", "processRef", "isBlocking")),
            Map.entry("milestone", Set.of("id", "name")),
            Map.entry("defaultControl", Set.of("id")),
            Map.entry("itemControl", Set.of("id")),
            Map.entry("repetitionRule", Set.of("id")),
            Map.entry("manualActivationRule", Set.of("id")),
            Map.entry("requiredRule", Set.of("id")),
            Map.entry("condition", Set.of()));

    /** A discretionary item and the hidden planning event that releases it in the engine model. */
    public record Discretionary(String id, String name, String definitionRef, Set<String> groups) {}

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
            List<Element> diagrams = new ArrayList<>();
            for (var child : children(root)) if (CMMNDI.equals(child.getNamespaceURI())) diagrams.add(child);
            walk(root, ids, groups, 0);
            var cases = children(root, "case");
            if (cases.size() != 1 || !key.equals(cases.getFirst().getAttribute("id")))
                throw invalid("Exactly one case with an ID matching the catalog key is required.");
            var roles = children(cases.getFirst(), "caseRoles");
            if (roles.size() > 1) throw invalid("A case may contain only one caseRoles element.");
            for (var element : ids.values()) {
                if (!Set.of("userEventListener","discretionaryItem").contains(element.getLocalName())) continue;
                var refs = element.getAttribute("authorizedRoleRefs").strip();
                String what="userEventListener".equals(element.getLocalName())?"user event":"discretionary item";
                if (refs.isEmpty() || refs.split("\\s+").length > 10) throw invalid("Every "+what+" needs 1 to 10 authorized case roles ("+element.getAttribute("id")+").");
                for (var ref : refs.split("\\s+")) {
                    var role = ids.get(ref);
                    if (role == null || !"role".equals(role.getLocalName()) || roles.isEmpty() || role.getParentNode() != roles.getFirst())
                        throw invalid("authorizedRoleRefs of "+element.getAttribute("id")+" must refer to roles in this case.");
                    groups.add(role.getAttribute("name"));
                }
            }
            for (var process : children(root, "process")) workflowKey(process);
            var plans = children(cases.getFirst(), "casePlanModel");
            if (plans.size() != 1) throw invalid("Exactly one case plan model is required.");
            long count = ids.values().stream().filter(e -> Set.of("planItem","discretionaryItem").contains(e.getLocalName())).count();
            boolean workflows = ids.values().stream().anyMatch(e -> "processTask".equals(e.getLocalName()));
            if (count < 1 || count > 30 || groups.isEmpty() && !workflows)
                throw invalid("The case needs 1 to 30 plan and discretionary items and at least one assigned human task, workflow task or authorized user event.");
            for (var element : List.copyOf(ids.values())) if ("discretionaryItem".equals(element.getLocalName())) {
                String id = element.getAttribute("id");
                for (String generated : List.of(id + PLANNER, id + PLANNER + "_item", id + "__plan_entry", id + "__plan_sentry"))
                    if (ids.containsKey(generated)) throw invalid("ID " + generated + " is reserved for the planning event of " + id + ".");
            }
            if (!validatePlan(plans.getFirst(), ids, 0))
                throw invalid("At least one human task, workflow task or user event must be available when the case starts.");
            if (diagrams.size() > 1) throw invalid("At most one CMMNDI section is supported.");
            for (var diagram : diagrams) validateDiagram(diagram, ids);
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

    private static Document parse(String xml) throws Exception {
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

    private static Map<String, Element> byId(Document document) {
        var out = new HashMap<String, Element>();
        var elements = document.getElementsByTagNameNS(CMMN, "*");
        for (int i = 0; i < elements.getLength(); i++) {
            var element = (Element) elements.item(i);
            if (element.hasAttribute("id")) out.put(element.getAttribute("id"), element);
        }
        return out;
    }

    private static Map<String, String> roleNames(Document document) {
        var roles = new HashMap<String, String>();
        var roleNodes = document.getElementsByTagNameNS(CMMN, "role");
        for (int i = 0; i < roleNodes.getLength(); i++) {
            var role = (Element) roleNodes.item(i); roles.put(role.getAttribute("id"), role.getAttribute("name"));
        }
        return roles;
    }

    private static Set<String> authorizedGroups(Element element, Map<String, String> roles) {
        var allowed = new HashSet<String>();
        for (var ref : element.getAttribute("authorizedRoleRefs").strip().split("\\s+")) allowed.add(roles.get(ref));
        return Set.copyOf(allowed);
    }

    /** The source was admitted and pinned at publication; parsing still prohibits external XML resolution. */
    public Map<String,Set<String>> humanTaskGroups(String xml) {
        try {
            var out=new HashMap<String,Set<String>>();
            var tasks=parse(xml).getElementsByTagNameNS(CMMN,"humanTask");
            for(int index=0;index<tasks.getLength();index++) {
                var task=(Element)tasks.item(index);
                out.put(task.getAttribute("id"),Set.copyOf(List.of(task.getAttributeNS(FLOWABLE,"candidateGroups").split(","))));
            }
            return Map.copyOf(out);
        } catch(Exception ex) { throw invalid("The pinned case definition cannot be read."); }
    }

    /** Groups allowed to fire each user event, including the hidden planning event of every discretionary item. */
    public Map<String,Set<String>> userEventGroups(String xml) {
        try {
            var document=parse(xml); var roles=roleNames(document);
            var result=new HashMap<String,Set<String>>();
            var listeners=document.getElementsByTagNameNS(CMMN,"userEventListener");
            for(int i=0;i<listeners.getLength();i++) {
                var listener=(Element)listeners.item(i);
                result.put(listener.getAttribute("id"),authorizedGroups(listener,roles));
            }
            for(var item:discretionaryItems(xml).values()) result.put(item.id()+PLANNER,item.groups());
            return Map.copyOf(result);
        } catch(ApiException ex) { throw ex; } catch(Exception ex) { throw invalid("The pinned user event roles cannot be read."); }
    }

    /** Discretionary items keyed by the ID of their hidden planning event definition. */
    public Map<String,Discretionary> discretionaryItems(String xml) {
        try {
            var document=parse(xml); var roles=roleNames(document); var ids=byId(document);
            var result=new LinkedHashMap<String,Discretionary>();
            var items=document.getElementsByTagNameNS(CMMN,"discretionaryItem");
            for(int i=0;i<items.getLength();i++) {
                var item=(Element)items.item(i);
                var definition=ids.get(item.getAttribute("definitionRef"));
                String name=item.hasAttribute("name")?item.getAttribute("name"):definition==null?item.getAttribute("id"):definition.getAttribute("name");
                result.put(item.getAttribute("id")+PLANNER,new Discretionary(item.getAttribute("id"),name,item.getAttribute("definitionRef"),authorizedGroups(item,roles)));
            }
            return java.util.Collections.unmodifiableMap(result);
        } catch(Exception ex) { throw invalid("The pinned planning table cannot be read."); }
    }

    /** Workflow key named by every process task, keyed by the process task definition ID. */
    public Map<String,String> workflowTasks(String xml) {
        try {
            var document=parse(xml); var ids=byId(document);
            var result=new LinkedHashMap<String,String>();
            var tasks=document.getElementsByTagNameNS(CMMN,"processTask");
            for(int i=0;i<tasks.getLength();i++) {
                var task=(Element)tasks.item(i);
                var process=ids.get(task.getAttribute("processRef"));
                if(process==null||!"process".equals(process.getLocalName())) throw invalid("Process task "+task.getAttribute("id")+" must reference a process.");
                result.put(task.getAttribute("id"),workflowKey(process));
            }
            return java.util.Collections.unmodifiableMap(result);
        } catch(ApiException ex) { throw ex; } catch(Exception ex) { throw invalid("The pinned workflow tasks cannot be read."); }
    }

    public Set<String> repeatedPlanItems(String xml) {
        try {
            var document=parse(xml);var definitions=byId(document);
            var result=new HashSet<String>();
            for(var element:definitions.values()) if("planItem".equals(element.getLocalName())) {
                var controls=new ArrayList<>(children(element,"itemControl"));
                var definition=definitions.get(element.getAttribute("definitionRef"));
                if(definition!=null) controls.addAll(children(definition,"defaultControl"));
                if(controls.stream().anyMatch(control->!children(control,"repetitionRule").isEmpty())) result.add(element.getAttribute("id"));
            }
            return Set.copyOf(result);
        } catch(Exception ex) {throw invalid("The pinned repetition rules cannot be read.");}
    }

    /** Plan items (and planned discretionary items, which keep their ID in the engine) with a required rule. */
    public Set<String> requiredPlanItems(String xml) {
        try {
            var document=parse(xml); var byId=byId(document);
            var required=new HashSet<String>();
            for(var element:byId.values()) {
                if(!Set.of("planItem","discretionaryItem").contains(element.getLocalName())) continue;
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

    /**
     * The model the engine runs: process tasks become blocking tasks with the same IDs, and each discretionary item
     * becomes a plan item (with the discretionary item's ID) whose entry criterion waits for a hidden planning event.
     * The source must already have been admitted by {@link #validate}.
     */
    public String engineXml(String xml) {
        try {
            var document=parse(xml);
            for(var task:list(document.getElementsByTagNameNS(CMMN,"processTask"))) {
                var replacement=document.createElementNS(CMMN,"task");
                replacement.setAttribute("id",task.getAttribute("id"));
                if(task.hasAttribute("name")) replacement.setAttribute("name",task.getAttribute("name"));
                replacement.setAttribute("isBlocking","true");
                for(var child:children(task)) replacement.appendChild(child);
                task.getParentNode().replaceChild(replacement,task);
            }
            for(var process:list(document.getElementsByTagNameNS(CMMN,"process"))) process.getParentNode().removeChild(process);
            // CMMN 1.1 makes diagram labels optional; the pinned engine's schema requires one in every shape and edge.
            for(String kind:List.of("CMMNShape","CMMNEdge")) for(var shape:list(document.getElementsByTagNameNS(CMMNDI,kind)))
                if(children(shape).stream().noneMatch(child->CMMNDI.equals(child.getNamespaceURI()) && "CMMNLabel".equals(child.getLocalName())))
                    shape.appendChild(document.createElementNS(CMMNDI,"cmmndi:CMMNLabel"));
            var definitions=byId(document);
            for(var table:list(document.getElementsByTagNameNS(CMMN,"planningTable"))) {
                var plan=(Element)table.getParentNode();
                for(var item:children(table,"discretionaryItem")) {
                    String id=item.getAttribute("id");
                    var definition=definitions.get(item.getAttribute("definitionRef"));
                    String name=item.hasAttribute("name")?item.getAttribute("name"):definition.getAttribute("name");
                    var listenerItem=element(document,"planItem","id",id+PLANNER+"_item","name","Plan: "+name,"definitionRef",id+PLANNER);
                    var planned=element(document,"planItem","id",id,"name",name,"definitionRef",item.getAttribute("definitionRef"));
                    for(var control:children(item,"itemControl")) planned.appendChild(control);
                    planned.appendChild(element(document,"entryCriterion","id",id+"__plan_entry","sentryRef",id+"__plan_sentry"));
                    var sentry=element(document,"sentry","id",id+"__plan_sentry");
                    var onPart=element(document,"planItemOnPart","id",id+"__plan_on","sourceRef",id+PLANNER+"_item");
                    var event=document.createElementNS(CMMN,"standardEvent"); event.setTextContent("occur");
                    onPart.appendChild(event); sentry.appendChild(onPart);
                    var listener=element(document,"userEventListener","id",id+PLANNER,"name","Plan: "+name);
                    plannerShapes(document,id);
                    insertBefore(plan,listenerItem,child->!"planItem".equals(child.getLocalName()));
                    insertBefore(plan,planned,child->!"planItem".equals(child.getLocalName()));
                    insertBefore(plan,sentry,child->!Set.of("planItem","sentry").contains(child.getLocalName()));
                    plan.appendChild(listener);
                }
                plan.removeChild(table);
            }
            var transformer=TransformerFactory.newInstance();
            transformer.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            var out=transformer.newTransformer();
            out.setOutputProperty(OutputKeys.ENCODING,"UTF-8");
            var writer=new StringWriter();
            out.transform(new DOMSource(document),new StreamResult(writer));
            return writer.toString();
        } catch(ApiException ex) { throw ex; } catch(Exception ex) { throw invalid("The case model could not be prepared for the engine."); }
    }

    /** The workflow key of a process whose externalRef is a QName in the Orqix workflow namespace. */
    private static String workflowKey(Element process) {
        String ref = process.getAttribute("externalRef").strip();
        int colon = ref.indexOf(':');
        String prefix = colon < 0 ? null : ref.substring(0, colon), key = colon < 0 ? ref : ref.substring(colon + 1);
        if (prefix != null && !prefix.matches("[A-Za-z_][A-Za-z0-9_.-]*")) prefix = "-";
        String namespace = "-".equals(prefix) ? null : process.lookupNamespaceURI(prefix);
        if (!WORKFLOW_NS.equals(namespace) || !key.matches("[a-z][a-z0-9-]{1,62}"))
            throw invalid("Process " + process.getAttribute("id") + " must name a workspace workflow: externalRef=\"prefix:workflow-key\" with the prefix bound to " + WORKFLOW_NS + ".");
        return key;
    }

    /** Diagram shapes for the generated planning event and entry criterion, beside the discretionary item's own shape. */
    private static void plannerShapes(Document document, String id) {
        for (var shape : list(document.getElementsByTagNameNS(CMMNDI, "CMMNShape"))) {
            if (!id.equals(shape.getAttribute("cmmnElementRef"))) continue;
            var bounds = children(shape).stream().filter(child -> DC.equals(child.getNamespaceURI()) && "Bounds".equals(child.getLocalName())).findFirst().orElse(null);
            if (bounds == null) return;
            double x = Double.parseDouble(bounds.getAttribute("x")), y = Double.parseDouble(bounds.getAttribute("y")), h = Double.parseDouble(bounds.getAttribute("height"));
            var diagram = shape.getParentNode();
            diagram.appendChild(diShape(document, id + PLANNER + "_item", x - 40, y + h / 2 - 12, 24, 24));
            diagram.appendChild(diShape(document, id + "__plan_entry", x - 6, y + h / 2 - 10, 12, 20));
            return;
        }
    }

    private static Element diShape(Document document, String ref, double x, double y, double w, double h) {
        var shape = document.createElementNS(CMMNDI, "cmmndi:CMMNShape");
        shape.setAttribute("id", ref + "_di");
        shape.setAttribute("cmmnElementRef", ref);
        var bounds = document.createElementNS(DC, "dc:Bounds");
        bounds.setAttribute("x", String.valueOf(x)); bounds.setAttribute("y", String.valueOf(y));
        bounds.setAttribute("width", String.valueOf(w)); bounds.setAttribute("height", String.valueOf(h));
        shape.appendChild(bounds);
        shape.appendChild(document.createElementNS(CMMNDI, "cmmndi:CMMNLabel"));
        return shape;
    }

    private static List<Element> list(NodeList nodes) {
        var out=new ArrayList<Element>();
        for(int i=0;i<nodes.getLength();i++) out.add((Element)nodes.item(i));
        return out;
    }

    private static Element element(Document document,String name,String... attributes) {
        var element=document.createElementNS(CMMN,name);
        for(int i=0;i<attributes.length;i+=2) element.setAttribute(attributes[i],attributes[i+1]);
        return element;
    }

    private static void insertBefore(Element parent,Element added,java.util.function.Predicate<Element> before) {
        for(var child:children(parent)) if(before.test(child)) { parent.insertBefore(added,child); return; }
        parent.appendChild(added);
    }

    private static boolean repeated(Element item, Element definition) {
        var controls=new ArrayList<>(children(item,"itemControl"));
        controls.addAll(children(definition,"defaultControl"));
        return controls.stream().anyMatch(control->!children(control,"repetitionRule").isEmpty());
    }

    /** CMMN stage scopes are lexical: definitions, sentries and event sources must belong to the same plan. */
    private static boolean validatePlan(Element plan, Map<String, Element> ids, int depth) {
        if (depth > 4) throw invalid("At most four nested stages are supported.");
        if (plan.hasAttribute("autoComplete") && !Set.of("true", "false").contains(plan.getAttribute("autoComplete")))
            throw invalid("Stage autoComplete must be a literal boolean.");
        if((plan.getElementsByTagNameNS(CMMN,"repetitionRule").getLength()>0 || plan.getElementsByTagNameNS(CMMN,"discretionaryItem").getLength()>0
                || plan.getElementsByTagNameNS(CMMN,"processTask").getLength()>0) && !"false".equals(plan.getAttribute("autoComplete")))
            throw invalid("Stage " + plan.getAttribute("id") + " contains repeated, discretionary or workflow work and requires explicit autoComplete=false.");
        var items = children(plan, "planItem");
        var tables = children(plan, "planningTable");
        if (tables.size() > 1) throw invalid("A stage may contain only one planning table.");
        var discretionary = tables.isEmpty() ? List.<Element>of() : children(tables.getFirst(), "discretionaryItem");
        if (items.isEmpty()) throw invalid("Every stage needs at least one plan item.");
        if (!tables.isEmpty() && discretionary.isEmpty()) throw invalid("A planning table needs at least one discretionary item.");
        boolean initialHuman = false;
        Set<String> used = new HashSet<>();
        for (var stage : children(plan, "stage")) {
            if (stage.hasAttribute("autoComplete") && !Set.of("true", "false").contains(stage.getAttribute("autoComplete")))
                throw invalid("Stage autoComplete must be a literal boolean.");
        }
        for (var item : items) {
            var definition = ids.get(item.getAttribute("definitionRef"));
            if (definition == null || definition.getParentNode() != plan || !WORK.contains(definition.getLocalName()))
                throw invalid("Plan item " + item.getAttribute("id") + " must reference a human task, workflow task, user event, milestone or stage defined in its own stage.");
            if (!used.add(definition.getAttribute("id")))
                throw invalid("Each task, milestone or stage definition may be instantiated only once in this subset.");
            if ("milestone".equals(definition.getLocalName())) {
                for (var control : children(item,"itemControl"))
                    if (!children(control,"manualActivationRule").isEmpty()) throw invalid("Milestones cannot be manually activated.");
            }
            if ("userEventListener".equals(definition.getLocalName()) && (!children(item,"entryCriterion").isEmpty()
                    || !children(item,"exitCriterion").isEmpty() || !children(item,"itemControl").isEmpty()))
                throw invalid("User event listeners cannot have criteria, required/manual controls or repetition.");
            if ("processTask".equals(definition.getLocalName())) {
                var controls=new ArrayList<>(children(item,"itemControl")); controls.addAll(children(definition,"defaultControl"));
                if (controls.stream().anyMatch(control->!children(control,"manualActivationRule").isEmpty()))
                    throw invalid("Workflow task " + item.getAttribute("id") + " starts automatically; manual activation is not supported. Use a discretionary item to let people decide.");
            }
            if (repeated(item, definition)) {
                if ("userEventListener".equals(definition.getLocalName()))
                    throw invalid("User event listeners cannot repeat.");
                if (!children(item,"exitCriterion").isEmpty())
                    throw invalid("Repeated item " + item.getAttribute("id") + " cannot also have an exit criterion: the pinned engine does not evaluate repetition on termination.");
                if ("milestone".equals(definition.getLocalName()) && children(item,"entryCriterion").isEmpty())
                    throw invalid("A repeated milestone needs an entry criterion; it repeats each time the criterion is satisfied again.");
            }
            boolean stageHuman = "stage".equals(definition.getLocalName()) && validatePlan(definition, ids, depth + 1);
            if (children(item, "entryCriterion").isEmpty() && (Set.of("humanTask","processTask","userEventListener").contains(definition.getLocalName()) || stageHuman))
                initialHuman = true;
            for (var criterion : children(item)) {
                if ("itemControl".equals(criterion.getLocalName())) continue;
                if ("milestone".equals(definition.getLocalName()) && "exitCriterion".equals(criterion.getLocalName()))
                    throw invalid("A milestone cannot have an exit criterion.");
                var sentry = ids.get(criterion.getAttribute("sentryRef"));
                if (sentry == null || sentry.getParentNode() != plan || !"sentry".equals(sentry.getLocalName()))
                    throw invalid("Criterion of " + item.getAttribute("id") + " must reference a sentry in its own stage.");
            }
        }
        for (var item : discretionary) {
            var definition = ids.get(item.getAttribute("definitionRef"));
            if (definition == null || definition.getParentNode() != plan || !Set.of("humanTask","processTask").contains(definition.getLocalName()))
                throw invalid("Discretionary item " + item.getAttribute("id") + " must reference a human task or workflow task defined in its own stage.");
            if (!used.add(definition.getAttribute("id")))
                throw invalid("Each task definition may be planned or instantiated only once in this subset.");
            if (repeated(item, definition))
                throw invalid("Discretionary item " + item.getAttribute("id") + " cannot repeat: it is planned once per stage instance.");
            if ("processTask".equals(definition.getLocalName())) {
                var controls=new ArrayList<>(children(item,"itemControl")); controls.addAll(children(definition,"defaultControl"));
                if (controls.stream().anyMatch(control->!children(control,"manualActivationRule").isEmpty()))
                    throw invalid("Workflow task " + item.getAttribute("id") + " starts when planned; manual activation is not supported.");
            }
        }
        for (var definition : children(plan)) {
            if (WORK.contains(definition.getLocalName()) && !used.contains(definition.getAttribute("id")))
                throw invalid("Every task, milestone or stage definition needs a plan item or discretionary item in its own stage (" + definition.getAttribute("id") + ").");
        }
        for (var sentry : children(plan, "sentry")) {
            if (children(sentry, "planItemOnPart").isEmpty()) throw invalid("Empty sentries are unsupported.");
            for (var onPart : children(sentry, "planItemOnPart")) {
                var source = ids.get(onPart.getAttribute("sourceRef"));
                var events = children(onPart, "standardEvent");
                if (source == null || source.getParentNode() != plan || !"planItem".equals(source.getLocalName()) || events.size() != 1)
                    throw invalid("Sentry " + sentry.getAttribute("id") + " must reference a plan item in its own stage.");
                var sourceDefinition = ids.get(source.getAttribute("definitionRef"));
                String expected = sourceDefinition != null && Set.of("milestone","userEventListener").contains(sourceDefinition.getLocalName()) ? "occur" : "complete";
                if (!expected.equals(events.getFirst().getTextContent().trim()))
                    throw invalid("Sentries support task/stage completion or milestone/user event occurrence events (" + sentry.getAttribute("id") + ").");
            }
        }
        return initialHuman;
    }

    private static void walk(Element element, Map<String, Element> ids, Set<String> groups, int depth) {
        if (depth == 1 && CMMNDI.equals(element.getNamespaceURI()) && "CMMNDI".equals(element.getLocalName())) return;
        if (depth > 12 || !CMMN.equals(element.getNamespaceURI()) || !CHILDREN.containsKey(element.getLocalName()))
            throw invalid("Unsupported CMMN element or nesting depth: " + element.getLocalName() + ".");
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
            } else throw invalid("Unsupported CMMN attribute: " + attr.getNodeName() + " on " + kind + ".");
        }
        String id = element.getAttribute("id");
        if (!id.isEmpty() && ids.putIfAbsent(id, element) != null) throw invalid("Duplicate CMMN ID: " + id);
        if (Set.of("planItem","humanTask","processTask","process","sentry","case","stage","milestone","casePlanModel","role","userEventListener","discretionaryItem","planningTable").contains(kind) && id.isEmpty())
            throw invalid("A required CMMN ID is missing on " + kind + ".");
        if (!id.isEmpty() && !id.matches("[A-Za-z_][A-Za-z0-9_.-]{0,63}"))
            throw invalid("CMMN ID " + id + " must start with a letter or underscore and use at most 64 letters, digits, '_', '.' or '-'.");
        if (kind.equals("role") && !element.getAttribute("name").matches("[a-z][a-z0-9-]{1,62}"))
            throw invalid("Case role names must identify workspace group keys.");
        if (kind.equals("humanTask") && !element.hasAttributeNS(FLOWABLE, "candidateGroups"))
            throw invalid("Every human task needs candidate groups.");
        if (kind.equals("processTask") && (element.getAttribute("processRef").isEmpty()
                || element.hasAttribute("isBlocking") && !"true".equals(element.getAttribute("isBlocking"))))
            throw invalid("Workflow task " + id + " needs a processRef and must be blocking.");
        if (kind.equals("planItem") || kind.equals("discretionaryItem")) {
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
                int order=List.of("repetitionRule","requiredRule","manualActivationRule").indexOf(rule.getLocalName());
                if(order<previous) throw invalid("CMMN controls must order repetition, required, then manual activation rules.");
                previous=order;
            }
            Element controlled = (Element) element.getParentNode();
            if ("milestone".equals(controlled.getLocalName()) && !children(element,"manualActivationRule").isEmpty())
                throw invalid("Milestones cannot be manually activated.");
        }
        if (Set.of("planItem","discretionaryItem","humanTask","processTask","stage","milestone").contains(kind)
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
            if (kind.equals("definitions") && CMMNDI.equals(child.getNamespaceURI()) && "CMMNDI".equals(child.getLocalName())) continue;
            if (!CMMN.equals(child.getNamespaceURI()) || !CHILDREN.get(kind).contains(child.getLocalName()))
                throw invalid("Unsupported CMMN element: " + child.getLocalName() + " in " + kind + ".");
            walk(child, ids, groups, depth + 1);
        }
    }

    /** CMMN 1.1 diagram interchange: shapes with bounds and edges with waypoints, all referring to model elements. */
    private static void validateDiagram(Element di, Map<String, Element> ids) {
        for (int i = 0; i < di.getAttributes().getLength(); i++) {
            var attr = di.getAttributes().item(i);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI())) throw invalid("Unsupported CMMNDI attribute: " + attr.getNodeName() + ".");
        }
        var diagrams = children(di);
        if (diagrams.size() > 1) throw invalid("At most one CMMN diagram is supported.");
        int elements = 0;
        Set<String> diagramIds = new HashSet<>();
        for (var diagram : diagrams) {
            requireDi(diagram, CMMNDI, "CMMNDiagram", Set.of("id", "name", "documentation", "resolution", "sharedStyle"));
            for (var part : children(diagram)) {
                if (++elements > 400) throw invalid("A CMMN diagram may contain at most 400 shapes and edges.");
                if (CMMNDI.equals(part.getNamespaceURI()) && "Size".equals(part.getLocalName())) {
                    requireDi(part, CMMNDI, "Size", Set.of("width", "height"));
                    number(part, "width"); number(part, "height");
                    continue;
                }
                if (CMMNDI.equals(part.getNamespaceURI()) && "CMMNShape".equals(part.getLocalName())) {
                    requireDi(part, CMMNDI, "CMMNShape", Set.of("id", "cmmnElementRef", "isCollapsed", "isPlanningTableCollapsed", "sharedStyle"));
                    reference(part, "cmmnElementRef", ids);
                    int bounds = 0;
                    for (var child : children(part)) {
                        if (DC.equals(child.getNamespaceURI()) && "Bounds".equals(child.getLocalName())) { bounds(child); bounds++; }
                        else label(child);
                    }
                    if (bounds != 1) throw invalid("Every CMMN shape needs exactly one dc:Bounds.");
                } else if (CMMNDI.equals(part.getNamespaceURI()) && "CMMNEdge".equals(part.getLocalName())) {
                    requireDi(part, CMMNDI, "CMMNEdge", Set.of("id", "cmmnElementRef", "sourceCMMNElementRef", "targetCMMNElementRef", "isStandardEventVisible", "sharedStyle"));
                    for (String ref : List.of("cmmnElementRef", "sourceCMMNElementRef", "targetCMMNElementRef"))
                        if (part.hasAttribute(ref)) reference(part, ref, ids);
                    int points = 0;
                    for (var child : children(part)) {
                        if (DI.equals(child.getNamespaceURI()) && "waypoint".equals(child.getLocalName())) {
                            requireDi(child, DI, "waypoint", Set.of("x", "y"));
                            number(child, "x"); number(child, "y");
                            if (++points > 50) throw invalid("A CMMN edge may have at most 50 waypoints.");
                        } else label(child);
                    }
                    if (points < 2) throw invalid("Every CMMN edge needs at least two waypoints.");
                } else throw invalid("Unsupported CMMNDI element: " + part.getLocalName() + ".");
                String id = part.getAttribute("id");
                if (!id.isEmpty() && (ids.containsKey(id) || !diagramIds.add(id))) throw invalid("Duplicate CMMN ID: " + id);
            }
        }
    }

    private static void label(Element child) {
        if (!CMMNDI.equals(child.getNamespaceURI()) || !"CMMNLabel".equals(child.getLocalName()))
            throw invalid("Unsupported CMMNDI element: " + child.getLocalName() + ".");
        requireDi(child, CMMNDI, "CMMNLabel", Set.of("id", "sharedStyle"));
        for (var bounds : children(child)) {
            if (!DC.equals(bounds.getNamespaceURI()) || !"Bounds".equals(bounds.getLocalName())) throw invalid("A CMMN label may contain only bounds.");
            bounds(bounds);
        }
    }

    private static void bounds(Element bounds) {
        requireDi(bounds, DC, "Bounds", Set.of("x", "y", "width", "height"));
        for (String attribute : List.of("x", "y", "width", "height")) number(bounds, attribute);
        if (Double.parseDouble(bounds.getAttribute("width")) < 0 || Double.parseDouble(bounds.getAttribute("height")) < 0)
            throw invalid("Diagram bounds cannot be negative.");
    }

    private static void requireDi(Element element, String namespace, String name, Set<String> attributes) {
        if (!namespace.equals(element.getNamespaceURI()) || !name.equals(element.getLocalName())) throw invalid("Unsupported CMMNDI element: " + element.getLocalName() + ".");
        var all = element.getAttributes();
        for (int i = 0; i < all.getLength(); i++) {
            var attr = all.item(i);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI())) continue;
            if (attr.getNamespaceURI() != null || !attributes.contains(attr.getNodeName()))
                throw invalid("Unsupported CMMNDI attribute: " + attr.getNodeName() + " on " + name + ".");
        }
        if (!Set.of("Bounds", "waypoint", "Size").contains(name) && children(element).isEmpty() && element.getTextContent().strip().length() > 0)
            throw invalid("Unexpected text in CMMN diagram.");
    }

    private static void number(Element element, String attribute) {
        try {
            double value = Double.parseDouble(element.getAttribute(attribute));
            if (!Double.isFinite(value) || Math.abs(value) > 1_000_000) throw invalid("Diagram coordinates must be finite and within 1,000,000.");
        } catch (NumberFormatException ex) { throw invalid("Diagram coordinates must be numbers."); }
    }

    private static void reference(Element element, String attribute, Map<String, Element> ids) {
        if (!ids.containsKey(element.getAttribute(attribute)))
            throw invalid("Diagram element " + element.getAttribute("id") + " refers to unknown model element " + element.getAttribute(attribute) + ".");
    }

    private static List<Element> children(Element parent, String name) {
        return children(parent).stream().filter(e -> name.equals(e.getLocalName())).toList();
    }
    private static List<Element> children(Element parent) {
        var result = new ArrayList<Element>();
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
