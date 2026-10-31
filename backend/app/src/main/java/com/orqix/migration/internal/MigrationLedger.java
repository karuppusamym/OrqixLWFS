package com.orqix.migration.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.migration.camunda.Report;
import com.orqix.migration.ledger.Xlsx;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.web.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The SI migration ledger of one migration (docs/17-migration-workbench.md "Migration ledger"): every source element with
 * its disposition, severity, reason code, target node, source checksum and adapter version, the approved mappings, the
 * independent review, verification acknowledgements and the equivalence evidence the server holds. Built from the stored
 * job only; nothing is re-analysed, so the ledger shows exactly what the conversion recorded.
 */
@Service
public class MigrationLedger {
    public static final String FORMAT = "orqix-migration-ledger-0.1";
    /** Mapping sections of a conversion request keyed by source element id. */
    private static final List<String> ELEMENT_MAPPINGS = List.of("approvalRoutes", "serviceTaskMappings", "childWorkflowMappings",
            "decisionMappings", "formMappings", "connectionMappings");
    static final List<String> COLUMNS = List.of("sourceElementId", "sourceName", "sourceKind", "sourcePath", "disposition", "severity",
            "reasonCode", "resolutionStatus", "targetNodeId", "sourceSha256", "adapterVersion", "approvedMapping",
            "verifiedBy", "verifiedAt", "verificationReason", "explanation", "suggestedAction");

    /** One downloadable file. */
    public record File(String fileName, String contentType, byte[] content) { }

    private record Row(UUID id, String fileName, String status, String format, String sha256, String adapter, String report,
            String mappings, String sourceMap, UUID workflow, UUID mapper, UUID createdBy, Instant createdAt, Instant updatedAt) { }

    private final JdbcClient jdbc; private final Json json; private final PolicyService policy; private final AuditWriter audit;

    public MigrationLedger(JdbcClient jdbc, Json json, PolicyService policy, AuditWriter audit) {
        this.jdbc=jdbc; this.json=json; this.policy=policy; this.audit=audit;
    }

    /** The ledger as JSON, CSV, XLSX, or (default) a ZIP bundle of all three. Needs {@code workflow.export}. */
    @Transactional // not read-only: the export is audited
    public File export(Caller caller, UUID ws, UUID id, String format) {
        var access=policy.requireVisible(caller,ws);
        policy.require(access,Permission.WORKFLOW_EXPORT,"exportMigrationLedger",false);
        String chosen=format == null || format.isBlank() ? "zip" : format;
        if (!Set.of("zip","json","csv","xlsx").contains(chosen)) throw new ApiException(com.orqix.shared.problem.ProblemCode.MALFORMED_REQUEST,"format must be zip, json, csv or xlsx.");
        ObjectNode ledger=ledger(ws,id);
        String base="migration-ledger-"+id.toString().substring(0,8);
        File file=switch (chosen) {
            case "json" -> new File(base+".json","application/json",(json.write(ledger)+"\n").getBytes(StandardCharsets.UTF_8));
            case "csv" -> new File(base+".csv","text/csv;charset=utf-8",csv(ledger).getBytes(StandardCharsets.UTF_8));
            case "xlsx" -> new File(base+".xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",xlsx(ledger));
            default -> new File(base+".zip","application/zip",zip(ledger));
        };
        audit.write(new AuditWriter.Entry(ws,AuditWriter.ActorType.USER,caller.subjectId(),"migration.ledger.exported","migration",id.toString(),
                AuditWriter.Outcome.SUCCESS,Map.of("migrationId",id.toString(),"format",chosen,"elements",ledger.path("elements").size())));
        return file;
    }

