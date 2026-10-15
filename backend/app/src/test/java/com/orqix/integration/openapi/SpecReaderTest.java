package com.orqix.integration.openapi;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpecReaderTest {
    @Test void readsJsonAndYamlAsPlainTreesWithExplicitOrDetectedFormat() {
        var json=SpecReader.read("{\"openapi\":\"3.1.0\",\"paths\":{}}","api.json",null);
        var yaml=SpecReader.read("openapi: 3.1.0\npaths: {}\n","API.YML",null);
        assertThat(json.format()).isEqualTo("json");assertThat(yaml.format()).isEqualTo("yaml");assertThat(yaml.document()).isEqualTo(json.document());
        assertThat(SpecReader.read("{\"openapi\":\"3.1.0\"}",null,null).format()).isEqualTo("json");
        assertThat(SpecReader.read("openapi: 3.1.0",null,null).format()).isEqualTo("yaml");
        assertThat(SpecReader.read("openapi: 3.1.0","wrong.json","yaml").document().path("openapi").asString()).isEqualTo("3.1.0");
    }
    @ParameterizedTest @ValueSource(strings={"{\"paths\":{},\"paths\":{}}", "paths: {}\npaths: {}\n"})
    void duplicateKeysCannotChangeTheImportedDocumentByParserOrder(String source) {
        assertThatThrownBy(()->SpecReader.read(source,null,null)).isInstanceOf(SpecReader.SpecException.class);
    }
    @ParameterizedTest @ValueSource(strings={
            "base: &base {type: object}\ncopy: *base\n",
            "base: &base {type: object}\n",
            "copy: *undefined\n",
            "value: !!str secret\n",
            "value: !custom secret\n",
            "value: !!java/object:java.lang.ProcessBuilder {}\n",
            "value: {<<: {a: 1}}\n",
            "openapi: 3.0.0\n---\nopenapi: 3.1.0\n"})
    void yamlIndirectionsTagsMergesAndMultipleDocumentsAreRejectedBeforeConversion(String source) {
        assertThatThrownBy(()->SpecReader.read(source,"api.yaml",null)).isInstanceOf(SpecReader.SpecException.class);
    }
    @ParameterizedTest @ValueSource(strings={"[]","null","42","\"text\"","{} {}"})
    void onlyOneTopLevelObjectIsAccepted(String source) {
        assertThatThrownBy(()->SpecReader.read(source,"api.json",null)).isInstanceOf(SpecReader.SpecException.class);
    }
    @Test void byteLimitMeasuresUtf8InsteadOfJavaCharacters() {
        String source="{\"description\":\""+"é".repeat(SpecReader.MAX_BYTES/2)+"\"}";
        assertThat(source.length()).isLessThan(SpecReader.MAX_BYTES);
        assertThatThrownBy(()->SpecReader.read(source,"api.json",null)).isInstanceOfSatisfying(SpecReader.SpecException.class,e->assertThat(e.tooLarge()).isTrue());
    }
    @Test void deeplyNestedJsonAndYamlCannotExhaustTheConverterStack() {
        String json="{\"value\":"+"[".repeat(SpecReader.MAX_DEPTH+1)+"0"+"]".repeat(SpecReader.MAX_DEPTH+1)+"}";
        String yaml="value: "+"[".repeat(SpecReader.MAX_DEPTH+1)+"0"+"]".repeat(SpecReader.MAX_DEPTH+1);
        assertThatThrownBy(()->SpecReader.read(json,"api.json",null)).isInstanceOf(SpecReader.SpecException.class);
        assertThatThrownBy(()->SpecReader.read(yaml,"api.yaml",null)).isInstanceOf(SpecReader.SpecException.class);
    }
    @Test void nodeBudgetStopsLargeTreesEvenBelowTheByteLimit() {
        String source="{\"values\":["+"0,".repeat(SpecReader.MAX_NODES)+"0]}";
        assertThat(source.length()).isLessThan(SpecReader.MAX_BYTES);
        assertThatThrownBy(()->SpecReader.read(source,"api.json",null)).isInstanceOfSatisfying(SpecReader.SpecException.class,e->assertThat(e.tooLarge()).isTrue());
    }
    @Test void emptyUnknownFormatAndBomInputAreHandledExplicitly() {
        assertThatThrownBy(()->SpecReader.read(" ",null,null)).isInstanceOf(SpecReader.SpecException.class);
        assertThatThrownBy(()->SpecReader.read("{}",null,"xml")).isInstanceOf(SpecReader.SpecException.class);
        assertThat(SpecReader.read("\uFEFF{\"openapi\":\"3.1.0\"}","api.json",null).document().path("openapi").asString()).isEqualTo("3.1.0");
    }
}
