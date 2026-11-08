package com.orqix.compiler;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import com.orqix.compiler.internal.json.Jcs;
import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;

/**
 * The AI skill contract ({@code contracts/ai-skill-v1.schema.json}, docs/build/S134): schema and semantic checks of
 * skill versions and evaluation suites, the content hash a publish pins, and the compile facts of a skill version.
 * Pure Java, shared by registration (application) and the compiler, so both read a skill the same way.
 */
public final class AiSkillContracts {

    /** A problem at a JSON Pointer inside the document. */
    public record Problem(String pointer, String message) {
    }

    private static final String ID = "https://orqix.example/schemas/ai-skill-v1.schema.json";
    private static final Schema SCHEMA = load();

    private AiSkillContracts() {
    }

    private static Schema load() {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, b -> b
                .schemaRegistryConfig(SchemaRegistryConfig.builder().pathType(PathType.JSON_POINTER).build())
                .schemaLoader(loader -> loader.fetchRemoteResources(false)));
        try (InputStream in = AiSkillContracts.class.getClassLoader()
                .getResourceAsStream("orqix/schemas/ai-skill-v1.schema.json")) {
            if (in == null) {
                throw new IllegalStateException("ai-skill-v1.schema.json is not packaged");
            }
            Schema schema = registry.getSchema(SchemaLocation.of(ID), StrictJson.parse(new String(in.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8)));
            schema.initializeValidators();
            return schema;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return JCS SHA-256 of the document (what a publish pins) */
    public static String contentHash(JsonNode document) {
        return Jcs.sha256Hex(document);
    }

    /** Problems of a skill version document ({@code kind: skill}); empty when valid. */
    public static List<Problem> checkSkill(JsonNode document) {
        List<Problem> problems = schema(document, "skill");
        if (!problems.isEmpty()) {
            return problems;
        }
        String mode = document.path("mode").asString();
        Set<String> labels = keys(document.path("labels"), "key", "/labels", "label", problems);
        keys(document.path("fields"), "name", "/fields", "field", problems);
        keys(document.path("outcomes"), "key", "/outcomes", "outcome", problems);
        for (String member : List.of("labels", "fields", "score", "outcomes", "summary", "document")) {
            boolean belongs = switch (member) {
                case "labels" -> mode.equals("classify");
                case "fields", "document" -> mode.equals("extract");
                case "score" -> mode.equals("score");
                case "outcomes" -> mode.equals("recommend");
                default -> mode.equals("summarize");
            };
            if (document.has(member) && !belongs) {
                problems.add(new Problem("/" + member, "A " + mode + " skill has no " + member + "."));
            }
        }
        if (document.has("score") && document.path("score").path("min").decimalValue()
                .compareTo(document.path("score").path("max").decimalValue()) >= 0) {
            problems.add(new Problem("/score/max", "The score maximum must be above the minimum."));
        }
        for (String label : document.path("evaluation").path("minRecall").propertyNames()) {
            if (!labels.contains(label)) {
                problems.add(new Problem("/evaluation/minRecall/" + label, "There is no label " + label + "."));
            }
        }
        documentProblems(document, problems);
        if (document.path("confidence").path("samples").asInt(1) > 1
                && !"selfConsistency".equals(document.path("confidence").path("method").asString("single"))) {
            problems.add(new Problem("/confidence/samples", "Several samples need the method selfConsistency."));
        }
        return problems;
    }

    /**
     * Document skills (CG-A06): exactly the declared {@code attachmentInput} has type {@code attachment}, and a
     * {@code custom} document declares its own fields.
     */
    private static void documentProblems(JsonNode skill, List<Problem> problems) {
        String attachmentInput = DocumentTemplates.attachmentInput(skill);
        for (var e : skill.path("input").properties()) {
            boolean attachment = "attachment".equals(e.getValue().path("type").asString());
            if (attachment && !e.getKey().equals(attachmentInput)) {
                problems.add(new Problem("/input/" + e.getKey() + "/type", "An attachment input is allowed only as the"
                        + " document input of an extract skill (document.attachmentInput)."));
            }
        }
        if (attachmentInput != null) {
            JsonNode declared = skill.path("input").path(attachmentInput);
            if (!"attachment".equals(declared.path("type").asString(""))) {
                problems.add(new Problem("/document/attachmentInput", "The document input " + attachmentInput
                        + " must be an input of type attachment."));
            }
            if ("custom".equals(DocumentTemplates.documentType(skill)) && !skill.path("fields").isArray()) {
                problems.add(new Problem("/fields", "A custom document declares the fields to extract."));
            }
        }
    }

    /** Problems of an evaluation suite ({@code kind: evaluationSuite}); empty when valid. */
    public static List<Problem> checkSuite(JsonNode document) {
        List<Problem> problems = schema(document, "evaluationSuite");
        if (problems.isEmpty()) {
            Set<String> ids = new HashSet<>();
            int i = 0;
            for (JsonNode c : document.path("cases")) {
                if (!ids.add(c.path("id").asString())) {
                    problems.add(new Problem("/cases/" + i + "/id", "The case id " + c.path("id").asString()
                            + " is used more than once."));
                }
                i++;
            }
        }
        return problems;
    }

    /** Problems of a suite against the skill it evaluates (labels, fields, input names). */
    public static List<Problem> checkSuiteFor(JsonNode suite, JsonNode skill) {
        List<Problem> problems = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        skill.path("labels").forEach(l -> labels.add(l.path("key").asString()));
        Set<String> outcomes = new HashSet<>();
        skill.path("outcomes").forEach(o -> outcomes.add(o.path("key").asString()));
        Set<String> inputs = new HashSet<>(skill.path("input").propertyNames());
        String mode = skill.path("mode").asString();
        int i = 0;
        for (JsonNode c : suite.path("cases")) {
            String at = "/cases/" + i;
            for (String name : c.path("input").propertyNames()) {
                if (!inputs.contains(name)) {
                    problems.add(new Problem(at + "/input/" + name, "The skill has no input " + name + "."));
                }
            }
            JsonNode expected = c.path("expected");
            switch (mode) {
                case "classify" -> {
                    if (!labels.contains(expected.path("label").asString(""))) {
                        problems.add(new Problem(at + "/expected/label", "Expected labels must be labels of the skill."));
                    }
                }
                case "extract" -> {
                    if (!expected.path("fields").isObject()) {
                        problems.add(new Problem(at + "/expected/fields", "Extraction cases need the expected fields."));
                    }
                }
                case "score" -> {
                    if (!expected.path("score").isNumber()) {
                        problems.add(new Problem(at + "/expected/score", "Score cases need the expected score."));
                    }
                }
                case "recommend" -> {
                    if (!outcomes.contains(expected.path("outcome").asString(""))) {
                        problems.add(new Problem(at + "/expected/outcome", "Expected outcomes must be outcomes of the"
                                + " skill."));
                    }
                }
                default -> {
                    if (!expected.path("mustMention").isArray()) {
                        problems.add(new Problem(at + "/expected/mustMention", "Summary cases list the phrases the"
                                + " summary must mention."));
                    }
                }
            }
            i++;
        }
        return problems;
    }

    /**
     * Compile facts of one stored version.
     *
     * @param passingEvaluationId the passing evaluation run within 30 days on the version's profile, or null
     */
    public static CompileContext.AiSkillVersion facts(int version, String status, JsonNode document, String contentHash,
            String passingEvaluationId, String autonomyCeiling) {
        List<CompileContext.AiLabel> labels = new ArrayList<>();
        document.path("labels").forEach(l -> labels.add(new CompileContext.AiLabel(l.path("key").asString(),
                l.path("risk").asString("medium"), l.path("adverse").asBoolean(false))));
        Map<String, String> inputs = new LinkedHashMap<>();
        Set<String> requiredInputs = new LinkedHashSet<>();
        for (var e : document.path("input").properties()) {
            inputs.put(e.getKey(), e.getValue().path("type").asString());
            if (e.getValue().path("required").asBoolean(false)) {
                requiredInputs.add(e.getKey());
            }
        }
        Map<String, String> fields = new LinkedHashMap<>();
        Set<String> requiredFields = new LinkedHashSet<>();
        // A document skill may take its fields from its document-type template (CG-A06).
        for (DocumentTemplates.Field f : DocumentTemplates.effectiveFields(document)) {
            fields.put(f.name(), f.type());
            if (f.required()) {
                requiredFields.add(f.name());
            }
        }
        List<String> outcomes = new ArrayList<>();
        document.path("outcomes").forEach(o -> outcomes.add(o.path("key").asString()));
        BigDecimal min = document.path("score").has("min") ? document.path("score").path("min").decimalValue() : null;
        BigDecimal max = document.path("score").has("max") ? document.path("score").path("max").decimalValue() : null;
        return new CompileContext.AiSkillVersion(version, status, document.path("mode").asString(), contentHash,
                document.path("modelProfile").asString(), labels, inputs, requiredInputs, fields, requiredFields, min, max,
                outcomes, passingEvaluationId, autonomyCeiling == null ? "suggest" : autonomyCeiling);
    }

    private static List<Problem> schema(JsonNode document, String kind) {
        List<Problem> problems = new ArrayList<>();
        if (!document.isObject() || !kind.equals(document.path("kind").asString(""))) {
            problems.add(new Problem("/kind", "The document must be an AI skill document of kind " + kind + "."));
            return problems;
        }
        for (Error error : SCHEMA.validate(document)) {
            String at = error.getInstanceLocation().toString();
            // oneOf reports the other alternative too; keep the messages about this kind only.
            String eval = error.getEvaluationPath() == null ? "" : error.getEvaluationPath().toString();
            if (eval.contains("/oneOf/") && !eval.contains(kind.equals("skill") ? "/oneOf/0" : "/oneOf/1")) {
                continue;
            }
            problems.add(new Problem(at, error.getMessage()));
        }
        return problems;
    }

    private static Set<String> keys(JsonNode array, String member, String path, String what, List<Problem> problems) {
        Set<String> seen = new LinkedHashSet<>();
        int i = 0;
        for (JsonNode item : array) {
            if (!seen.add(item.path(member).asString())) {
                problems.add(new Problem(path + "/" + i + "/" + member, "The " + what + " "
                        + item.path(member).asString() + " is declared more than once."));
            }
            i++;
        }
        return seen;
    }
}
