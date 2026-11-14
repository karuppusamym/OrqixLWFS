package com.orqix.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.orqix.ai.AiProperties;
import com.orqix.ai.internal.ProviderException;
import com.orqix.shared.json.Json;
import com.orqix.support.AiTaskStub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-A04 (docs/build/S134 section 1): each provider protocol against the WireMock stand-in. Checks what is sent
 * (authentication header, strict JSON schema, prompt caching, system text, deployment and API version, SigV4) and
 * how answers and usage are read. No Spring context, no real provider.
 */
class ModelProvidersTest {

    private final Json json = new Json(JsonMapper.builder().build());
    private final ProviderHttp http = new ProviderHttp(json);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC);
    private final AiProperties ai = new AiProperties("", URI.create("https://openrouter.ai/api/v1"), null, "economy",
            "auto", null, null, null, null, null, null, null, 2, 8000, null, null, null, null, null);

    @BeforeEach
    void reset() {
        AiTaskStub.reset();
    }

    private ObjectNode schema() {
        ObjectNode schema = json.object();
        schema.put("type", "object").putObject("properties").putObject("label").put("type", "string");
        schema.putArray("required").add("label");
        schema.put("additionalProperties", false);
        return schema;
    }

    private ModelProvider.ModelRequest request(String model) {
        return new ModelProvider.ModelRequest(model, "You perform one classify task for a business workflow.",
                List.of(new ModelProvider.Turn("user", "<request_data>{\"purpose\":\"Replacement\"}</request_data>")),
                "answer", schema(), 400, 0.0, Duration.ofSeconds(5));
    }

    private ProviderProperties.Endpoint endpoint(String kind, String path, String region, String project,
            Map<String, String> deployments) {
        return new ProviderProperties.Endpoint(kind, URI.create(AiTaskStub.baseUrl() + path), "ref", region, project,
                null, null, deployments, List.of("eu"), true, false, Duration.ofSeconds(5));
    }

    @Test
    void openAiCompatibleSendsBearerAndStrictSchema() {
        ModelProvider.ModelResponse r = new OpenAiCompatibleProvider(http, ai).complete(
                endpoint("openaiCompatible", "/openai", "", "", Map.of()), AiTaskStub.KEY, request("test/model"));
        assertThat(r.content()).contains("\"label\":\"routine\"");
        assertThat(r.inputTokens()).isEqualTo(330);
        assertThat(r.schemaEnforced()).isTrue();
        AiTaskStub.Call call = AiTaskStub.calls().getLast();
        assertThat(call.path()).isEqualTo("/openai/chat/completions");
        assertThat(call.headers().get("authorization")).isEqualTo("Bearer " + AiTaskStub.KEY);
        assertThat(call.body().path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(call.body().path("messages").get(0).path("role").asString()).isEqualTo("system");
        // Not OpenRouter: no provider.max_price block, no X-Title.
        assertThat(call.body().has("provider")).isFalse();
        // A private endpoint (Ollama, vLLM) works without a key.
        new OpenAiCompatibleProvider(http, ai).complete(endpoint("openaiCompatible", "/openai", "", "", Map.of()), null,
                request("local/llama"));
        assertThat(AiTaskStub.calls().getLast().headers()).doesNotContainKey("authorization");
    }

    @Test
    void anthropicCachesTheSystemTextAndUsesOutputConfigFormat() {
        ModelProvider.ModelResponse r = new AnthropicProvider(http).complete(
                endpoint("anthropic", "/anthropic", "", "", Map.of()), AiTaskStub.KEY, request("claude-haiku-5-5"));
        assertThat(r.content()).contains("routine");
        assertThat(r.cachedInputTokens()).isEqualTo(250);
        AiTaskStub.Call call = AiTaskStub.calls().getLast();
        assertThat(call.path()).isEqualTo("/anthropic/v1/messages");
        assertThat(call.headers().get("x-api-key")).isEqualTo(AiTaskStub.KEY);
        assertThat(call.headers().get("anthropic-version")).isEqualTo("2026-10-21");
        assertThat(call.body().path("system").get(0).path("cache_control").path("type").asString()).isEqualTo("ephemeral");
        assertThat(call.body().path("output_config").path("format").path("type").asString()).isEqualTo("json_schema");
        // This API version refuses sampling settings: none is sent.
        assertThat(call.body().has("temperature")).isFalse();
    }

    @Test
    void bedrockSignsWithSigV4AndForcesTheAnswerTool() {
        String keys = "{\"accessKeyId\":\"AKIDTESTONLY\",\"secretAccessKey\":\"secret\",\"sessionToken\":\"tok\"}";
        ModelProvider.ModelResponse r = new BedrockProvider(http, clock).complete(
                endpoint("bedrock", "/bedrock", "eu-central-1", "", Map.of()), keys,
                request("anthropic.claude-haiku-5-5:0"));
        assertThat(json.parse(r.content()).path("label").asString()).isEqualTo("routine");
        AiTaskStub.Call call = AiTaskStub.calls().getLast();
        assertThat(call.path()).isEqualTo("/bedrock/model/anthropic.claude-haiku-5-5%3A0/converse");
        assertThat(call.headers().get("authorization")).startsWith("AWS4-HMAC-SHA256 Credential=AKIDTESTONLY/20261105/"
                + "eu-central-1/bedrock/aws4_request").contains("SignedHeaders=content-type;host;x-amz-date;x-amz-security-token");
        assertThat(call.headers().get("x-amz-security-token")).isEqualTo("tok");
        assertThat(call.body().path("toolConfig").path("toolChoice").path("tool").path("name").asString())
                .isEqualTo("answer");
        assertThat(call.body().path("system").get(0).path("text").asString()).contains("classify task");
        // A Bedrock API key goes as a bearer token instead.
        new BedrockProvider(http, clock).complete(endpoint("bedrock", "/bedrock", "eu-central-1", "", Map.of()),
                "bedrock-api-key", request("m"));
        assertThat(AiTaskStub.calls().getLast().headers().get("authorization")).isEqualTo("Bearer bedrock-api-key");
    }

    @Test
    void sigV4MatchesTheAwsTestVector() {
        String auth = SigV4.authorization(new SigV4.Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                null), "us-east-1", "service", "GET", "/", "", Map.of("host", "example.amazonaws.com", "x-amz-date",
                "20150830T123600Z"), new byte[0], Instant.parse("2015-08-30T12:36:00Z"));
        assertThat(auth).endsWith("Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31");
    }

    @Test
    void azureUsesTheDeploymentAndApiVersion() {
        new AzureOpenAiProvider(http).complete(endpoint("azureOpenai", "/azure", "", "", Map.of("gpt-test", "dep-gpt")),
                AiTaskStub.KEY, request("gpt-test"));
        AiTaskStub.Call call = AiTaskStub.calls().getLast();
        assertThat(call.path()).isEqualTo("/azure/openai/deployments/dep-gpt/chat/completions?api-version=2024-10-21");
        assertThat(call.headers().get("api-key")).isEqualTo(AiTaskStub.KEY);
        assertThat(call.body().path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
    }

    @Test
    void geminiApiAndVertexUseTheirAddressesAndJsonSchema() {
        ModelProvider.ModelResponse r = new VertexGeminiProvider(http).complete(
                endpoint("vertex", "/gemini", "", "", Map.of()), AiTaskStub.KEY, request("gemini-test"));
        assertThat(r.model()).isEqualTo("gemini-test-001");
        AiTaskStub.Call call = AiTaskStub.calls().getLast();
        assertThat(call.path()).isEqualTo("/gemini/v1beta/models/gemini-test:generateContent");
        assertThat(call.headers().get("x-goog-api-key")).isEqualTo(AiTaskStub.KEY);
        JsonNode generation = call.body().path("generationConfig");
        assertThat(generation.path("responseMimeType").asString()).isEqualTo("application/json");
        assertThat(generation.path("responseJsonSchema").path("required").get(0).asString()).isEqualTo("label");
        assertThat(call.body().path("systemInstruction").path("parts").get(0).path("text").asString()).contains("classify");
        // Vertex: project and location in the path, bearer token.
        new VertexGeminiProvider(http).complete(endpoint("vertex", "/gemini", "", "acme-ai", Map.of()), "ya29.token",
                request("gemini-test"));
        AiTaskStub.Call vertex = AiTaskStub.calls().getLast();
        assertThat(vertex.path()).isEqualTo(
                "/gemini/v1/projects/acme-ai/locations/us-central1/publishers/google/models/gemini-test:generateContent");
        assertThat(vertex.headers().get("authorization")).isEqualTo("Bearer ya29.token");
    }

    @Test
    void failuresAreClassifiedWithoutBodies() {
        ModelProvider.ModelRequest outage = new ModelProvider.ModelRequest("m", "classify",
                List.of(new ModelProvider.Turn("user", "<request_data>outage</request_data>")), null, null, 100, null,
                Duration.ofSeconds(5));
        assertThatThrownBy(() -> new AnthropicProvider(http).complete(endpoint("anthropic", "/anthropic", "", "",
                Map.of()), AiTaskStub.KEY, outage)).isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).kind()).isEqualTo(ProviderException.Kind.UNAVAILABLE))
                .hasMessageNotContaining("outage");
        assertThat(ProviderHttp.classify(401, null, "x").kind()).isEqualTo(ProviderException.Kind.CREDENTIALS_REJECTED);
        assertThat(ProviderHttp.classify(402, null, "x").kind()).isEqualTo(ProviderException.Kind.PAYMENT_REQUIRED);
        assertThat(ProviderHttp.classify(422, null, "x").kind()).isEqualTo(ProviderException.Kind.REJECTED);
    }
}