    ObjectNode ledger(UUID ws, UUID id) {
        Row row=jdbc.sql("""
                SELECT id,file_name,status,source_format,source_sha256,adapter_version,report::text,mappings::text,source_map::text,
                       converted_workflow_id,converted_by_subject_id,created_by,created_at,updated_at
                FROM migration_job WHERE workspace_id=:ws AND id=:id""").param("ws",ws).param("id",id)
                .query((rs,n) -> new Row(Sql.uuid(rs,"id"),rs.getString("file_name"),rs.getString("status"),rs.getString("source_format"),
                        rs.getString("source_sha256"),rs.getString("adapter_version"),rs.getString("report"),rs.getString("mappings"),
                        rs.getString("source_map"),Sql.uuid(rs,"converted_workflow_id"),Sql.uuid(rs,"converted_by_subject_id"),
                        Sql.uuid(rs,"created_by"),Sql.instant(rs,"created_at"),Sql.instant(rs,"updated_at"))).optional().orElseThrow(ApiException::notFound);
        var report=json.convert(json.parse(row.report()),Report.CompatibilityReport.class);
        JsonNode mappings=row.mappings() == null ? null : json.parse(row.mappings());
        ObjectNode out=json.object();
        out.put("format",FORMAT);
        ObjectNode migration=out.putObject("migration");
        migration.put("id",row.id().toString()); migration.put("fileName",row.fileName()); migration.put("status",row.status());
        migration.put("sourceFormat",row.format()); migration.put("sourceSystem",report.sourceSystem()); migration.put("sourceVersion",report.sourceVersion());
        migration.put("sourceSha256",row.sha256()); migration.put("adapterVersion",row.adapter());
        migration.put("processId",report.processId()); migration.put("processName",report.processName());
        migration.put("uploadedBy",row.createdBy().toString()); migration.put("uploadedAt",row.createdAt().toString());
        migration.put("lastChangedAt",row.updatedAt().toString());
        migration.put("convertedWorkflowId",row.workflow() == null ? null : row.workflow().toString());
        migration.put("mappedBy",row.mapper() == null ? null : row.mapper().toString());
        migration.put("publishable",report.publishable());
        migration.put("awaitingVerification",report.awaitingVerification());
        out.set("elementCounts",json.toTree(report.elementCounts()));
        out.set("assumptions",json.toTree(report.assumptions()));
        Map<String,List<Report.Verification>> verified=new HashMap<>();
        for (var v:report.verifications()) verified.computeIfAbsent(key(v.sourceElementId(),v.reasonCode(),v.sourcePath()),k -> new ArrayList<>()).add(v);
        ArrayNode elements=out.putArray("elements");
        for (var issue:report.issues()) {
            ObjectNode e=elements.addObject();
            e.put("sourceElementId",issue.sourceElementId()); e.put("sourceName",issue.sourceName()); e.put("sourceKind",issue.sourceKind());
            e.put("sourcePath",issue.sourcePath()); e.put("disposition",disposition(issue)); e.put("severity",issue.severity());
            e.put("reasonCode",issue.reasonCode()); e.put("resolutionStatus",issue.resolutionStatus()); e.put("targetNodeId",issue.targetNodeId());
            e.put("sourceSha256",row.sha256()); e.put("adapterVersion",report.adapterVersion());
            ObjectNode mapping=elementMapping(mappings,issue.sourceElementId());
            e.set("approvedMapping",mapping);
            var v=verified.getOrDefault(key(issue.sourceElementId(),issue.reasonCode(),issue.sourcePath()),List.of());
            e.set("verification",v.isEmpty() ? null : json.toTree(v.getLast()));
            e.put("explanation",issue.explanation()); e.put("suggestedAction",issue.suggestedAction());
        }
        out.set("approvedMappings",mappings);
        out.set("sourceMap",row.sourceMap() == null ? null : json.parse(row.sourceMap()));
        out.set("verifications",json.toTree(report.verifications()));
        out.set("independentReview",review(ws,row.workflow()));
        ObjectNode equivalence=out.putObject("equivalence");
        equivalence.set("generatedChecks",json.toTree(report.testCases()));
        ObjectNode golden=equivalence.putObject("goldenScenarios");
        golden.put("status","notRecorded");
        golden.put("note","Golden-scenario results from tools/migration-equivalence are produced as a separate evidence ZIP and are not stored by the server. Attach that pack to this ledger.");
        return out;
    }

