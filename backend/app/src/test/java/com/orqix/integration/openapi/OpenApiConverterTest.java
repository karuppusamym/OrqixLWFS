package com.orqix.integration.openapi;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pure conversion tests: source declarations stay reviewable data, never executable URLs or automatic bindings. */
class OpenApiConverterTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static ObjectNode object(String text) { return (ObjectNode)JSON.readTree(text); }
    private ObjectNode base() {
        return object("{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"Inventory API\",\"version\":\"1\"},\"paths\":{}}");
    }
    private ObjectNode operation(ObjectNode root,String path,String method,String key) {
        var paths=(ObjectNode)root.path("paths");var item=paths.has(path)?(ObjectNode)paths.get(path):paths.putObject(path);
        var op=item.putObject(method).put("operationId",key).put("summary","Operation "+key);
        op.set("responses",object("{\"200\":{\"description\":\"Success\",\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"required\":[\"status\"],\"properties\":{\"status\":{\"type\":\"string\"}}}}}}}"));return op;
    }
    private ObjectNode post(ObjectNode root) {
        var op=operation(root,"/items","post","createItem");
        op.set("requestBody",object("{\"required\":true,\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"required\":[\"title\",\"amount\"],\"properties\":{\"title\":{\"type\":\"string\"},\"amount\":{\"type\":\"integer\"}},\"additionalProperties\":false}}}}"));return op;
    }
    @Test void convertsStaticGetAndPostButDoesNotGuessConfirmedEffectOrCreateBinding() {
        var root=base();operation(root,"/health","get","getHealth");post(root);
        var analysis=OpenApiConverter.analyze(root);assertThat(analysis.report().summary().operations()).isEqualTo(2);assertThat(analysis.report().summary().blocked()).isZero();
        assertThat(analysis.templates()).containsKeys("getHealth","createItem");
        var get=analysis.templates().get("getHealth");var post=analysis.templates().get("createItem");
        assertThat(get.path("method").asString()).isEqualTo("GET");assertThat(post.path("requestSchema").path("required").toString()).contains("title","amount");
        assertThat(get.has("effectClass")).isFalse();assertThat(post.has("effectClass")).isFalse();
        var complete=OpenApiConverter.definition("inventory",1,"Inventory",null,Map.of("getHealth",OpenApiConverter.finish(get,"readOnly",null),"createItem",OpenApiConverter.finish(post,"nonIdempotent",null)));
        assertThat(complete.has("auth")).isFalse();assertThat(complete.has("baseUrl")).isFalse();assertThat(complete.has("binding")).isFalse();
        assertThat(complete.at("/operations/getHealth/testable").asBoolean()).isTrue();assertThat(complete.at("/operations/createItem/testable").asBoolean()).isFalse();
    }
    @Test void declaredIdempotencyIsASuggestionAndOnlyExplicitFinishEnablesIt() {
        var root=base();var op=post(root);op.set("parameters",object("{\"list\":[{\"name\":\"Idempotency-Key\",\"in\":\"header\",\"required\":true,\"schema\":{\"type\":\"string\"}}]}").path("list"));
        var analysis=OpenApiConverter.analyze(root);var report=analysis.report().operations().get(0);
        assertThat(report.suggestedEffectClass()).isEqualTo("idempotentWithKey");assertThat(report.idempotencyHeader()).isEqualTo("Idempotency-Key");
        var template=analysis.templates().get("createItem");assertThat(template).isNotNull();assertThat(template.has("effectClass")).isFalse();
        String beforeFinish=template.toString();
        var opWithKey=OpenApiConverter.finish(template,"idempotentWithKey","X-Request-Key");
        assertThat(opWithKey.path("effectClass").asString()).isEqualTo("idempotentWithKey");assertThat(opWithKey.path("idempotencyHeader").asString()).isEqualTo("X-Request-Key");assertThat(opWithKey.path("testable").asBoolean()).isFalse();
        assertThat(OpenApiConverter.finish(template,"nonIdempotent",null).has("idempotencyHeader")).isFalse();assertThat(template.path("idempotencyHeader").asString()).isEqualTo("Idempotency-Key");assertThat(template.toString()).isEqualTo(beforeFinish);
    }
    @Test void requiredQueryAndDynamicAddressesAreBlockedWithReasons() {
        var root=base();operation(root,"/items/{id}","get","getItem");var query=operation(root,"/search","get","searchItems");
        query.set("parameters",JSON.createArrayNode().add(object("{\"name\":\"query\",\"in\":\"query\",\"required\":true,\"schema\":{\"type\":\"string\"}}")));
        var analysis=OpenApiConverter.analyze(root);assertThat(analysis.report().summary().blocked()).isEqualTo(2);assertThat(analysis.templates()).isEmpty();
        assertThat(analysis.report().operations()).allSatisfy(op->{assertThat(op.disposition()).isEqualTo(ImportReport.BLOCKED);assertThat(op.blockers()).isNotEmpty();});
        assertThat(analysis.report().operations().stream().flatMap(op->op.blockers().stream()).toList().toString()).contains("variable","query");
    }
    @Test void optionalQueryAndUnsupportedArrayResultsAreReportedAsSimplifications() {
        var root=base();var op=operation(root,"/items","get","listItems");
        op.set("parameters",JSON.createArrayNode().add(object("{\"name\":\"cursor\",\"in\":\"query\",\"schema\":{\"type\":\"string\"}}")));
        ((ObjectNode)op.at("/responses/200/content/application~1json/schema/properties")).set("items",object("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"));
        var analysis=OpenApiConverter.analyze(root);var report=analysis.report().operations().get(0);
        assertThat(report.disposition()).isEqualTo(ImportReport.SIMPLIFIED);assertThat(report.notes().toString()).contains("cursor","items");
        assertThat(analysis.templates().get("listItems").at("/responseSchema/properties").has("items")).isFalse();
    }
    @Test void requiredArrayRequestAndFileResponseAreBlockedInsteadOfSilentlyDropped() {
        var root=base();var request=post(root);
        ((ObjectNode)request.at("/requestBody/content/application~1json/schema/properties")).set("title",object("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"));
        var download=operation(root,"/download","get","downloadFile");download.set("responses",object("{\"200\":{\"description\":\"File\",\"content\":{\"application/pdf\":{\"schema\":{\"type\":\"string\",\"format\":\"binary\"}}}}}"));
        var analysis=OpenApiConverter.analyze(root);assertThat(analysis.report().summary().blocked()).isEqualTo(2);assertThat(analysis.templates()).isEmpty();
        assertThat(analysis.report().operations()).allSatisfy(op->assertThat(op.blockers()).isNotEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"https://192.0.2.1/never-fetch.yaml#/schema","file:///never-read-local-secret.json#/schema","#/paths/forbidden"})
    void externalFileAndNonComponentReferencesAreReportedWithoutResolvingThem(String reference) {
        var root=base();var op=post(root);((ObjectNode)op.at("/requestBody/content/application~1json")).set("schema",JSON.createObjectNode().put("$ref",reference));
        var analysis=OpenApiConverter.analyze(root);assertThat(analysis.templates()).isEmpty();assertThat(analysis.report().operations().get(0).blockers()).isNotEmpty();
        assertThat(analysis.report().issues()).isNotEmpty();
    }
    @Test void internalComponentReferenceIsResolvedAndCyclesAreReported() {
        var root=base();var op=post(root);var original=op.at("/requestBody/content/application~1json/schema").deepCopy();
        root.putObject("components").putObject("schemas").set("Request",original);((ObjectNode)op.at("/requestBody/content/application~1json")).set("schema",object("{\"$ref\":\"#/components/schemas/Request\"}"));
        assertThat(OpenApiConverter.analyze(root).templates()).containsKey("createItem");
        ((ObjectNode)root.at("/components/schemas")).set("Request",object("{\"$ref\":\"#/components/schemas/Request\"}"));
        var cyclic=OpenApiConverter.analyze(root);assertThat(cyclic.templates()).isEmpty();assertThat(cyclic.report().operations().get(0).blockers().toString()).contains("itself");
    }
    @Test void serverProvenanceNeverCopiesCredentialsQueryOrFragmentsEvenForMalformedUrls() {
        var root=base();operation(root,"/health","get","health");root.set("servers",JSON.createArrayNode()
                .add(object("{\"url\":\"https://alice:super-secret@example.com:8443/api?token=hidden#private\"}"))
                .add(object("{\"url\":\"https://alice:super-secret@bad host/api?token=hidden#private\"}")));
        var report=OpenApiConverter.analyze(root).report();assertThat(report.servers().get(0).url()).isEqualTo("https://example.com:8443/api");
        assertThat(report.servers().get(1).url()).isEqualTo("Invalid server URL");
        assertThat(report.servers().toString()).doesNotContain("alice","super-secret","token","hidden","private");
    }
    @Test void bearerAuthenticationIsAdviceAndUnsupportedSecurityNeedsPlatformDecision() {
        var root=base();operation(root,"/health","get","health");root.set("components",object("{\"securitySchemes\":{\"token\":{\"type\":\"http\",\"scheme\":\"bearer\"}}}"));root.set("security",JSON.createArrayNode().add(object("{\"token\":[]}")));
        var bearer=OpenApiConverter.analyze(root).report().auth();assertThat(bearer.suggestedType()).isEqualTo("bearerToken");assertThat(bearer.status()).isEqualTo("suggested");
        ((ObjectNode)root.at("/components/securitySchemes/token")).put("scheme","basic");
        var unsupported=OpenApiConverter.analyze(root).report().auth();assertThat(unsupported.status()).isEqualTo("needsPlatformDecision");assertThat(unsupported.suggestedType()).isNull();
    }
    @Test void swaggerAndMissingVersionCannotBeMistakenForOpenApiThree() {
        assertThatThrownBy(()->OpenApiConverter.analyze(object("{\"swagger\":\"2.0\",\"paths\":{}}"))).isInstanceOf(SpecReader.SpecException.class).hasMessageContaining("OpenAPI 3");
        assertThatThrownBy(()->OpenApiConverter.analyze(object("{\"paths\":{}}"))).isInstanceOf(SpecReader.SpecException.class);
    }
}
