package com.orqix.migration.pega;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.orqix.migration.camunda.*;
import com.orqix.shared.json.Json;

/** Strict XPDL 2.0 flow-export adapter. This does not interpret native RAP rule pages or execute Pega code. */
public final class PegaXpdl {
    public static final String ADAPTER = "pega-xpdl-0.1";
    public static final String NS = "http://www.wfmc.org/2004/XPDL2.0alpha";
    private static final Map<String,Set<String>> CHILDREN = Map.ofEntries(
        Map.entry("Package",Set.of("PackageHeader","WorkflowProcesses","Participants")),
        Map.entry("PackageHeader",Set.of("XPDLVersion","Vendor","Created","Description","Documentation")),
        Map.entry("WorkflowProcesses",Set.of("WorkflowProcess")),
        Map.entry("WorkflowProcess",Set.of("ProcessHeader","Activities","Transitions","Participants")),
        Map.entry("ProcessHeader",Set.of("Created","Description","Documentation")),
        Map.entry("Participants",Set.of("Participant")), Map.entry("Participant",Set.of("ParticipantType","Description")),
        Map.entry("Activities",Set.of("Activity")),
        Map.entry("Activity",Set.of("Event","Route","Implementation","Performer","StartMode","FinishMode","Description","NodeGraphicsInfos")),
        Map.entry("StartMode",Set.of("Automatic")), Map.entry("FinishMode",Set.of("Manual")),
        Map.entry("Event",Set.of("StartEvent","EndEvent")), Map.entry("Implementation",Set.of("No")),
        Map.entry("Transitions",Set.of("Transition")), Map.entry("Transition",Set.of("Condition","Description","ConnectorGraphicsInfos")),
        Map.entry("NodeGraphicsInfos",Set.of("NodeGraphicsInfo")), Map.entry("NodeGraphicsInfo",Set.of("Coordinates")),
        Map.entry("ConnectorGraphicsInfos",Set.of("ConnectorGraphicsInfo")), Map.entry("ConnectorGraphicsInfo",Set.of("Coordinates")));
    private static final Map<String,Set<String>> ATTRS = Map.ofEntries(
        Map.entry("Package",Set.of("Id","Name","{http://www.w3.org/2001/XMLSchema-instance}schemaLocation")), Map.entry("WorkflowProcess",Set.of("Id","Name")),
        Map.entry("Participant",Set.of("Id","Name")), Map.entry("ParticipantType",Set.of("Type")),
        Map.entry("Activity",Set.of("Id","Name")), Map.entry("Route",Set.of("GatewayType","MarkerVisible")),
        Map.entry("StartEvent",Set.of("Trigger")), Map.entry("EndEvent",Set.of("Result")),
        Map.entry("Transition",Set.of("Id","Name","From","To")), Map.entry("Condition",Set.of("Type")),
        Map.entry("NodeGraphicsInfo",Set.of("ToolId","Page","LaneId","Width","Height","BorderColor","FillColor","IsVisible")),
        Map.entry("ConnectorGraphicsInfo",Set.of("ToolId","Page","BorderColor","IsVisible","Style")),
        Map.entry("Coordinates",Set.of("XCoordinate","YCoordinate")));
    private static final Set<String> TEXT = Set.of("XPDLVersion","Vendor","Created","Description","Documentation","Performer","Condition");
    private static final Set<String> REPEATED = Set.of("WorkflowProcess","Activity","Transition","Participant","NodeGraphicsInfo","ConnectorGraphicsInfo","Coordinates");

