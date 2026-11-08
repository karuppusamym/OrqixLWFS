package com.orqix.ai.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.ai.ocr.OcrProviders;
import com.orqix.ai.skills.AiSkills;
import com.orqix.compiler.DocumentTemplates;
import com.orqix.identity.CallerResolver;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Document extraction settings (CG-A06, contracts/openapi.yaml tag Completion): the workspace's OCR provider and the
 * built-in document-type templates.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class DocumentExtractionController {

    private final CallerResolver callers;
    private final OcrProviders ocr;
    private final AiSkills skills;
    private final Json json;

    public DocumentExtractionController(CallerResolver callers, OcrProviders ocr, AiSkills skills, Json json) {
        this.callers = callers;
        this.ocr = ocr;
        this.skills = skills;
        this.json = json;
    }

    @GetMapping("/ai-ocr-settings")
    public ObjectNode getAiOcrSettings(@PathVariable UUID workspaceId) {
        return ocr.view(callers.current(), workspaceId);
    }

    @PutMapping("/ai-ocr-settings")
    public ObjectNode updateAiOcrSettings(@PathVariable UUID workspaceId, @RequestBody(required = false) JsonNode body) {
        return ocr.update(callers.current(), workspaceId, body);
    }

    @GetMapping("/ai-document-templates")
    public ObjectNode listAiDocumentTemplates(@PathVariable UUID workspaceId) {
        skills.list(callers.current(), workspaceId); // same visibility as the skills
        ObjectNode result = json.object();
        ArrayNode items = result.putArray("items");
        for (DocumentTemplates.Template t : DocumentTemplates.builtIns()) {
            ObjectNode o = items.addObject();
            o.put("type", t.type()).put("label", t.label());
            ArrayNode fields = o.putArray("fields");
            for (DocumentTemplates.Field f : t.fields()) {
                ObjectNode field = fields.addObject();
                field.put("name", f.name()).put("type", f.type()).put("required", f.required());
                if (f.description() == null) {
                    field.putNull("description");
                } else {
                    field.put("description", f.description());
                }
                if (f.hint() == null) {
                    field.putNull("hint");
                } else {
                    field.put("hint", f.hint());
                }
            }
            ArrayNode checks = o.putArray("checks");
            for (DocumentTemplates.Check c : t.checks()) {
                ObjectNode check = checks.addObject();
                check.put("kind", c.kind()).put("target", c.target());
                ArrayNode plus = check.putArray("plus");
                c.plus().forEach(plus::add);
                ArrayNode minus = check.putArray("minus");
                c.minus().forEach(minus::add);
                if (c.other() == null) {
                    check.putNull("other");
                } else {
                    check.put("other", c.other());
                }
                check.put("tolerance", c.tolerance());
            }
        }
        return result;
    }
}
