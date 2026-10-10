package com.orqix.migration.camunda;

import java.util.*;

/** Bounded cross-file dependency inventory. Source workers and expressions are never evaluated. */
public final class ProjectAnalyzer {
    public record Source(String fileName,String content,String sourceFormat) {}
    public record File(String fileName,String processId,List<String> dependencies,Analysis analysis) {}
    public record Issue(String fileName,String code,String detail) {}
    public record Plan(List<File> files,List<String> conversionOrder,List<Issue> issues) {}

    public static Plan analyze(List<Source> sources) {
        if(sources==null || sources.isEmpty() || sources.size()>10) throw SourceException.invalid("Project needs 1 to 10 source files.");
        long bytes=0; Set<String> names=new HashSet<>(); List<File> files=new ArrayList<>(); List<Issue> issues=new ArrayList<>();
        Map<String,File> processes=new LinkedHashMap<>();
        for(Source source:sources) {
            if(source.fileName()==null || source.fileName().isBlank() || source.fileName().length()>200 || !names.add(source.fileName())) throw SourceException.invalid("Project filenames must be unique, nonblank and at most 200 characters.");
            if(source.content()==null) throw SourceException.invalid("Project source content is required.");
            bytes+=source.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if(bytes>4_194_304) throw new SourceException("Project source exceeds 4 MiB total.",true);
            if(source.sourceFormat()!=null && !"bpmn".equals(source.sourceFormat())) {
                issues.add(new Issue(source.fileName(),"PROJECT_FORMAT_NOT_SUPPORTED","This project preserves the source but converts BPMN only. Upload a DMN file as its own migration (source format dmn) and attach it with decisionMappings; forms need separate reviewed reconstruction."));
                files.add(new File(source.fileName(),null,List.of(),null)); continue;
            }
            Analysis analysis=Analyzer.analyze(source.content(),Limits.DEFAULT);
            XElement root=SafeXml.parse(source.content(),Limits.DEFAULT);
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
            File file=new File(source.fileName(),analysis.processId(),List.copyOf(dependencies),analysis); files.add(file);
            if(processes.putIfAbsent(file.processId(),file)!=null) issues.add(new Issue(file.fileName(),"PROJECT_DUPLICATE_PROCESS","Process ID '"+file.processId()+"' appears in more than one file."));
        }
        for(File file:files) for(String dependency:file.dependencies()) if(!processes.containsKey(dependency))
            issues.add(new Issue(file.fileName(),"PROJECT_MISSING_DEPENDENCY","Called process '"+dependency+"' is not included in this project."));
        List<String> order=new ArrayList<>(); Map<String,Integer> state=new HashMap<>();
        for(String process:processes.keySet()) visit(process,processes,state,order,issues);
        return new Plan(List.copyOf(files),issues.isEmpty()?List.copyOf(order):List.of(),List.copyOf(issues));
    }
    private static void visit(String process,Map<String,File> files,Map<String,Integer> state,List<String> order,List<Issue> issues) {
        int current=state.getOrDefault(process,0);
        if(current==2) return;
        if(current==1) { issues.add(new Issue(files.get(process).fileName(),"PROJECT_DEPENDENCY_CYCLE","Cross-file child process dependencies contain a cycle at '"+process+"'.")); return; }
        state.put(process,1);
        for(String dependency:files.get(process).dependencies()) if(files.containsKey(dependency)) visit(dependency,files,state,order,issues);
        state.put(process,2); order.add(process);
    }
}
