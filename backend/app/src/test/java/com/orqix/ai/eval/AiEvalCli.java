package com.orqix.ai.eval;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.orqix.ai.internal.DraftRules;
import com.orqix.ai.internal.PromptLibrary;
import com.orqix.compiler.CompileContext;
import com.orqix.compiler.Compilers;
import com.orqix.compiler.ValidationIssue;
import com.orqix.shared.json.Json;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bridge for {@code tools/ai-eval}: exposes the backend's exact prompt construction ({@link PromptLibrary}) and draft
 * rules ({@link DraftRules}: post-processing, compiler validation, binding-issue classification, repair message) over
 * JSON lines on stdin/stdout, so the evaluation measures what the product would accept. No network, no Spring.
 *
 * <p>Requests (one JSON object per line):
 * <ul>
 *   <li>{@code {"op":"messages","workspace":<path>,"description":"..."}} returns
 *       {@code {system, user, responseSchema, promptVersion}}</li>
 *   <li>{@code {"op":"evaluate","workspace":<path>,"content":"<assistant reply>","name":null}} returns
 *       {@code {status, definition, assumptions, missingConfiguration, repairable, issues, notAWorkflow,
 *       repairMessage}}</li>
 *   <li>{@code {"op":"explain","definition":{...}}} returns {@code {system, user, responseSchema}}</li>
 * </ul>
 * Workspace file: {@code {selfApprovalAllowed, groups:[{key,name,memberCount}], connections:[{file, bound}]}} with
 * connection files relative to the workspace file.
 */
public final class AiEvalCli {

    private AiEvalCli() {
    }

    public static void main(String[] args) throws Exception {
        JsonMapper mapper = JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
        Json json = new Json(mapper);
        PromptLibrary prompts = new PromptLibrary(json);
        DraftRules rules = new DraftRules(json, Compilers.workflowCompiler());
        Map<String, DraftRules.Workspace> workspaces = new LinkedHashMap<>();
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        out.println("{\"ready\":true}");
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            if (line.isBlank()) {
                continue;
            }
            ObjectNode response = json.object();
            try {
                JsonNode request = json.parse(line);
                String op = request.path("op").asString("");
                switch (op) {
                    case "messages" -> {
                        DraftRules.Workspace workspace = workspaces.computeIfAbsent(request.path("workspace").asString(),
                                path -> load(json, Path.of(path)));
                        response.put("system", prompts.draftSystem());
                        response.put("user", PromptLibrary.draftUser(rules.workspaceContext(workspace),
                                request.path("description").asString()));
                        response.set("responseSchema", prompts.draftEnvelopeSchema());
                        response.put("promptVersion", PromptLibrary.DRAFT_VERSION);
                    }
                    case "evaluate" -> {
                        DraftRules.Workspace workspace = workspaces.computeIfAbsent(request.path("workspace").asString(),
                                path -> load(json, Path.of(path)));
                        DraftRules.Evaluation evaluation = rules.evaluate(request.path("content").asString(""),
                                request.path("name").asString(null), workspace);
                        response.put("status", evaluation.status().name());
                        response.set("definition", evaluation.definition());
                        response.set("assumptions", json.toTree(evaluation.assumptions()));
                        response.set("missingConfiguration", json.toTree(evaluation.missing()));
                        response.set("repairable", issues(json, evaluation.repairable()));
                        response.set("issues", evaluation.report() == null ? json.object().putArray("none")
                                : issues(json, evaluation.report().issues()));
                        response.put("notAWorkflow", evaluation.notAWorkflow());
                        response.put("repairMessage", evaluation.repairable().isEmpty() ? null
                                : DraftRules.repairMessage(evaluation.repairable()));
                    }
                    case "explain" -> {
                        response.put("system", prompts.explainSystem());
                        response.put("user", prompts.explainUser(request.path("definition")));
                        response.set("responseSchema", prompts.explanationSchema());
                        response.put("promptVersion", PromptLibrary.EXPLAIN_VERSION);
                    }
                    default -> response.put("error", "unknown op " + op);
                }
            } catch (RuntimeException e) {
                response.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            out.println(json.write(response));
        }
    }

    private static ArrayNode issues(Json json, List<ValidationIssue> issues) {
        ArrayNode array = json.object().putArray("issues");
        for (ValidationIssue issue : issues) {
            ObjectNode node = array.addObject();
            node.put("code", issue.code());
            node.put("severity", issue.severity().apiValue());
            node.put("message", issue.message());
            node.put("path", issue.path());
            node.put("nodeId", issue.nodeId());
        }
        return array;
    }

    private static DraftRules.Workspace load(Json json, Path file) {
        try {
            JsonNode spec = json.parse(Files.readString(file));
            Map<String, CompileContext.GroupInfo> groupInfos = new LinkedHashMap<>();
            List<DraftRules.GroupFact> groups = new ArrayList<>();
            for (JsonNode group : spec.path("groups")) {
                String key = group.path("key").asString();
                groupInfos.put(key, new CompileContext.GroupInfo(key, group.path("name").asString(key),
                        group.path("memberCount").asInt(1)));
                groups.add(new DraftRules.GroupFact(key, group.path("name").asString(key)));
            }
            List<CompileContext.ConnectionVersion> versions = new ArrayList<>();
            List<DraftRules.ConnectionFact> connections = new ArrayList<>();
            java.util.Set<String> bound = new java.util.HashSet<>();
            for (JsonNode connection : spec.path("connections")) {
                JsonNode document = json.parse(Files.readString(file.getParent().resolve(connection.path("file").asString())));
                String key = document.path("key").asString();
                int version = document.path("version").asInt();
                String text = json.write(document);
                versions.add(new CompileContext.ConnectionVersion(key, version, text, Json.canonicalSha256(text)));
                connections.add(new DraftRules.ConnectionFact(key, version, document.path("name").asString(key), document));
                if (connection.path("bound").asBoolean(true)) {
                    bound.add(key);
                }
            }
            CompileContext context = new CompileContext(spec.path("selfApprovalAllowed").asBoolean(false), groupInfos,
                    versions, Set.copyOf(bound));
            return new DraftRules.Workspace(groups, connections, context);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Cannot read workspace " + file, e);
        }
    }
}
