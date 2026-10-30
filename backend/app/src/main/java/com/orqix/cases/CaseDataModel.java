package com.orqix.cases;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.orqix.compiler.FormPattern;
import com.orqix.compiler.FormValidator;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import tools.jackson.databind.JsonNode;

/**
 * Closed, scalar case-data vocabulary; never evaluates author expressions or installs engine variables.
 * <p>
 * Since S125 a case data field carries the same checks as a single-value request question (docs/build/S125): help text,
 * shortest length, an answer pattern with its message, date bounds, a money or long-text presentation. All of them are
 * optional, so every earlier definition keeps reading and validating as before. Keys keep the older pattern
 * {@code [A-Za-z][A-Za-z0-9_]{0,63}}; a key outside the request-question pattern {@code [a-z][A-Za-z0-9]{0,63}} is reported
 * as a warning ({@link #warnings}), never as an error.
 */
@Component
public class CaseDataModel {
    public record Field(String key, String label, String type, Boolean required, Integer maxLength,
            BigDecimal minimum, BigDecimal maximum, List<String> options,
            @JsonInclude(JsonInclude.Include.NON_NULL) String description,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer minLength,
            @JsonInclude(JsonInclude.Include.NON_NULL) String pattern,
            @JsonInclude(JsonInclude.Include.NON_NULL) String patternMessage,
            @JsonInclude(JsonInclude.Include.NON_NULL) String formatMinimum,
            @JsonInclude(JsonInclude.Include.NON_NULL) String formatMaximum,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal multipleOf,
            @JsonInclude(JsonInclude.Include.NON_NULL) String widget) {
        /** The field as it was before S125: no help text, pattern, date bounds or presentation. */
        public Field(String key, String label, String type, Boolean required, Integer maxLength,
                BigDecimal minimum, BigDecimal maximum, List<String> options) {
            this(key, label, type, required, maxLength, minimum, maximum, options, null, null, null, null, null, null, null, null);
        }
    }

    /** A problem that does not stop the definition from being created. */
    public record Warning(String code, String field, String message) {}

    public static final String LEGACY_KEY = "CASE_DATA_KEY_LEGACY";
    /** Accepted for every case data field key (unchanged since the first case slice). */
    static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    /** The request-question key pattern (workflow-v0 {@code fieldKey}); new keys follow it. */
    static final Pattern QUESTION_KEY = Pattern.compile("[a-z][A-Za-z0-9]{0,63}");
    private static final Set<String> TYPES=Set.of("text","number","integer","boolean","date","choice");
    private static final BigDecimal LIMIT=new BigDecimal("1000000000000000");
    private static final Pattern DATE=Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private final Json json;
    private final FormValidator validator;
    public CaseDataModel(Json json,FormValidator validator) { this.json=json; this.validator=validator; }

