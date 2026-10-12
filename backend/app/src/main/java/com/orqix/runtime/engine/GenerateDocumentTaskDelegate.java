package com.orqix.runtime.engine;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

import com.orqix.integration.DocumentGeneration;
import com.orqix.runtime.internal.JobFailure;
import com.orqix.runtime.internal.RunAttachments;
import com.orqix.runtime.internal.RunEvents;
import com.orqix.runtime.internal.RunStore;
import com.orqix.shared.json.Json;

@Component("orqixGenerateDocumentTask")
public final class GenerateDocumentTaskDelegate implements JavaDelegate {
    private final Json json;
    private final DocumentGeneration generation;
    private final RunAttachments attachments;
    private final RunEvents events;
    private final RunStore runs;
    public GenerateDocumentTaskDelegate(Json json, DocumentGeneration generation, RunAttachments attachments,
            RunEvents events, RunStore runs) {
        this.json = json; this.generation = generation; this.attachments = attachments; this.events = events; this.runs = runs;
    }
    @Override public void execute(DelegateExecution execution) {
        var element = execution.getCurrentFlowElement();
        var config = OrqixModel.requireExtension(element, "generateDocument");
        var values = json.object();
        OrqixModel.childMap(config, "input", "fieldRef").forEach((name, ref) -> values.set(name, ProcessData.resolve(execution, json, ref)));
        var workspaceId = ProcessData.workspaceId(execution); var runId = ProcessData.runId(execution);
        String format = OrqixModel.attribute(config, "format");
        try {
            var generated = generation.generate(format, OrqixModel.attribute(config, "fileName"),
                    new String(Base64.getDecoder().decode(OrqixModel.attribute(config, "templateBase64")), StandardCharsets.UTF_8), values);
            var id = attachments.addGenerated(workspaceId, runId, ProcessData.initiator(execution), element.getId(), generated);
            execution.setVariable(ProcessData.stepVariable(element.getId()), json.write(json.object().put("attachmentId", id.toString()).put("format", format)));
            runs.mutate(workspaceId, runId, m -> { m.removeStep(element.getId()); m.settle(); });
            events.append(workspaceId, runId, "DOCUMENT_GENERATED", element.getId(), null,
                    Map.of("attachmentId", id.toString(), "format", format));
        } catch (IllegalArgumentException | com.orqix.shared.problem.ApiException e) {
            throw new JobFailure(false, "RESPONSE_INVALID", "DOCUMENT_GENERATION_REJECTED", "permanent", null, 1, null,
                    "The document could not be generated. Check its mapped fields, format and request file limits.");
        }
    }
}
