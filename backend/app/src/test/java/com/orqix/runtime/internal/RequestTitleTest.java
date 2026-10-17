package com.orqix.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.orqix.catalog.ReleaseQueries.ReleaseView;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The request title of run lists: first text field in form order, trimmed, at most 120 characters. */
class RequestTitleTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ReleaseView release(String inputSchema, String inputUi) throws Exception {
        JsonNode definition = JSON.readTree("{\"inputSchema\":" + inputSchema + ",\"inputUi\":" + inputUi + "}");
        return new ReleaseView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "k", "Workflow", 1, definition,
                "p", "d", null, null, null);
    }

    private static final String SCHEMA = """
            {"type":"object","properties":{"amount":{"type":"number"},"title":{"type":"string"},"purpose":{"type":"string"}}}""";

    @Test
    void firstTextFieldInFormOrderIsTheTitle() throws Exception {
        ReleaseView release = release(SCHEMA, "{\"order\":[\"amount\",\"purpose\",\"title\"]}");
        JsonNode input = JSON.readTree("{\"amount\":5,\"title\":\"Laptop\",\"purpose\":\"Broken device\"}");
        assertThat(RuntimeViews.requestTitle(input, release)).isEqualTo("Broken device");
    }

    @Test
    void titleIsTrimmedAndBlankFieldsAreSkipped() throws Exception {
        ReleaseView release = release(SCHEMA, "{\"order\":[\"title\",\"purpose\"]}");
        assertThat(RuntimeViews.requestTitle(JSON.readTree("{\"title\":\"  Laptop \",\"purpose\":\"x\"}"), release))
                .isEqualTo("Laptop");
        assertThat(RuntimeViews.requestTitle(JSON.readTree("{\"title\":\"   \",\"purpose\":\"Fallback\"}"), release))
                .isEqualTo("Fallback");
    }

    @Test
    void titleIsCutToOneHundredAndTwentyCharacters() throws Exception {
        ReleaseView release = release(SCHEMA, "{\"order\":[\"title\"]}");
        String title = RuntimeViews.requestTitle(JSON.readTree("{\"title\":\"" + "a".repeat(300) + "\"}"), release);
        assertThat(title).hasSize(120);
    }

    @Test
    void noTextFieldMeansNoTitle() throws Exception {
        ReleaseView release = release("{\"type\":\"object\",\"properties\":{\"amount\":{\"type\":\"number\"}}}", "{}");
        assertThat(RuntimeViews.requestTitle(JSON.readTree("{\"amount\":5}"), release)).isNull();
    }
}
