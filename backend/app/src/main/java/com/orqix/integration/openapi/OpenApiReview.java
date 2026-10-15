package com.orqix.integration.openapi;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.audit.AuditWriter;
import com.orqix.identity.*;
import com.orqix.shared.problem.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Stateless review: never fetches a server/reference, saves a binding or registers a definition. */
@Service
public class OpenApiReview {
    public record Selection(@NotBlank @Size(max=80) String key,
            @NotNull @Pattern(regexp="readOnly|idempotentWithKey|nonIdempotent") String effectClass,
            @Size(max=64) String idempotencyHeader) {}
    public record Request(@NotBlank @Size(max=2097152) String source, @Size(max=200) String fileName,
            @Pattern(regexp="^[a-z][a-z0-9-]{0,62}[a-z0-9]$") String key, @Min(1) Integer version,
            @Size(min=1,max=100) String name, @Size(max=2000) String description,
            @Size(max=20) List<@NotNull @Valid Selection> selections, Boolean confirmEffects) {}
    public record Result(ImportReport report, JsonNode definition) {}
    private final PolicyService policy;
    private final AuditWriter audit;
    public OpenApiReview(PolicyService policy, AuditWriter audit) { this.policy=policy; this.audit=audit; }

    @Transactional
    public Result review(Caller caller, UUID workspaceId, Request request) {
        var access=policy.freshAccess(caller,workspaceId).filter(WorkspaceAccess::visible).orElseThrow(ApiException::notFound);
        policy.require(access,Permission.CONNECTION_MANAGE,"reviewOpenApiConnection",true);
        OpenApiConverter.Analysis analysis;
        try { analysis=OpenApiConverter.analyze(SpecReader.read(request.source(),request.fileName(),null).document()); }
        catch (SpecReader.SpecException e) { throw new ApiException(e.tooLarge()?ProblemCode.PAYLOAD_TOO_LARGE:ProblemCode.SOURCE_INVALID,e.getMessage()); }
        List<Selection> selected=request.selections()==null?List.of():request.selections();
        ObjectNode definition=null;
        if (!selected.isEmpty()) {
            if (!Boolean.TRUE.equals(request.confirmEffects())) throw invalid("Confirm the retry behavior of every selected operation.");
            if (analysis.report().issues().stream().anyMatch(issue->"blocker".equals(issue.severity())))
                throw invalid("Resolve the document blockers before creating a connection definition.");
            Map<String,ObjectNode> operations=new TreeMap<>();
            for (Selection selection:selected) {
                ObjectNode template=analysis.templates().get(selection.key());
                if (template==null || operations.containsKey(selection.key())) throw invalid("Select unique supported operations from this report.");
                if ("idempotentWithKey".equals(selection.effectClass()) && (selection.idempotencyHeader()==null
                        || !selection.idempotencyHeader().matches("^[A-Za-z][A-Za-z0-9-]{0,63}$")
                        || java.util.Set.of("authorization","cookie","host","content-type","content-length","accept","connection","transfer-encoding")
                            .contains(selection.idempotencyHeader().toLowerCase(java.util.Locale.ROOT))))
                    throw invalid("Provide a dedicated idempotency header for keyed retries.");
                operations.put(selection.key(),OpenApiConverter.finish(template,selection.effectClass(),selection.idempotencyHeader()));
            }
            String key=request.key()==null?analysis.report().suggestedKey():request.key();
            String name=request.name()==null?analysis.report().suggestedName():request.name();
            definition=OpenApiConverter.definition(key,request.version()==null?1:request.version(),name,request.description(),operations);
        }
        audit.write(new AuditWriter.Entry(workspaceId,AuditWriter.ActorType.USER,caller.subjectId(),"connection.openapi-reviewed",
                "workspace",workspaceId.toString(),AuditWriter.Outcome.SUCCESS,
                Map.of("operations",analysis.report().summary().operations(),"selected",selected.size())));
        return new Result(analysis.report(),definition);
    }
    private static ApiException invalid(String message) { return new ApiException(ProblemCode.VALIDATION_FAILED,message); }
}
