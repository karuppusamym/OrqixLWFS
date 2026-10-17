package com.orqix.migration.pega;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.orqix.compiler.CompileContext;
import com.orqix.compiler.Compilers;
import com.orqix.migration.camunda.Limits;
import com.orqix.migration.dmn.DmnAnalyzer;
import com.orqix.migration.dmn.DmnConverter;
import com.orqix.shared.json.Json;
import tools.jackson.databind.json.JsonMapper;

class PegaDecisionSnapshotTest {
    @Test void generatedSnapshotImportsCompilesAndPreservesFirstMatchAtBoundaries() throws Exception {
        var json = new Json(JsonMapper.builder().build());
        String xml = Files.readString(Path.of("../../examples/migration/pega-decision/generated/decision.dmn"));
        var analysis = DmnAnalyzer.analyze(xml, Limits.DEFAULT, json);
        var table = analysis.table("PurchaseRoute");
        assertThat(table).isNotNull();
        assertThat(table.inputKey()).isEqualTo("amount");
        var definition = DmnConverter.workflow(table, "pega-decision-demo", "Pega decision demo", json);
        assertThat(Compilers.workflowCompiler().validate(json.write(definition),
                new CompileContext(false, Map.of(), List.of(), Set.of())).issues()).isEmpty();
        var cases = Map.of("4999.99", "Standard", "5000", "Finance", "5000.01", "Finance",
                "9999.99", "Finance", "10000", "Director", "10000.01", "Director");
        for (var entry : cases.entrySet()) {
            var evaluated = Compilers.decisionEvaluator().evaluate(json.write(definition),
                    DmnConverter.tableKey(table.decisionId()), Map.of("/input/" + table.inputKey(), new BigDecimal(entry.getKey())));
            assertThat(evaluated.output()).as(entry.getKey()).containsEntry("result", entry.getValue());
        }
    }
}