    public record Model(Analysis analysis, List<Report.Issue> sourceIssues, Map<String,String> paths, String sourceHash) {
        public Report.CompatibilityReport report(Report.CompatibilityReport converted, boolean acknowledgeInfo) {
            List<Report.Issue> issues=new ArrayList<>();
            for(var issue:sourceIssues) issues.add(Report.INFO.equals(issue.severity()) && acknowledgeInfo ? issue.withResolution(Report.ACKNOWLEDGED) : issue);
            if(converted!=null) for(var i:converted.issues()) issues.add(new Report.Issue(i.sourceElementId(),i.sourceName(),i.sourceKind(),i.severity(),i.reasonCode(),i.explanation(),i.suggestedAction(),i.targetNodeId(),i.resolutionStatus(),paths.getOrDefault(i.sourceElementId(),"/Package")));
            boolean ready=converted!=null && converted.publishable() && issues.stream().noneMatch(i->i.blocksPublishing() || Report.INFO.equals(i.severity()) && Report.OPEN.equals(i.resolutionStatus()));
            return new Report.CompatibilityReport("pega","XPDL 2.0",ADAPTER,sourceHash,converted==null?null:converted.processId(),converted==null?null:converted.processName(),Report.ElementCounts.of(issues),issues,
                List.of("Automatic structural conversion of a bounded XPDL 2.0 flow export, not native RAP rule resolution.","Assignments and approval routes require explicit review. No source activities, expressions or credentials are executed.","No Pega release is certified; genuine export and source-runtime acceptance remain required."),
                converted==null?new Report.RequiredMappings(List.of(),List.of(),List.of()):converted.requiredMappings(),ready);
        }
        public Report.CompatibilityReport report() { return report(analysis==null?null:Converter.report(analysis,analysis.items().stream().map(Analysis.Item::issue).toList()),false); }
        public boolean blocked() { return sourceIssues.stream().anyMatch(Report.Issue::blocksPublishing); }
    }