    public List<Field> fields(List<Field> fields) {
        if(fields==null) return List.of();
        if(fields.size()>30) throw invalid("A case supports at most 30 data fields.");
        var keys=new HashSet<String>();
        for(var field:fields) {
            if(field==null || field.key()==null || !KEY.matcher(field.key()).matches() || !keys.add(field.key())
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
            checkRequestFieldChecks(field);
        }
        return fields.stream().map(field->new Field(field.key(),field.label(),field.type(),Boolean.TRUE.equals(field.required()),field.maxLength(),field.minimum(),field.maximum(),
                field.options()==null?null:List.copyOf(field.options()),blankToNull(field.description()),field.minLength(),field.pattern(),
                field.pattern()==null?null:blankToNull(field.patternMessage()),field.formatMinimum(),field.formatMaximum(),
                field.multipleOf()==null?null:field.multipleOf().compareTo(BigDecimal.ONE)==0?BigDecimal.ONE:new BigDecimal("0.01"),field.widget())).toList();
    }

    /** The checks a case field shares with a single-value request question (S125); each is optional. */
    private static void checkRequestFieldChecks(Field field) {
        String type=field.type(), key=field.key();
        if(field.description()!=null && field.description().length()>500) throw invalid("Help text of "+key+" must be at most 500 characters.");
        if(field.minLength()!=null) {
            int longest=field.maxLength()==null?2000:field.maxLength();
            if(!type.equals("text") || field.minLength()<0 || field.minLength()>longest)
                throw invalid("The shortest length of "+key+" applies to text fields only and must be between 0 and the longest length.");
        }
        if(field.pattern()!=null) {
            if(!type.equals("text")) throw invalid("Only text fields accept an answer pattern.");
            String problem=field.pattern().length()<2 || field.pattern().length()>FormPattern.MAX_PATTERN_LENGTH
                    ?"use 2 to "+FormPattern.MAX_PATTERN_LENGTH+" characters":FormPattern.problem(field.pattern());
            if(problem!=null) throw invalid("The answer pattern of "+key+" is not supported: "+problem);
        }
        if(field.patternMessage()!=null && !field.patternMessage().isBlank()) {
            if(field.pattern()==null) throw invalid("A pattern message needs an answer pattern.");
            if(field.patternMessage().strip().length()>200) throw invalid("The pattern message of "+key+" must be at most 200 characters.");
        }
        if(field.formatMinimum()!=null || field.formatMaximum()!=null) {
            if(!type.equals("date")) throw invalid("Only date fields accept an earliest or latest date.");
            LocalDate earliest=date(field.formatMinimum()), latest=date(field.formatMaximum());
            if(field.formatMinimum()!=null && earliest==null || field.formatMaximum()!=null && latest==null)
                throw invalid("Date bounds of "+key+" must be calendar dates written YYYY-MM-DD.");
            if(earliest!=null && latest!=null && earliest.isAfter(latest)) throw invalid("The earliest date of "+key+" is after its latest date.");
        }
        if(field.multipleOf()!=null && (!Set.of("number","integer").contains(type)
                || field.multipleOf().compareTo(new BigDecimal("0.01"))!=0 && field.multipleOf().compareTo(BigDecimal.ONE)!=0))
            throw invalid("Only numeric fields accept a step, and it must be 0.01 or 1.");
        if(field.widget()!=null && !("textarea".equals(field.widget()) && type.equals("text") || "money".equals(field.widget()) && type.equals("number")))
            throw invalid("Long text applies to text fields and money to number fields.");
    }

    /** Keys that do not follow the request-question pattern: they keep working and are reported so authors can rename them. */
    public static List<Warning> warnings(List<Field> fields) {
        if(fields==null) return List.of();
        var warnings=new ArrayList<Warning>();
        for(var field:fields) if(field!=null && field.key()!=null && !QUESTION_KEY.matcher(field.key()).matches())
            warnings.add(new Warning(LEGACY_KEY,field.key(),"The key "+field.key()+" uses the older case data key pattern. It keeps working, but new keys start with a lowercase letter and use letters and digits only, like request questions"
                    +(suggestKey(field.key())==null?".":" (for example "+suggestKey(field.key())+").")));
        return List.copyOf(warnings);
    }

    /** customerName for Customer_Name; null when no key in the new pattern can be derived. */
    static String suggestKey(String key) {
        var out=new StringBuilder();
        boolean upper=false;
        for(char c:key.toCharArray()) {
            if(c=='_') { upper=out.length()>0; continue; }
            if(out.length()==0) { if(Character.isLetter(c)) out.append(Character.toLowerCase(c)); continue; }
            out.append(upper?Character.toUpperCase(c):c); upper=false;
        }
        String suggestion=out.length()>64?out.substring(0,64):out.toString();
        return QUESTION_KEY.matcher(suggestion).matches()?suggestion:null;
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
            if(field.type().equals("text")) {
                property.put("maxLength",field.maxLength()==null?2000:field.maxLength());
                property.put("minLength",Math.max(field.minLength()==null?0:field.minLength(),Boolean.TRUE.equals(field.required())?1:0));
                if(field.pattern()!=null) {
                    property.put("pattern",field.pattern());
                    if(field.patternMessage()!=null) property.put("patternMessage",field.patternMessage());
                }
            }
            if(field.type().equals("date")) {
                property.put("format","date");
                if(field.formatMinimum()!=null) property.put("formatMinimum",field.formatMinimum());
                if(field.formatMaximum()!=null) property.put("formatMaximum",field.formatMaximum());
            }
            if(field.type().equals("choice")) { var options=property.putArray("enum");field.options().forEach(options::add); }
            if(Set.of("number","integer").contains(field.type())) {
                property.put("minimum",field.minimum()==null?LIMIT.negate():field.minimum());
                property.put("maximum",field.maximum()==null?LIMIT:field.maximum());
                if(field.multipleOf()!=null) property.put("multipleOf",field.multipleOf());
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
    private static LocalDate date(String text) {
        if(text==null || !DATE.matcher(text).matches()) return null;
        try { return LocalDate.parse(text); } catch(DateTimeParseException ex) { return null; }
    }
    private static String blankToNull(String text) { return text==null || text.isBlank()?null:text.strip(); }
    private static boolean fitsBrowserJson(BigDecimal value) {
        return value==null || Double.isFinite(value.doubleValue()) && value.compareTo(BigDecimal.valueOf(value.doubleValue()))==0;
    }
    private static ApiException invalid(String message) { return new ApiException(ProblemCode.VALIDATION_FAILED, message); }
}
