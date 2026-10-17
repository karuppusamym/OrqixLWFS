package com.orqix.compiler;

import java.math.BigDecimal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.MissingNode;

/** Resolves typed request fields and bounded collection aggregates identically in simulation and live execution. */
public final class InputValues {
    private static final JsonNodeFactory JSON=JsonNodeFactory.instance;
    private InputValues() {}
    public static JsonNode resolve(JsonNode input,String reference) {
        if (reference==null || !reference.startsWith("/input/")) return MissingNode.getInstance();
        String[] parts=reference.split("/",-1);
        if (parts.length<3 || !key(parts[2])) return MissingNode.getInstance();
        JsonNode value=input.path(parts[2]);
        if (parts.length==3) return value;
        boolean count=parts.length==4 && "count".equals(parts[3]);
        boolean sum=parts.length==5 && "sum".equals(parts[3]) && key(parts[4]);
        if (!count && !sum) return MissingNode.getInstance();
        if (value.isMissingNode()) return JSON.numberNode(BigDecimal.ZERO);
        if (!value.isArray()) throw new IllegalArgumentException("Collection aggregates require a list.");
        if (count) return JSON.numberNode(value.size());
        BigDecimal total=BigDecimal.ZERO;
        for (JsonNode row:value) {
            if (!row.isObject()) throw new IllegalArgumentException("Column totals require object rows.");
            JsonNode cell=row.path(parts[4]);
            if (cell.isMissingNode()) continue;
            if (!cell.isNumber()) throw new IllegalArgumentException("Column totals require numeric values.");
            total=total.add(cell.decimalValue());
        }
        return JSON.numberNode(total);
    }
    private static boolean key(String value) { return value.matches("^[a-z][A-Za-z0-9]{0,63}$"); }
}