    public static Model analyze(String source) {
        XElement root=SafeXml.parse(source,Limits.DEFAULT);
        if(!root.is(NS,"Package")) throw SourceException.invalid("Expected an XPDL 2.0 Package flow export, not a native Pega RAP rule page.");
        List<Report.Issue> issues=new ArrayList<>(); Map<String,String> paths=new LinkedHashMap<>();
        audit(root,issues,paths,new HashSet<>());
        XElement header=root.child(NS,"PackageHeader");
        if(header==null || header.child(NS,"XPDLVersion")==null || !"2.0".equals(header.child(NS,"XPDLVersion").text())) block(issues,root,"PEGA_XPDL_VERSION_UNSUPPORTED","The package must explicitly declare XPDLVersion 2.0.");
        XElement processes=root.child(NS,"WorkflowProcesses");
        List<XElement> flows=processes==null?List.of():processes.children(NS,"WorkflowProcess");
        if(flows.size()!=1) block(issues,root,"PEGA_XPDL_PROCESS_SELECTION","Export exactly one flow per file; multiple processes are not selected implicitly.");
        String hash=Json.sha256Hex(source.getBytes(StandardCharsets.UTF_8));
        if(issues.stream().anyMatch(Report.Issue::blocksPublishing)) return new Model(null,List.copyOf(issues),paths,hash);
        XElement flow=flows.getFirst();
        var activities=flow.child(NS,"Activities"); var transitions=flow.child(NS,"Transitions");
        if(activities==null || transitions==null) { block(issues,flow,"PEGA_XPDL_GRAPH_REQUIRED","Activities and Transitions are required."); return new Model(null,List.copyOf(issues),paths,hash); }
        Map<String,String> defaults=new HashMap<>();
        for(var t:transitions.children(NS,"Transition")) {
            var condition=t.child(NS,"Condition");
            if(condition!=null && "OTHERWISE".equals(condition.attr("Type")) && defaults.putIfAbsent(t.attr("From"),t.attr("Id"))!=null)
                block(issues,t,"PEGA_XPDL_DEFAULT_AMBIGUOUS","A route cannot have more than one OTHERWISE transition.");
        }
        StringBuilder xml=new StringBuilder("<definitions xmlns='"+Namespaces.BPMN+"' xmlns:c='"+Namespaces.CAMUNDA7+"' targetNamespace='urn:orqix:pega-xpdl'><process isExecutable='true' id='").append(escape(flow.attr("Id"))).append("' name='").append(escape(flow.attr("Name"))).append("'>");
        for(var a:activities.children(NS,"Activity")) {
            String type=null; var event=a.child(NS,"Event"); var route=a.child(NS,"Route"); var impl=a.child(NS,"Implementation");
            int kinds=(event==null?0:1)+(route==null?0:1)+(impl==null?0:1);
            if(kinds!=1) block(issues,a,"PEGA_XPDL_ACTIVITY_UNSUPPORTED","Each activity needs exactly one supported event, route or manual implementation.");
            else if(event!=null) {
                if(event.children().size()!=1) block(issues,a,"PEGA_XPDL_EVENT_UNSUPPORTED","An event must contain exactly one plain start or end event.");
                else if(event.child(NS,"StartEvent")!=null && none(event.child(NS,"StartEvent").attr("Trigger"))) type="startEvent";
                else if(event.child(NS,"EndEvent")!=null && none(event.child(NS,"EndEvent").attr("Result"))) type="endEvent";
                else block(issues,a,"PEGA_XPDL_EVENT_UNSUPPORTED","Only untriggered starts and plain ends are supported.");
            } else if(route!=null) {
                if("XOR".equals(route.attr("GatewayType"))) type="exclusiveGateway";
                else block(issues,a,"PEGA_XPDL_ROUTE_UNSUPPORTED","Only explicit XOR routing is supported; parallel, inclusive and event routes require reconstruction.");
            } else if(impl.children(NS,"No").size()==1 && impl.children().size()==1) {
                var performer=a.child(NS,"Performer");
                var startMode=a.child(NS,"StartMode"); var finishMode=a.child(NS,"FinishMode");
                if(startMode==null || finishMode==null || startMode.child(NS,"Automatic")==null || finishMode.child(NS,"Manual")==null)
                    block(issues,a,"PEGA_XPDL_TASK_MODE_UNSUPPORTED","A human task must explicitly start automatically and finish manually; No alone does not prove human-task semantics.");
                else if(performer==null || !performer.text().matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) block(issues,a,"PEGA_XPDL_ASSIGNMENT_UNSUPPORTED","A manual task needs one literal Performer to map to a workspace group; computed assignments are not inferred.");
                else type="userTask";
            } else block(issues,a,"PEGA_XPDL_IMPLEMENTATION_UNSUPPORTED","Only manual No implementations are supported; tools, scripts and subflows are not executed.");
            if(type==null) continue;
            if(!"userTask".equals(type) && a.child(NS,"Performer")!=null) block(issues,a,"PEGA_XPDL_ASSIGNMENT_UNSUPPORTED","A performer on a non-manual activity is unsupported.");
            if(!"userTask".equals(type) && (a.child(NS,"StartMode")!=null || a.child(NS,"FinishMode")!=null)) block(issues,a,"PEGA_XPDL_TASK_MODE_UNSUPPORTED","Task start/finish modes on events or routes are unsupported.");
            xml.append('<').append(type).append(" id='").append(escape(a.attr("Id"))).append("' name='").append(escape(a.attr("Name"))).append("'");
            if(type.equals("userTask")) xml.append(" c:candidateGroups='").append(escape(a.child(NS,"Performer").text())).append("'");
            if(defaults.containsKey(a.attr("Id"))) {
                if(!type.equals("exclusiveGateway")) block(issues,a,"PEGA_XPDL_DEFAULT_UNSUPPORTED","OTHERWISE is supported only on an XOR route.");
                xml.append(" default='").append(escape(defaults.get(a.attr("Id")))).append("'");
            }
            xml.append("/>");
        }
        for(var t:transitions.children(NS,"Transition")) {
            xml.append("<sequenceFlow id='").append(escape(t.attr("Id"))).append("' sourceRef='").append(escape(t.attr("From"))).append("' targetRef='").append(escape(t.attr("To"))).append("' name='").append(escape(t.attr("Name"))).append("'>");
            var c=t.child(NS,"Condition");
            if(c!=null) {
                if("CONDITION".equals(c.attr("Type")) && !c.text().isBlank()) xml.append("<conditionExpression>").append(escape("${"+c.text()+"}")).append("</conditionExpression>");
                else if(!"OTHERWISE".equals(c.attr("Type")) || !c.text().isBlank()) block(issues,t,"PEGA_XPDL_CONDITION_UNSUPPORTED","Only a nonempty CONDITION or an empty OTHERWISE condition is supported; exception and tool conditions are not executed.");
            }
            xml.append("</sequenceFlow>");
        }
        xml.append("</process></definitions>");
        if(issues.stream().anyMatch(Report.Issue::blocksPublishing)) return new Model(null,List.copyOf(issues),paths,hash);
        return new Model(Analyzer.analyze(xml.toString(),Limits.DEFAULT),List.copyOf(issues),Map.copyOf(paths),hash);
    }

