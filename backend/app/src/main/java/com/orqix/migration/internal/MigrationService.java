package com.orqix.migration.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.audit.AuditWriter;
import com.orqix.catalog.DraftAuthoring;
import com.orqix.identity.*;
import com.orqix.integration.ConnectionService;
import com.orqix.migration.camunda.*;
import com.orqix.migration.pega.PegaSource;
import com.orqix.shared.Ids;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.*;
import com.orqix.shared.web.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Workspace-scoped immutable source storage and transactional, compiler-gated draft creation. */
@Service
public class MigrationService {
    public record Job(UUID id, String fileName, String status, Report.CompatibilityReport report,
            UUID convertedWorkflowId, Instant createdAt, JsonNode sourceMap) { }
    public record Converted(JsonNode workflow, JsonNode draft, Report.CompatibilityReport report) { }
    private record Stored(Job view, String format, byte[] source, String mappings) { }
    private final JdbcClient jdbc; private final Json json; private final PolicyService policy;
    private final DraftAuthoring drafts; private final AuditWriter audit;
    private final ConnectionService connections;
    public MigrationService(JdbcClient jdbc, Json json, PolicyService policy, DraftAuthoring drafts, AuditWriter audit, ConnectionService connections) {
        this.jdbc=jdbc; this.json=json; this.policy=policy; this.drafts=drafts; this.audit=audit; this.connections=connections;
    }
    private void authorize(Caller caller, UUID ws, boolean edit, String operation) {
        var access=policy.requireVisible(caller, ws);
        policy.require(access,edit ? Permission.WORKFLOW_EDIT : Permission.WORKFLOW_READ, operation,false);
    }
    @Transactional
    public Job upload(Caller caller, UUID ws, String fileName, String content, String requestedFormat) {
        authorize(caller,ws,true,"uploadMigration");
        try {
            String format=requestedFormat == null ? "bpmn" : requestedFormat;
            byte[] bytes;
            try { bytes="pegaArchive".equals(format) ? Base64.getDecoder().decode(content) : content.getBytes(StandardCharsets.UTF_8); }
            catch (IllegalArgumentException e) { throw SourceException.invalid("The archive content must be base64 encoded."); }
            if (bytes.length == 0 || bytes.length > Limits.DEFAULT.maxBytes()) throw new SourceException("Source must be between 1 byte and 1 MiB.",true);
            Report.CompatibilityReport report;
            if (format.equals("pegaArchive")) report=PegaSource.archive(bytes);
            else if (format.equals("pegaMapping")) {
                JsonNode pkg=parsePackage(content);
                Stored original=archiveForPackage(ws,pkg);
                report=PegaSource.mapping(pkg, original.view.report(),false);
            } else if (format.equals("bpmn")) {
                var analysis=Analyzer.analyze(content,Limits.DEFAULT); format=analysis.sourceSystem();
                report=Converter.report(analysis,analysis.items().stream().map(Analysis.Item::issue).toList());
            } else throw SourceException.invalid("Unknown source format.");
            UUID id=Ids.newId();
            jdbc.sql("""
                INSERT INTO migration_job(id,workspace_id,created_by,file_name,source_format,source_sha256,source,adapter_version,report,status)
                VALUES(:id,:ws,:actor,:file,:format,:hash,:source,:adapter,CAST(:report AS jsonb),'analyzed')
                """).param("id",id).param("ws",ws).param("actor",caller.subjectId()).param("file",fileName)
                .param("format",format).param("hash",Json.sha256Hex(bytes)).param("source",bytes)
                .param("adapter",report.adapterVersion()).param("report",json.write(report)).update();
            audit(caller,ws,id,"migration.uploaded",Map.of("sourceFormat",format,"sourceHash",Json.sha256Hex(bytes)));
            return stored(ws,id,false).view;
        } catch (SourceException e) { throw sourceProblem(e); }
    }
    @Transactional(readOnly=true)
    public Job get(Caller caller,UUID ws,UUID id) { authorize(caller,ws,false,"getMigration"); return stored(ws,id,false).view; }
    @Transactional
    public Converted convert(Caller caller,UUID ws,UUID id,JsonNode request) {
        authorize(caller,ws,true,"convertMigration");
        ConversionRequest.validate(request);
        Stored job=stored(ws,id,true);
        if ("converted".equals(job.view.status())) {
            if (!json.parse(job.mappings).equals(request)) throw new ApiException(ProblemCode.WORKFLOW_VERSION_CONFLICT,"This source was already converted with different mappings.");
            var existing=drafts.view(caller,ws,job.view.convertedWorkflowId(),1);
            return new Converted(existing.workflow(),existing.draft(),job.view.report());
        }
        try {
            ObjectNode definition; Report.CompatibilityReport report; Object sourceMap;
            if (job.format.equals("pegaArchive")) throw new ApiException(ProblemCode.MIGRATION_BLOCKED,
                    "Native Pega archives are inventory-only. Upload a checksum-linked manual mapping package to create a draft.")
                    .with("openItems",job.view.report().issues());
            if (job.format.equals("pegaMapping")) {
                var pkg=parsePackage(new String(job.source,StandardCharsets.UTF_8));
                var original=archiveForPackage(ws,pkg);
                report=PegaSource.mapping(pkg,original.view.report(),request.path("acknowledgeInfo").asBoolean(false));
                definition=(ObjectNode)pkg.path("definition").deepCopy(); sourceMap=pkg.path("fileMappings");
            } else {
                var analysis=Analyzer.analyze(new String(job.source,StandardCharsets.UTF_8),Limits.DEFAULT);
                var result=Converter.convert(analysis,request,json,serviceInputTypes(ws,Converter.expandServiceGroups(analysis,request,json)));
                definition=result.definition(); report=result.report(); sourceMap=result.sourceMap();
            }
            if (!report.publishable()) throw new ApiException(ProblemCode.MIGRATION_BLOCKED,"Resolve mappings and acknowledge documented losses before converting.")
                    .with("openItems",report.issues().stream().filter(i -> Report.OPEN.equals(i.resolutionStatus())).toList());
            var created=drafts.createFromDefinition(caller,ws,new DraftAuthoring.NewDraft(request.path("workflowKey").asString(null),
                    request.path("name").asString(null),"Imported source; review compatibility report and verify behavior.",definition,
                    job.format.equals("pegaMapping") ? DraftAuthoring.Source.PEGA_IMPORT : DraftAuthoring.Source.IMPORT,false));
            if (!created.report().path("publishable").asBoolean(false)) {
                throw new ApiException(ProblemCode.MIGRATION_BLOCKED,"The mapped draft did not pass compiler validation. No workflow was created.")
                        .with("issues",created.report().path("issues"));
            }
            jdbc.sql("""
                UPDATE migration_job SET report=CAST(:report AS jsonb),source_map=CAST(:map AS jsonb),mappings=CAST(:mappings AS jsonb),
                    status='converted',converted_workflow_id=:workflow,converted_by_subject_id=:mapper,updated_at=now() WHERE workspace_id=:ws AND id=:id
                """).param("report",json.write(report)).param("map",json.write(sourceMap)).param("mappings",json.write(request))
                .param("workflow",created.workflowId()).param("mapper",caller.subjectId()).param("ws",ws).param("id",id).update();
            audit(caller,ws,id,"migration.converted",Map.of("workflowId",created.workflowId().toString(),"sourceFormat",job.format));
            return new Converted(created.workflow(),created.draft(),report);
        } catch (SourceException e) { throw sourceProblem(e); }
    }
    private Stored archiveForPackage(UUID ws,JsonNode pkg) {
        UUID archiveId;
        try { archiveId=UUID.fromString(pkg.path("archiveJobId").asString("")); }
        catch (IllegalArgumentException e) { throw SourceException.invalid("A manual mapping package must reference its archiveJobId."); }
        var original=stored(ws,archiveId,false);
        if (!original.format.equals("pegaArchive")) throw SourceException.invalid("archiveJobId must reference a Pega archive in this workspace.");
        return original;
    }
    private Map<String,JsonNode> serviceInputTypes(UUID ws,JsonNode request) {
        if (request.path("inputSchema").isObject()) return Map.of(); // Explicit schemas are checked by the compiler, not inference.
        Map<String,JsonNode> types=new LinkedHashMap<>();
        request.path("serviceTaskMappings").properties().forEach(task -> {
            var mapping=task.getValue();
            var definition=connections.definition(ws,mapping.path("connectionRef").asString(""),mapping.path("definitionVersion").asInt(1));
            if(definition.isEmpty()) return;
            var operation=definition.get().definition().operations().get(mapping.path("operation").asString(""));
            if(operation==null || operation.requestSchema()==null) return;
            mapping.path("inputMapping").properties().forEach(field -> {
                String ref=field.getValue().asString("");
                JsonNode schema=operation.requestSchema().path("properties").path(field.getKey());
                String type=schema.path("type").asString("");
                if(ref.matches("/input/[a-z][A-Za-z0-9]{0,63}") && Set.of("string","number","integer","boolean").contains(type)) {
                    JsonNode previous=types.putIfAbsent(ref.substring(7),schema);
                    if(previous!=null && !previous.equals(schema)) throw SourceException.invalid("Service operations disagree about constraints for request field '"+ref.substring(7)+"'. Supply explicit connector field mappings.");
                }
            });
        });
        return types;
    }
    private JsonNode parsePackage(String content) {
        try { var node=json.parse(content); if (!node.isObject() || Json.depth(node)>64) throw SourceException.invalid("Expected a bounded JSON mapping package."); return node; }
        catch (IllegalArgumentException e) { throw SourceException.invalid("Invalid JSON mapping package."); }
    }
    private Stored stored(UUID ws,UUID id,boolean lock) {
        return jdbc.sql("SELECT id,file_name,status,report::text,converted_workflow_id,created_at,source_format,source,mappings::text,source_map::text FROM migration_job WHERE workspace_id=:ws AND id=:id"+(lock ? " FOR UPDATE" : ""))
            .param("ws",ws).param("id",id).query((rs,n) -> new Stored(new Job(Sql.uuid(rs,"id"),rs.getString("file_name"),rs.getString("status"),
                json.convert(json.parse(rs.getString("report")),Report.CompatibilityReport.class),Sql.uuid(rs,"converted_workflow_id"),Sql.instant(rs,"created_at"),
                rs.getString("source_map") == null ? json.toTree(null) : json.parse(rs.getString("source_map"))),
                rs.getString("source_format"),rs.getBytes("source"),rs.getString("mappings"))).optional().orElseThrow(ApiException::notFound);
    }
    private static ApiException sourceProblem(SourceException e) { return new ApiException(e.tooLarge() ? ProblemCode.PAYLOAD_TOO_LARGE : ProblemCode.SOURCE_INVALID,e.getMessage()); }
    private void audit(Caller caller,UUID ws,UUID id,String action,Map<String,Object> metadata) {
        audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.USER,caller.subjectId(),action,"migration",id.toString(),AuditWriter.Outcome.SUCCESS,metadata));
    }
}
