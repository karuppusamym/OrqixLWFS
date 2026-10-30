package com.orqix.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.DecisionTables;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The strict DMN reader (docs/build/S121): Orqix's export reads back unchanged; what cannot be held is named. */
class DecisionDmnTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String NS = "https://www.omg.org/spec/DMN/20191111/MODEL/";

    private static JsonNode roundTrip(String tableJson) {
        String xml = DecisionTables.exportDmn(tableJson, "fees", "Fees");
        DecisionDmn.Result read = DecisionDmn.read(xml, null);
        assertThat(read.decisionId()).isEqualTo("fees");
        assertThat(read.name()).isEqualTo("Fees");
        // Compare as JSON text: the reader makes decimal nodes, the expectation is parsed text.
        return MAPPER.readTree(read.table().toString());
    }

    private static ObjectNode content(String json) {
        return (ObjectNode) MAPPER.readTree(json);
    }

    @Test
    void exportedTablesReadBackToTheSameContent() {
        String[] tables = {
            // UNIQUE with a list, a range, a dated row and a not-equal.
            "{\"hitPolicy\":\"UNIQUE\",\"rows\":["
                + "{\"when\":{\"all\":[{\"fieldRef\":\"/input/amount\",\"operator\":\"between\",\"value\":{\"from\":1000,\"to\":5000,\"includeTo\":false}},"
                + "{\"fieldRef\":\"/input/region\",\"operator\":\"in\",\"value\":[\"EU\",\"UK\"]}]},"
                + "\"then\":{\"route\":\"manager\",\"limit\":5000},\"effective\":{\"from\":\"2026-01-01\",\"to\":\"2026-12-31\"}},"
                + "{\"when\":{\"fieldRef\":\"/input/region\",\"operator\":\"ne\",\"value\":\"EU\"},\"then\":{\"route\":\"finance\",\"limit\":0}}],"
                + "\"default\":{\"route\":\"standard\",\"limit\":0}}",
            // PRIORITY with a step result and booleans.
            "{\"hitPolicy\":\"PRIORITY\",\"priority\":{\"output\":\"route\",\"order\":[\"finance\",\"standard\"]},\"rows\":["
                + "{\"when\":{\"fieldRef\":\"/steps/check/score\",\"operator\":\"gte\",\"value\":700},\"then\":{\"route\":\"standard\"}},"
                + "{\"when\":{\"fieldRef\":\"/input/urgent\",\"operator\":\"eq\",\"value\":true},\"then\":{\"route\":\"finance\"}}],"
                + "\"default\":{\"route\":\"standard\"}}",
            // COLLECT with an OR row on one field (one list of tests) and FIRST with an OR row on one field.
            "{\"hitPolicy\":\"COLLECT\",\"aggregation\":\"SUM\",\"rows\":["
                + "{\"when\":{\"any\":[{\"fieldRef\":\"/input/amount\",\"operator\":\"lt\",\"value\":10},"
                + "{\"fieldRef\":\"/input/amount\",\"operator\":\"gt\",\"value\":900000}]},\"then\":{\"fee\":1.5}},"
                + "{\"when\":{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":0},\"then\":{\"fee\":2}}],"
                + "\"default\":{\"fee\":0}}",
        };
        for (String table : tables) {
            assertThat(roundTrip(table)).as(table).isEqualTo(content(table));
        }
    }

    @Test
    void anOrRowOverSeveralFieldsReadsBackWithTheSameMeaning() {
        // UNIQUE becomes ANY (one rule per field, same results); the rules read back as one row each.
        JsonNode table = roundTrip("{\"hitPolicy\":\"UNIQUE\",\"rows\":[{\"when\":{\"any\":["
                + "{\"fieldRef\":\"/input/amount\",\"operator\":\"lt\",\"value\":10},"
                + "{\"fieldRef\":\"/input/region\",\"operator\":\"eq\",\"value\":\"EU\"}]},\"then\":{\"r\":\"a\"}}],\"default\":{\"r\":\"b\"}}");
        assertThat(table.path("hitPolicy").asString()).isEqualTo("ANY");
        assertThat(table.path("rows")).hasSize(2);
        // COLLECT makes the rules exclusive with not(...), which reads back as the inverted comparison.
        JsonNode collect = roundTrip("{\"hitPolicy\":\"COLLECT\",\"aggregation\":\"COUNT\",\"rows\":[{\"when\":{\"any\":["
                + "{\"fieldRef\":\"/input/amount\",\"operator\":\"lt\",\"value\":10},"
                + "{\"fieldRef\":\"/input/region\",\"operator\":\"eq\",\"value\":\"EU\"}]},\"then\":{\"n\":1}}],\"default\":{\"n\":0}}");
        assertThat(collect.path("rows").get(1).path("when").toString())
                .contains("\"operator\":\"gte\",\"value\":10", "\"operator\":\"eq\",\"value\":\"EU\"");
    }

    private static String file(String table) {
        return "<definitions xmlns=\"" + NS + "\" id=\"d\" namespace=\"x\"><decision id=\"a\">" + table + "</decision></definitions>";
    }

    private static String rule(String in, String out) {
        return "<rule><inputEntry><text>" + in + "</text></inputEntry><outputEntry><text>" + out + "</text></outputEntry></rule>";
    }

    private static final String COLUMNS = "<input><inputExpression typeRef=\"number\"><text>amount</text></inputExpression></input>"
            + "<output name=\"r\" typeRef=\"string\"><defaultOutputEntry><text>\"x\"</text></defaultOutputEntry></output>";

    @Test
    void aTrailingCatchAllIsTheDefaultOfFirstTablesOnly() {
        String first = file("<decisionTable hitPolicy=\"FIRST\">" + COLUMNS.replace("<defaultOutputEntry><text>\"x\"</text></defaultOutputEntry>", "")
                + rule("&lt; 5", "\"low\"") + rule("-", "\"other\"") + "</decisionTable>");
        JsonNode table = DecisionDmn.read(first, null).table();
        assertThat(table.path("rows")).hasSize(1);
        assertThat(table.path("default").path("r").asString()).isEqualTo("other");
        String unique = file("<decisionTable>" + COLUMNS + rule("&lt; 5", "\"low\"") + rule("-", "\"other\"") + "</decisionTable>");
        assertThatThrownBy(() -> DecisionDmn.read(unique, null)).hasMessageContaining("tests nothing");
    }

    @Test
    void whatCannotBeHeldIsRefusedWithItsPlace() {
        String[][] cases = {
            {"<decisionTable hitPolicy=\"RULE ORDER\">" + COLUMNS + rule("1", "\"a\"") + "</decisionTable>", "RULE ORDER"},
            {"<decisionTable hitPolicy=\"COLLECT\">" + COLUMNS + rule("1", "\"a\"") + "</decisionTable>", "without an aggregation"},
            {"<decisionTable>" + COLUMNS + rule("abs(amount) &gt; 3", "\"a\"") + "</decisionTable>", "rule 1, input 1"},
            {"<decisionTable>" + COLUMNS + rule("not([1..5])", "\"a\"") + "</decisionTable>", "not(...) of a range"},
            {"<decisionTable>" + COLUMNS + rule("1", "\"a\\\"b\"") + "</decisionTable>", "rule 1, output 1"},
            {"<decisionTable>" + COLUMNS.replace("<defaultOutputEntry><text>\"x\"</text></defaultOutputEntry>", "")
                + rule("1", "\"a\"") + "</decisionTable>", "defaultOutputEntry"},
            {"<decisionTable>" + COLUMNS.replace("<text>amount</text>", "<text>amount + 1</text>") + rule("1", "\"a\"")
                + "</decisionTable>", "not a field name"},
        };
        for (String[] c : cases) {
            assertThatThrownBy(() -> DecisionDmn.read(file(c[0]), null)).as(c[1]).isInstanceOf(DecisionDmn.Invalid.class)
                    .satisfies(e -> assertThat(((DecisionDmn.Invalid) e).problems().toString()).contains(c[1]));
        }
        String nested = "<decisionTable><input><inputExpression typeRef=\"number\"><text>amount</text></inputExpression></input>"
                + "<input><inputExpression typeRef=\"string\"><text>region</text></inputExpression></input>"
                + "<output name=\"r\" typeRef=\"string\"><defaultOutputEntry><text>\"x\"</text></defaultOutputEntry></output>"
                + "<rule><inputEntry><text>&lt; 5, &gt; 9</text></inputEntry><inputEntry><text>\"EU\"</text></inputEntry>"
                + "<outputEntry><text>\"a\"</text></outputEntry></rule></decisionTable>";
        assertThatThrownBy(() -> DecisionDmn.read(file(nested), null)).hasMessageContaining("cannot nest an OR inside an AND");
        assertThatThrownBy(() -> DecisionDmn.read("<definitions xmlns=\"urn:other\"/>", null)).hasMessageContaining("not a DMN file");
        assertThatThrownBy(() -> DecisionDmn.read("<!DOCTYPE d [<!ENTITY e \"x\">]><definitions xmlns=\"" + NS + "\"/>", null))
                .isInstanceOf(DecisionDmn.Invalid.class);
    }

    @Test
    void severalDecisionsWaitForAChoice() {
        String two = "<definitions xmlns=\"" + NS + "\" id=\"d\" namespace=\"x\">"
                + "<decision id=\"a\" name=\"A\"><decisionTable>" + COLUMNS + rule("1", "\"a\"") + "</decisionTable></decision>"
                + "<decision id=\"b\"><decisionTable>" + COLUMNS + rule("2", "\"b\"") + "</decisionTable></decision></definitions>";
        DecisionDmn.Result pending = DecisionDmn.read(two, null);
        assertThat(pending.table()).isNull();
        assertThat(pending.decisions()).extracting(DecisionDmn.Choice::id).containsExactly("a", "b");
        assertThat(DecisionDmn.read(two, "b").table().path("rows").get(0).path("when").path("value").asInt()).isEqualTo(2);
        assertThatThrownBy(() -> DecisionDmn.read(two, "c")).hasMessageContaining("no decision with the id");
    }
}