    private static boolean none(String value) { return value==null || "None".equals(value); }
    private static String escape(String text) { return text==null?"":text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("'","&apos;").replace("\"","&quot;"); }
    private static String path(XElement e) { return (e.parent()==null?"":path(e.parent()))+"/"+e.name()+"["+e.ordinal()+"]"; }
    private static void block(List<Report.Issue> issues,XElement e,String reason,String detail) {
        issues.add(new Report.Issue(e.attr("Id")==null?"xpdl-"+e.ordinal():e.attr("Id"),e.attr("Name"),e.name(),Report.BLOCKER,reason,detail,"Reconstruct this rule explicitly; no source behavior was omitted from a converted draft.",null,Report.OPEN,path(e)));
    }
    private static void audit(XElement e,List<Report.Issue> issues,Map<String,String> paths,Set<String> ids) {
        if(!NS.equals(e.ns())) block(issues,e,"PEGA_XPDL_NAMESPACE_UNSUPPORTED","Foreign XML extensions require explicit reconstruction.");
        if(e.attr("Id")!=null) {
            if(e.attr("Id").isBlank() || !ids.add(e.attr("Id"))) block(issues,e,"PEGA_XPDL_ID_AMBIGUOUS","Source IDs must be nonblank and unique across the file.");
            paths.put(e.attr("Id"),path(e));
        }
        if(Set.of("Package","WorkflowProcess","Activity","Transition","Participant").contains(e.name()) && e.attr("Id")==null) block(issues,e,"PEGA_XPDL_ID_REQUIRED","This source element requires an Id.");
        for(String attr:e.attributes().keySet()) if(!ATTRS.getOrDefault(e.name(),Set.of()).contains(attr)) block(issues,e,"PEGA_XPDL_ATTRIBUTE_UNSUPPORTED","Unsupported source attribute: "+attr+". Its behavior was not imported.");
        if(!e.text().isBlank() && !TEXT.contains(e.name())) block(issues,e,"PEGA_XPDL_TEXT_UNSUPPORTED","Unexpected source text requires explicit reconstruction.");
        if(Set.of("Description","Documentation","Created","Vendor").contains(e.name()) && !e.text().isBlank()) issues.add(new Report.Issue("xpdl-"+e.ordinal(),null,e.name(),Report.INFO,"PEGA_XPDL_METADATA_EXCLUDED","Source documentation or export metadata is retained in the source but not executed or copied to the workflow.","Review and acknowledge this exclusion.",null,Report.OPEN,path(e)));
        if(Set.of("NodeGraphicsInfos","ConnectorGraphicsInfos").contains(e.name()) || e.attr("MarkerVisible")!=null || e.attr("http://www.w3.org/2001/XMLSchema-instance","schemaLocation")!=null)
            issues.add(new Report.Issue("xpdl-"+e.ordinal(),null,e.name(),Report.INFO,"PEGA_XPDL_PRESENTATION_EXCLUDED","Known diagram presentation or schema-location metadata is retained in the source but not imported. External schemas are never fetched.","Review and acknowledge this exclusion.",null,Report.OPEN,path(e)));
        if(e.name().equals("ParticipantType") && !"ROLE".equals(e.attr("Type"))) block(issues,e,"PEGA_XPDL_PARTICIPANT_UNSUPPORTED","Only ROLE participant declarations are supported; source identities and permissions are not migrated.");
        Set<String> seen=new HashSet<>();
        for(var child:e.children()) {
            if(!CHILDREN.getOrDefault(e.name(),Set.of()).contains(child.name())) block(issues,child,"PEGA_XPDL_ELEMENT_UNSUPPORTED","Unsupported XPDL construct under "+e.name()+": "+child.name()+".");
            if(!REPEATED.contains(child.name()) && !seen.add(child.name())) block(issues,child,"PEGA_XPDL_ELEMENT_AMBIGUOUS","Repeated singleton source elements are not selected implicitly.");
            audit(child,issues,paths,ids);
        }
    }
    private PegaXpdl() { }
}