    /** The latest independent review of the converted workflow (docs/17 "Independent review"), or null. */
    private JsonNode review(UUID ws, UUID workflow) {
        if (workflow == null) return null;
        return jdbc.sql("SELECT draft_revision,version,definition_hash,source_hash,mappings_hash,reviewed_by,reviewed_at,reason FROM migration_review WHERE workspace_id=:ws AND workflow_id=:wf ORDER BY draft_revision DESC,version DESC LIMIT 1")
                .param("ws",ws).param("wf",workflow).query((rs,n) -> {
                    ObjectNode r=json.object();
                    r.put("draftRevision",rs.getInt("draft_revision")); r.put("reviewVersion",rs.getInt("version"));
                    r.put("definitionHash",rs.getString("definition_hash")); r.put("sourceHash",rs.getString("source_hash"));
                    r.put("mappingsHash",rs.getString("mappings_hash")); r.put("reviewedBy",String.valueOf(Sql.uuid(rs,"reviewed_by")));
                    r.put("reviewedAt",Sql.instant(rs,"reviewed_at").toString()); r.put("reason",rs.getString("reason"));
                    return (JsonNode)r;
                }).optional().orElse(null);
    }

    private ObjectNode elementMapping(JsonNode mappings, String elementId) {
        if (mappings == null || elementId == null) return null;
        ObjectNode found=null;
        for (String section:ELEMENT_MAPPINGS) {
            JsonNode value=mappings.path(section).path(elementId);
            if (value.isMissingNode() || value.isNull()) continue;
            if (found == null) found=json.object();
            found.set(section,value);
        }
        JsonNode group=mappings.path("groupMappings").path(elementId);
        if (!group.isMissingNode() && !group.isNull()) { if (found == null) found=json.object(); found.set("groupMappings",group); }
        return found;
    }

    /** What happened to the element, in plain words: one value per severity and resolution. */
    static String disposition(Report.Issue issue) {
        boolean open=Report.OPEN.equals(issue.resolutionStatus());
        return switch (issue.severity()) {
            case Report.SUPPORTED -> "converted";
            case Report.REVIEW -> "convertedReview";
            case Report.VERIFY -> open ? "convertedChangedUnverified" : "convertedChangedVerified";
            case Report.INFO -> "notImported";
            case Report.ACTION -> open ? "decisionNeeded" : "mapped";
            case Report.BLOCKER -> open ? "manualRebuild" : "mapped";
            default -> issue.severity();
        };
    }

    private static String key(String element, String code, String path) { return element+"\u0000"+code+"\u0000"+path; }

    private List<List<String>> elementRows(ObjectNode ledger) {
        List<List<String>> rows=new ArrayList<>();
        rows.add(COLUMNS);
        for (JsonNode e:ledger.path("elements")) {
            List<String> row=new ArrayList<>();
            for (String c:COLUMNS) {
                row.add(switch (c) {
                    case "approvedMapping" -> e.path(c).isObject() ? json.write(e.path(c)) : "";
                    case "verifiedBy" -> e.path("verification").path("acknowledgedByName").asString(e.path("verification").path("acknowledgedBy").asString(""));
                    case "verifiedAt" -> e.path("verification").path("acknowledgedAt").asString("");
                    case "verificationReason" -> e.path("verification").path("reason").asString("");
                    default -> e.path(c).isNull() || e.path(c).isMissingNode() ? "" : e.path(c).asString("");
                });
            }
            rows.add(row);
        }
        return rows;
    }

    String csv(ObjectNode ledger) {
        StringBuilder out=new StringBuilder();
        for (List<String> row:elementRows(ledger)) {
            StringJoiner line=new StringJoiner(",");
            for (String value:row) line.add(cell(value));
            out.append(line).append("\r\n");
        }
        return out.toString();
    }

    /** Quotes CSV syntax; a leading apostrophe keeps spreadsheets from reading untrusted text as a formula. */
    static String cell(String value) {
        String text=value == null ? "" : value;
        if (text.matches("(?s)^\\s*[=+@-].*") || text.matches("(?s)^[\\t\\r\\n].*")) text="'"+text;
        return "\""+text.replace("\"","\"\"")+"\"";
    }

