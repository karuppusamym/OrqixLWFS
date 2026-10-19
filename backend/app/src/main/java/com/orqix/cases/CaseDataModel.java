package com.orqix.cases;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.orqix.compiler.FormValidator;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import tools.jackson.databind.JsonNode;

/** Closed, scalar case-data vocabulary; never evaluates author expressions or installs engine variables. */
@Component
public class CaseDataModel {
    public record Field(String key, String label, String type, Boolean required, Integer maxLength,
            BigDecimal minimum, BigDecimal maximum, List<String> options) {}
    private static final Set<String> TYPES=Set.of("text","number","integer","boolean","date","choice");
    private static final BigDecimal LIMIT=new BigDecimal("1000000000000000");
    private final Json json;
    private final FormValidator validator;
    public CaseDataModel(Json json,FormValidator validator) { this.json=json; this.validator=validator; }

    public List<Field> fields(List<Field> fields) {
        if(fields==null) return List.of();
        if(fields.size()>30) throw invalid("A case supports at most 30 data fields.");
        var keys=new HashSet<String>();
        for(var field:fields) {
            if(field==null || field.key()==null || !field.key().matches("[A-Za-z][A-Za-z0-9_]{0,63}") || !keys.add(field.key())
                    || field.label()==null || field.label().isBlank() || !field.label().equals(field.label().strip()) || field.label().length()>120
                    || !TYPES.contains(field.type()==null?"":field.type())) throw invalid("Case data fields need unique keys, labels and supported types.");
            boolean numeric=Set.of("number","integer").contains(field.type());
            if(field.maxLength()!=null && (!field.type().equals("text") || field.maxLength()<1 || field.maxLength()>4000))
                throw invalid("Text limits must be between 1 and 4000 characters.");
            if((field.minimum()!=null || field.maximum()!=null) && !numeric) throw invalid("Only numeric fields accept numeric bounds.");
            if(!fitsBrowserJson(field.minimum()) || !fitsBrowserJson(field.maximum())
                    || field.minimum()!=null && field.minimum().abs().compareTo(LIMIT)>0
                    || field.maximum()!=null && field.maximum().abs().compareTo(LIMIT)>0
                    || field.minimum()!=null && field.maximum()!=null && field.minimum().compareTo(field.maximum())>0)
                throw invalid("Numeric case-data bounds are invalid.");
            if(field.type().equals("choice")) {
                if(field.options()==null || field.options().isEmpty() || field.options().size()>30
                        || field.options().stream().anyMatch(value->value==null || value.isBlank() || value.length()>120 || !value.equals(value.strip()))
                        || new HashSet<>(field.options()).size()!=field.options().size()) throw invalid("Choice fields need 1 to 30 distinct values.");
            } else if(field.options()!=null && !field.options().isEmpty()) throw invalid("Only choice fields accept options.");
        }
        return fields.stream().map(field->new Field(field.key(),field.label(),field.type(),Boolean.TRUE.equals(field.required()),field.maxLength(),field.minimum(),field.maximum(),field.options()==null?null:List.copyOf(field.options()))).toList();
    }

    public List<Field> readFields(String source) {
        var result=new java.util.ArrayList<Field>();
        for(var node:json.parse(source)) result.add(json.convert(node,Field.class));
        return fields(result);
    }

    public JsonNode values(List<Field> fields,JsonNode proposed) {
        JsonNode values=proposed==null?json.object():proposed;
        if(!values.isObject() || json.write(values).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768)
            throw invalid("Case data must be an object of at most 32 KiB.");
        var schema=json.object().put("type","object").put("additionalProperties",false);
        var properties=schema.putObject("properties"); var required=schema.putArray("required");
        for(var field:fields) {
            var property=properties.putObject(field.key());
            property.put("type",switch(field.type()) {
                case "text","date","choice" -> "string";
                default -> field.type();
            });
            if(Boolean.TRUE.equals(field.required())) required.add(field.key());
            if(field.type().equals("text")) { property.put("maxLength",field.maxLength()==null?2000:field.maxLength()); property.put("minLength",Boolean.TRUE.equals(field.required())?1:0); }
            if(field.type().equals("date")) property.put("format","date");
            if(field.type().equals("choice")) { var options=property.putArray("enum");field.options().forEach(options::add); }
            if(Set.of("number","integer").contains(field.type())) {
                property.put("minimum",field.minimum()==null?LIMIT.negate():field.minimum());
                property.put("maximum",field.maximum()==null?LIMIT:field.maximum());
            }
        }
        for(var value:values) {
            if(value.isNumber() && !fitsBrowserJson(value.decimalValue()))
                throw invalid("Case data numbers must round-trip through browser JSON without losing precision.");
        }
        var errors=validator.validate(json.write(schema),json.write(values));
        if(!errors.isEmpty()) throw invalid("Case data does not match the pinned fields: "+errors.stream().limit(10).map(e->e.pointer()+" ("+e.code()+")").collect(java.util.stream.Collectors.joining(", ")));
        return values.deepCopy();
    }
    private static boolean fitsBrowserJson(BigDecimal value) {
        return value==null || Double.isFinite(value.doubleValue()) && value.compareTo(BigDecimal.valueOf(value.doubleValue()))==0;
    }
    private static ApiException invalid(String message) { return new ApiException(ProblemCode.VALIDATION_FAILED,message); }
}
