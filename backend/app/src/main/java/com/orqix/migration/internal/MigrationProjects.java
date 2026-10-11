package com.orqix.migration.internal;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.audit.AuditWriter;
import com.orqix.identity.*;
import com.orqix.migration.camunda.*;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.*;
import com.orqix.shared.web.Sql;
import tools.jackson.databind.JsonNode;

@Service
public class MigrationProjects {
    public record File(String fileName,UUID migrationId,String processId,List<String> dependencies,String status,UUID convertedWorkflowId,
                       String sourceFormat,List<String> decisionIds,Map<String,String> decisionDependencies) {}
    public record Project(UUID id,String name,String status,List<File> files,List<UUID> conversionOrder,List<ProjectAnalyzer.Issue> issues,Instant createdAt) {}
    public record Converted(Project project,MigrationService.Converted converted) {}
    private final JdbcClient jdbc; private final Json json; private final PolicyService policy; private final MigrationService migrations; private final AuditWriter audit;
    public MigrationProjects(JdbcClient jdbc,Json json,PolicyService policy,MigrationService migrations,AuditWriter audit) { this.jdbc=jdbc;this.json=json;this.policy=policy;this.migrations=migrations;this.audit=audit; }
    private void authorize(Caller caller,UUID ws,boolean edit) {
        policy.require(policy.requireVisible(caller,ws),edit?Permission.WORKFLOW_EDIT:Permission.WORKFLOW_READ,edit?"editMigrationProject":"getMigrationProject",false);
    }
    @Transactional public Project upload(Caller caller,UUID ws,String name,List<ProjectAnalyzer.Source> sources) {
        authorize(caller,ws,true);
        if(name==null || name.isBlank() || name.length()>200) throw ApiException.malformed("Project name must contain 1 to 200 characters.");
        ProjectAnalyzer.Plan plan;
        try { plan=ProjectAnalyzer.analyze(sources,json); }
        catch(SourceException e) { throw new ApiException(e.tooLarge()?ProblemCode.PAYLOAD_TOO_LARGE:ProblemCode.SOURCE_INVALID,e.getMessage()); }
        List<File> files=new ArrayList<>(); Map<String,UUID> ids=new HashMap<>();
        for(int i=0;i<plan.files().size();i++) {
            var file=plan.files().get(i); var source=sources.get(i); UUID job=null;
            if(file.sourceFormat()!=null) { job=migrations.upload(caller,ws,file.fileName(),source.content(),file.sourceFormat()).id(); ids.putIfAbsent(file.planKey(),job); }
            files.add(new File(file.fileName(),job,file.processId(),file.dependencies(),"analyzed",null,file.sourceFormat(),file.decisionIds(),file.decisionDependencies()));
        }
        UUID id=Ids.newId(); Instant created=Instant.now();
        Project project=new Project(id,name,"analyzed",files,plan.conversionOrder().stream().map(ids::get).toList(),plan.issues(),created);
        jdbc.sql("INSERT INTO migration_project(id,workspace_id,created_by,name,sources,report,created_at) VALUES(:id,:ws,:actor,:name,CAST(:sources AS jsonb),CAST(:report AS jsonb),:created)")
            .param("id",id).param("ws",ws).param("actor",caller.subjectId()).param("name",name).param("sources",json.write(sources)).param("report",json.write(project)).param("created",Sql.ts(created)).update();
        audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.USER,caller.subjectId(),"migration.project.created","migrationProject",id.toString(),
            AuditWriter.Outcome.SUCCESS,Map.of("fileCount",files.size(),"issueCount",plan.issues().size())));
        return get(caller,ws,id);
    }
    @Transactional(readOnly=true) public Project get(Caller caller,UUID ws,UUID id) {
        authorize(caller,ws,false);
        Project project=stored(ws,id,false);
        int converted=0; List<File> files=new ArrayList<>();
        for(File file:project.files()) {
            MigrationService.Job job=file.migrationId()==null?null:migrations.get(caller,ws,file.migrationId());
            if(job!=null && "converted".equals(job.status())) converted++;
            files.add(new File(file.fileName(),file.migrationId(),file.processId(),file.dependencies(),job==null?"blocked":job.status(),job==null?null:job.convertedWorkflowId(),file.sourceFormat(),file.decisionIds()==null?List.of():file.decisionIds(),file.decisionDependencies()==null?Map.of():file.decisionDependencies()));
        }
        String status=!project.issues().isEmpty()?"blocked":converted==project.files().size()?"converted":converted>0?"converting":"analyzed";
        return new Project(project.id(),project.name(),status,files,project.conversionOrder(),project.issues(),project.createdAt());
    }
    @Transactional public Converted convertNext(Caller caller,UUID ws,UUID id,UUID expectedMigrationId,JsonNode mappings) {
        authorize(caller,ws,true); Project project=stored(ws,id,true);
        if(!project.issues().isEmpty()) throw new ApiException(ProblemCode.MIGRATION_BLOCKED,"Resolve project dependency findings before converting files.").with("projectIssues",project.issues());
        UUID next=null;
        for(UUID job:project.conversionOrder()) if(!"converted".equals(migrations.get(caller,ws,job).status())) { next=job;break; }
        if(next==null || !next.equals(expectedMigrationId)) throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT,"Project conversion order changed or every file is already converted.");
        var converted=migrations.convert(caller,ws,next,mappings);
        return new Converted(get(caller,ws,id),converted);
    }
    private Project stored(UUID ws,UUID id,boolean lock) {
        if(lock) jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))").param("key",ws+":"+id).query((rs,n)->1).single();
        return jdbc.sql("SELECT report::text FROM migration_project WHERE workspace_id=:ws AND id=:id")
            .param("ws",ws).param("id",id).query(String.class).optional().map(value->json.convert(json.parse(value),Project.class)).orElseThrow(ApiException::notFound);
    }
}
