package com.orqix.compiler;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class InputValuesTest {
    private final JsonMapper json=JsonMapper.builder().build();
    @Test void exactColumnTotalsPreserveDecimalPrecisionAndOptionalAbsentCellsContributeZero() {
        var input=json.readTree("{\"items\":[{\"amount\":0.1},{\"amount\":0.2},{}, {\"amount\":-0.05}]}");
        assertThat(InputValues.resolve(input,"/input/items/sum/amount").decimalValue()).isEqualByComparingTo(new BigDecimal("0.25"));
        assertThat(InputValues.resolve(input,"/input/items/count").asInt()).isEqualTo(4);
    }
    @Test void absentOrEmptyListsHaveZeroAggregatesAndScalarListsHaveCounts() {
        var input=json.readTree("{\"items\":[],\"names\":[\"a\",\"b\"]}");
        for(String reference:new String[]{"/input/items/count","/input/items/sum/amount","/input/absent/count","/input/absent/sum/amount"})
            assertThat(InputValues.resolve(input,reference).decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(InputValues.resolve(input,"/input/names/count").asInt()).isEqualTo(2);
    }
    @Test void forgedWrongTypesNeverProduceInventedTotalsAndUnsupportedReferencesStayMissing() {
        assertThatThrownBy(()->InputValues.resolve(json.readTree("{\"items\":{}}"),"/input/items/count")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->InputValues.resolve(json.readTree("{\"items\":[1]}"),"/input/items/sum/amount")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->InputValues.resolve(json.readTree("{\"items\":[{\"amount\":\"1\"}]}"),"/input/items/sum/amount")).isInstanceOf(IllegalArgumentException.class);
        assertThat(InputValues.resolve(json.readTree("{}"),"/input/items/nope").isMissingNode()).isTrue();
        assertThat(InputValues.resolve(json.readTree("{}"),"/steps/call/value").isMissingNode()).isTrue();
    }
}
