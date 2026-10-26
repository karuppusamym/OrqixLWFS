package com.orqix.ai.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Versioned prompts (S10 section 3 step 2): {@code ai/prompts/draft-v1.md} with the packaged workflow-v0 schema and
 * the purchase-approval example inlined, and {@code ai/prompts/explain-v2.md}. The system prompts are static, so
 * providers can cache them; workspace context and the (untrusted) description go into the user turn.
 */
@Component
public class PromptLibrary {

    public static final String DRAFT_VERSION = "draft-v1";
    public static final String EXPLAIN_VERSION = "explain-v2";
    public static final String FORM_VERSION = "form-v1";

    private final Json json;
    private final String draftSystem;
    private final String explainSystem;
    private final ObjectNode draftEnvelopeSchema;
    private final ObjectNode explanationSchema;
    private final String formSystem;
    private final ObjectNode formSuggestionSchema;

    public PromptLibrary(Json json) {
        this.json = json;
        ObjectNode example = (ObjectNode) json.parse(load("templates/purchase-approval.json"));
        example.remove("uiLayout");
        String schema = json.write(json.parse(load("orqix/schemas/workflow-v0.schema.json")));
        this.draftSystem = load("ai/prompts/" + DRAFT_VERSION + ".md")
                .replace("{{EXAMPLE}}", json.write(example))
                .replace("{{WORKFLOW_SCHEMA}}", schema);
        this.explainSystem = load("ai/prompts/" + EXPLAIN_VERSION + ".md");
        this.draftEnvelopeSchema = buildDraftEnvelopeSchema(json);
        this.explanationSchema = buildExplanationSchema(json);
        this.formSystem = load("ai/prompts/" + FORM_VERSION + ".md");
        this.formSuggestionSchema = buildFormSuggestionSchema(json);
    }

    public String draftSystem() {
        return draftSystem;
    }

    public String explainSystem() {
        return explainSystem;
    }

    public String formSystem() {
        return formSystem;
    }

    /** Response format of form suggestions (S90). */
    public JsonNode formSuggestionSchema() {
        return formSuggestionSchema;
    }

    /** User turn of a form suggestion: the current request form, then the untrusted description in its own block. */
    public static String formUser(String formJson, String description) {
        return "<form>\n" + fence(formJson, "form") + "\n</form>\n\n<description>\n" + fence(description, "description")
                + "\n</description>\n\nReply with the JSON object only.";
    }

    /** User turn of a draft generation: workspace facts, then the untrusted description in its own block. */
    public static String draftUser(String workspaceContextJson, String description) {
        return "<workspace>\n" + workspaceContextJson + "\n</workspace>\n\n<description>\n" + fence(description, "description")
                + "\n</description>\n\nReply with the JSON object only.";
    }

    /** User turn of an explanation: the canonical definition without presentation data. */
    public String explainUser(JsonNode definition, JsonNode validation) {
        ObjectNode copy = definition.isObject() ? (ObjectNode) definition.deepCopy() : json.object();
        copy.remove("uiLayout");
        copy.remove("inputUi");
        return "<workflow>\n" + json.write(copy) + "\n</workflow>\n<validation>\n" + json.write(validation)
                + "\n</validation>\n\nThe validation result is authoritative for readiness. Describe missing configuration explicitly; do not call unavailable groups or integrations ready. Reply with the JSON object only.";
    }

    /** Response format of draft replies (structured outputs): the envelope; the document is checked by the compiler. */
    public JsonNode draftEnvelopeSchema() {
        return draftEnvelopeSchema;
    }

    /** Response format of explanation replies. */
    public JsonNode explanationSchema() {
        return explanationSchema;
    }

    private static ObjectNode buildDraftEnvelopeSchema(Json json) {
        ObjectNode schema = json.object();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("definition").putArray("type").add("object").add("null");
        properties.putObject("assumptions").put("type", "array").putObject("items").put("type", "string");
        ObjectNode missing = properties.putObject("missingConfiguration");
        missing.put("type", "array");
        ObjectNode item = missing.putObject("items");
        item.put("type", "object");
        ObjectNode itemProperties = item.putObject("properties");
        itemProperties.putObject("message").put("type", "string");
        itemProperties.putObject("nodeId").putArray("type").add("string").add("null");
        item.putArray("required").add("message");
        properties.putObject("notAWorkflow").putArray("type").add("string").add("null");
        ArrayNode required = schema.putArray("required");
        required.add("definition").add("assumptions").add("missingConfiguration");
        return schema;
    }

    private static ObjectNode buildExplanationSchema(Json json) {
        ObjectNode schema = json.object();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("summary").put("type", "string");
        ObjectNode steps = properties.putObject("steps");
        steps.put("type", "array");
        ObjectNode item = steps.putObject("items");
        item.put("type", "object");
        ObjectNode itemProperties = item.putObject("properties");
        itemProperties.putObject("nodeId").put("type", "string");
        itemProperties.putObject("text").put("type", "string");
        item.putArray("required").add("nodeId").add("text");
        schema.putArray("required").add("summary").add("steps");
        return schema;
    }

    private static ObjectNode buildFormSuggestionSchema(Json json) {
        ObjectNode schema = json.object();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode changes = properties.putObject("changes");
        changes.put("type", "array");
        ObjectNode item = changes.putObject("items");
        item.put("type", "object");
        ObjectNode itemProperties = item.putObject("properties");
        itemProperties.putObject("op").putArray("enum").add("add").add("update").add("remove");
        itemProperties.putObject("key").put("type", "string");
        itemProperties.putObject("field").putArray("type").add("object").add("null");
        itemProperties.putObject("required").putArray("type").add("boolean").add("null");
        itemProperties.putObject("reason").put("type", "string");
        item.putArray("required").add("op").add("key").add("reason");
        properties.putObject("notes").put("type", "array").putObject("items").put("type", "string");
        properties.putObject("notAForm").putArray("type").add("string").add("null");
        schema.putArray("required").add("changes").add("notes");
        return schema;
    }

    /** Neutralizes a closing tag inside untrusted text so it cannot end its block early. */
    static String fence(String text, String tag) {
        return text.replace("</" + tag, "<\\/" + tag).replace("<" + tag, "<\\" + tag);
    }

    private static String load(String resource) {
        try (InputStream in = PromptLibrary.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }
}
