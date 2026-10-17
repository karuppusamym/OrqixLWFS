package com.orqix.runtime.api;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.orqix.identity.CallerResolver;
import com.orqix.runtime.internal.HackathonEvidence;
import com.orqix.shared.json.Json;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/runs/{runId}")
public class HackathonController {
    private final CallerResolver callers;
    private final HackathonEvidence evidence;
    private final Json json;
    public HackathonController(CallerResolver callers,HackathonEvidence evidence,Json json) {this.callers=callers;this.evidence=evidence;this.json=json;}
    @GetMapping("/mail")
    public ObjectNode mail(@PathVariable UUID workspaceId,@PathVariable UUID runId) {
        return evidence.mail(callers.current(),workspaceId,runId);
    }
    @GetMapping("/evidence")
    public ResponseEntity<byte[]> export(@PathVariable UUID workspaceId,@PathVariable UUID runId,
            @RequestParam(defaultValue="json") String format) {
        if(!java.util.Set.of("json","csv").contains(format))throw new com.orqix.shared.problem.ApiException(
                com.orqix.shared.problem.ProblemCode.VALIDATION_FAILED,"Choose json or csv.");
        ObjectNode bundle=evidence.export(callers.current(),workspaceId,runId);
        String content=json.write(bundle);
        if("csv".equals(format)) {
            StringBuilder csv=new StringBuilder("recordType,sequenceOrId,at,actionOrType,actor,metadata\r\n");
            csv.append("manifest,,,,,").append(cell(json.write(bundle.path("manifest")))).append("\r\n");
            for(String type:java.util.List.of("audit","events"))for(var row:bundle.path(type))csv.append(type).append(',')
                    .append(cell(row.path("sequence").asString(row.path("id").asString("")))).append(',')
                    .append(cell(row.path("at").asString())).append(',').append(cell(row.path("action").asString(row.path("type").asString("")))).append(',')
                    .append(cell(row.path("actor").asString(""))).append(',').append(cell(json.write(row))).append("\r\n");
            content=csv.toString();
        }
        return ResponseEntity.ok().header("Content-Type","csv".equals(format)?"text/csv;charset=UTF-8":"application/json")
                .header("Content-Disposition","attachment; filename=\"case-"+runId+"-evidence."+format+"\"")
                .header("Cache-Control","no-store").body(content.getBytes(StandardCharsets.UTF_8));
    }
    private static String cell(String value) {
        if(!value.isEmpty() && "=+-@\t\r\n".indexOf(value.charAt(0))>=0)value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
}
