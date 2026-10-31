package com.orqix.runtime.evidencepack;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ReleaseDiffTest {

    private final Json json = new Json(JsonMapper.builder().build());

    private JsonNode definition(String nodes, String transitions, String decisions, String inputFields, String extra) {
        return json.parse("{\"nodes\":" + nodes + ",\"transitions\":" + transitions + ",\"decisions\":" + decisions
                + ",\"inputSchema\":{\"type\":\"object\",\"properties\":{" + inputFields + "}},"
                + "\"uiLayout\":{\"x\":1},\"metadata\":{\"note\":\"a\"}" + extra + "}");
    }

    @Test
    void summarizesStepsRoutesRulesFormsAndSettings() {
        JsonNode before = definition("""
                [{"id":"start","type":"start","label":"Request","config":{}},
                 {"id":"manager","type":"approval","label":"Manager approval","config":{"group":"managers"}},
                 {"id":"old","type":"notify","label":"Old notice","config":{}}]""",
                "[{\"id\":\"t1\",\"from\":\"start\",\"to\":\"manager\",\"on\":\"next\"}]",
                "[{\"key\":\"limits\",\"rows\":[{}]}]", "\"amount\":{\"type\":\"number\"}", ",\"execution\":{\"maxAttempts\":1}");
        JsonNode after = definition("""
                [{"id":"start","type":"start","label":"Request","config":{}},
                 {"id":"manager","type":"approval","label":"Line manager approval","config":{"group":"finance"}},
                 {"id":"finance","type":"approval","label":"Finance approval","config":{}}]""",
                "[{\"id\":\"t1\",\"from\":\"start\",\"to\":\"manager\",\"on\":\"next\"},{\"id\":\"t2\",\"from\":\"manager\",\"to\":\"finance\",\"on\":\"approve\"}]",
                "[{\"key\":\"limits\",\"rows\":[{},{}]}]", "\"amount\":{\"type\":\"number\"},\"costCenter\":{\"type\":\"string\"}",
                ",\"execution\":{\"maxAttempts\":3}");
        assertThat(ReleaseDiff.compare(before, after)).extracting(ReleaseDiff.Change::title).containsExactly(
                "Step changed: Line manager approval (renamed from \"Manager approval\"; settings changed: group)",
                "Step added: Finance approval (approval)",
                "Step removed: Old notice",
                "Route added: Line manager approval -> Finance approval (approve)",
                "Rule table changed: limits (1 -> 2 rows)",
                "Request form changed (fields +costCenter)",
                "Workflow setting changed: execution");
    }

    @Test
    void layoutAndNotesAreNotChanges() {
        JsonNode before = definition("[]", "[]", "[]", "", "");
        JsonNode after = json.parse(before.toString().replace("\"x\":1", "\"x\":2").replace("\"a\"", "\"b\""));
        assertThat(ReleaseDiff.compare(before, after)).isEmpty();
    }
}
