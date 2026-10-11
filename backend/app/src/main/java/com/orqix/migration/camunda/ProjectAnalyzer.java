package com.orqix.migration.camunda;

import java.util.*;
import com.orqix.migration.dmn.DmnAnalyzer;
import com.orqix.shared.json.Json;

/** Bounded cross-file dependency inventory. Source workers and expressions are never evaluated. */
public final class ProjectAnalyzer {
    public record Source(String fileName,String content,String sourceFormat) {}
    public record File(String fileName,String processId,List<String> dependencies,Analysis analysis,
                       String sourceFormat,List<String> decisionIds,Map<String,String> decisionDependencies) {
        public String planKey() { return "dmn".equals(sourceFormat)?"dmn:"+fileName:"bpmn:"+processId; }
    }
    public record Issue(String fileName,String code,String detail) {}
    public record Plan(List<File> files,List<String> conversionOrder,List<Issue> issues) {}

    public static Plan analyze(List<Source> sources,Json json) {
        if(sources==null || sources.isEmpty() || sources.size()>10) throw SourceException.invalid("Project needs 1 to 10 source files.");
        long bytes=0; Set<String> names=new HashSet<>(); List<File> files=new ArrayList<>(); List<Issue> issues=new ArrayList<>();
        Map<String,File> processes=new LinkedHashMap<>(), decisions=new LinkedHashMap<>(), plans=new LinkedHashMap<>();
        for(Source source:sources) {
            if(source.fileName()==null || source.fileName().isBlank() || source.fileName().length()>200 || !names.add(source.fileName())) throw SourceException.invalid("Project filenames must be unique, nonblank and at most 200 characters.");
            if(source.content()==null) throw SourceException.invalid("Project source content is required.");
            bytes+=source.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if(bytes>4_194_304) throw new SourceException("Project source exceeds 4 MiB total.",true);
            String format=source.sourceFormat();
            if(format!=null && !Set.of("bpmn","dmn").contains(format)) {
                issues.add(new Issue(source.fileName(),"PROJECT_FORMAT_NOT_SUPPORTED","Projects support BPMN and bounded DMN tables only. Forms and other source formats need separate reviewed reconstruction."));
                files.add(new File(source.fileName(),null,List.of(),null,null,List.of(),Map.of())); continue;
            }
            XElement root=SafeXml.parse(source.content(),Limits.DEFAULT);
            if(format==null) format=root.ns().contains("/DMN/")?"dmn":"bpmn";
            if("dmn".equals(format)) {
                var dmn=DmnAnalyzer.analyze(source.content(),Limits.DEFAULT,json);
                List<String> ids=dmn.decisionNames().keySet().stream().sorted().toList();
                File file=new File(source.fileName(),dmn.report().processId(),List.of(),null,"dmn",ids,Map.of());
                files.add(file); plans.put(file.planKey(),file);
                if(ids.size()!=1) issues.add(new Issue(file.fileName(),"PROJECT_DMN_SELECTION_REQUIRED","This project slice requires exactly one decision per DMN file. Split or review this file as a standalone DMN migration; found "+ids.size()+" decisions."));
                for(String id:ids) {
                    if(decisions.putIfAbsent(id,file)!=null) issues.add(new Issue(file.fileName(),"PROJECT_DUPLICATE_DECISION","Decision ID '"+id+"' appears in more than one DMN file."));
                    if(dmn.table(id)==null || !dmn.blockersOf(id).isEmpty()) issues.add(new Issue(file.fileName(),"PROJECT_DMN_NOT_CONVERTIBLE","Decision '"+id+"' cannot be converted by the bounded DMN adapter. Review the file report before rebuilding it."));
                }
                continue;
            }
            Analysis analysis=Analyzer.analyze(source.content(),Limits.DEFAULT);
            XElement process=root.children(Namespaces.BPMN,"process").stream().filter(e->analysis.processId().equals(e.attr("id"))).findFirst().orElseThrow();
            Set<String> dependencies=new LinkedHashSet<>();
            for(XElement call:process.children(Namespaces.BPMN,"callActivity")) {
                String reference=call.attr("calledElement");
                XElement extensions=call.child(Namespaces.BPMN,"extensionElements");
                XElement called=extensions==null?null:extensions.child(Namespaces.ZEEBE,"calledElement");
                if(called!=null) reference=called.attr("processId");
                if(reference==null || reference.isBlank() || reference.contains("${") || reference.contains("#{") || reference.startsWith("="))
                    issues.add(new Issue(source.fileName(),"PROJECT_DYNAMIC_CHILD_REFERENCE","A child process reference is missing or dynamic; provide a literal dependency before project conversion."));
                else dependencies.add(reference);
            }
            Map<String,String> refs=new LinkedHashMap<>(); analysis.decisions().forEach((task,ref)->refs.put(task,ref.decisionId()));
            File file=new File(source.fileName(),analysis.processId(),List.copyOf(dependencies),analysis,"bpmn",List.of(),Map.copyOf(refs)); files.add(file); plans.putIfAbsent(file.planKey(),file);
            if(processes.putIfAbsent(file.processId(),file)!=null) issues.add(new Issue(file.fileName(),"PROJECT_DUPLICATE_PROCESS","Process ID '"+file.processId()+"' appears in more than one file."));
        }
        Map<String,List<String>> edges=new LinkedHashMap<>();
        for(File file:files) {
            List<String> required=new ArrayList<>();
            for(String dependency:file.dependencies()) {
                File target=processes.get(dependency);
                if(target==null) issues.add(new Issue(file.fileName(),"PROJECT_MISSING_DEPENDENCY","Called process '"+dependency+"' is not included in this project."));
                else required.add(target.planKey());
            }
            for(String decision:new LinkedHashSet<>(file.decisionDependencies().values())) {
                File target=decisions.get(decision);
                if(target==null) issues.add(new Issue(file.fileName(),"PROJECT_MISSING_DECISION","Called decision '"+decision+"' is not included in this project."));
                else required.add(target.planKey());
            }
            edges.put(file.planKey(),required);
        }
        List<String> order=new ArrayList<>(); Map<String,Integer> state=new HashMap<>();
        for(String key:plans.keySet()) visit(key,plans,edges,state,order,issues);
        return new Plan(List.copyOf(files),issues.isEmpty()?List.copyOf(order):List.of(),List.copyOf(issues));
    }
    private static void visit(String key,Map<String,File> files,Map<String,List<String>> edges,Map<String,Integer> state,List<String> order,List<Issue> issues) {
        int current=state.getOrDefault(key,0);
        if(current==2) return;
        if(current==1) { issues.add(new Issue(files.get(key).fileName(),"PROJECT_DEPENDENCY_CYCLE","Cross-file dependencies contain a cycle at '"+files.get(key).processId()+"'.")); return; }
        state.put(key,1);
        for(String dependency:edges.getOrDefault(key,List.of())) visit(dependency,files,edges,state,order,issues);
        state.put(key,2); order.add(key);
    }
}