    byte[] xlsx(ObjectNode ledger) {
        JsonNode m=ledger.path("migration");
        List<List<String>> summary=new ArrayList<>();
        summary.add(List.of("field","value"));
        m.properties().forEach(e -> summary.add(List.of(e.getKey(),e.getValue().isNull() ? "" : e.getValue().asString(""))));
        ledger.path("elementCounts").properties().forEach(e -> summary.add(List.of("count."+e.getKey(),e.getValue().asString(""))));
        summary.add(List.of("ledgerFormat",FORMAT));
        JsonNode review=ledger.path("independentReview");
        if (review.isObject()) review.properties().forEach(e -> summary.add(List.of("independentReview."+e.getKey(),e.getValue().asString(""))));
        else summary.add(List.of("independentReview","none recorded"));
        summary.add(List.of("goldenScenarios",ledger.path("equivalence").path("goldenScenarios").path("note").asString("")));
        List<List<String>> mappings=new ArrayList<>();
        mappings.add(List.of("section","sourceKey","mapping"));
        JsonNode approved=ledger.path("approvedMappings");
        if (approved.isObject()) approved.properties().forEach(section -> {
            if (section.getValue().isObject()) section.getValue().properties().forEach(entry -> mappings.add(List.of(section.getKey(),entry.getKey(),entry.getValue().isString() ? entry.getValue().asString() : json.write(entry.getValue()))));
            else mappings.add(List.of(section.getKey(),"",section.getValue().isString() ? section.getValue().asString() : json.write(section.getValue())));
        });
        List<List<String>> checks=new ArrayList<>();
        checks.add(List.of("decision","case","input","expected","actual","matchedRow","status"));
        for (JsonNode c:ledger.path("equivalence").path("generatedChecks"))
            checks.add(List.of(c.path("decision").asString(""),c.path("name").asString(""),json.write(c.path("input")),json.write(c.path("expected")),
                    json.write(c.path("actual")),c.path("matchedRow").isNull() ? "" : c.path("matchedRow").asString(""),c.path("status").asString("")));
        List<List<String>> verifications=new ArrayList<>();
        verifications.add(List.of("sourceElementId","reasonCode","sourcePath","acknowledgedBy","acknowledgedByName","acknowledgedAt","reason"));
        for (JsonNode v:ledger.path("verifications"))
            verifications.add(List.of(v.path("sourceElementId").asString(""),v.path("reasonCode").asString(""),v.path("sourcePath").asString(""),
                    v.path("acknowledgedBy").asString(""),v.path("acknowledgedByName").asString(""),v.path("acknowledgedAt").asString(""),v.path("reason").asString("")));
        return new Xlsx().sheet("Elements",elementRows(ledger)).sheet("Summary",summary).sheet("Mappings",mappings)
                .sheet("Verifications",verifications).sheet("Equivalence",checks).bytes();
    }

    private byte[] zip(ObjectNode ledger) {
        var out=new ByteArrayOutputStream();
        try (var zip=new ZipOutputStream(out,StandardCharsets.UTF_8)) {
            put(zip,"ledger.json",(json.write(ledger)+"\n").getBytes(StandardCharsets.UTF_8));
            put(zip,"ledger.csv",csv(ledger).getBytes(StandardCharsets.UTF_8));
            put(zip,"ledger.xlsx",xlsx(ledger));
            put(zip,"README.txt",("Orqix migration ledger ("+FORMAT+") for "+ledger.path("migration").path("fileName").asString("")+"\r\n"
                    +"Source SHA-256 "+ledger.path("migration").path("sourceSha256").asString("")+", adapter "+ledger.path("migration").path("adapterVersion").asString("")+".\r\n"
                    +"ledger.json is the complete record; ledger.csv lists one row per source finding; ledger.xlsx holds the same rows plus summary,\r\n"
                    +"mappings, verifications and generated equivalence checks. Golden-scenario results are a separate evidence pack.\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        var entry=new ZipEntry(name); entry.setTimeLocal(Xlsx.FIXED_TIME); zip.putNextEntry(entry); zip.write(content); zip.closeEntry();
    }
}
