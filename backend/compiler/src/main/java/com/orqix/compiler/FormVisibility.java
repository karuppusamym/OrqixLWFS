package com.orqix.compiler;

import tools.jackson.databind.JsonNode;

/** Typed conditional form policy shared by request validation and simulations. Hidden submitted values are invalid. */
public final class FormVisibility {
    private FormVisibility() {}
    public static boolean visible(JsonNode ui,JsonNode input,String key) {
        var own=ui.path("visibleWhen").path(key);
        if (!own.isMissingNode() && !matches(own,input)) return false;
        for (JsonNode section:ui.path("layout").path("sections")) {
            boolean included=false;for(JsonNode field:section.path("fields"))included|=key.equals(field.asString());
            if (included && section.has("visibleWhen") && !matches(section.path("visibleWhen"),input)) return false;
        }
        return true;
    }
    public static boolean required(JsonNode schema,JsonNode ui,JsonNode input,String key) {
        if (!visible(ui,input,key)) return false;
        for(JsonNode name:schema.path("required"))if(key.equals(name.asString()))return true;
        return ui.path("requiredWhen").has(key) && matches(ui.path("requiredWhen").path(key),input);
    }
    public static boolean matches(JsonNode condition,JsonNode input) {
        if (condition.has("all")) {
            if (!condition.path("all").isArray() || condition.path("all").isEmpty())return false;
            for(JsonNode term:condition.path("all"))if(!matches(term,input))return false;
            return true;
        }
        if (condition.has("any")) {
            for(JsonNode term:condition.path("any"))if(matches(term,input))return true;
            return false;
        }
        String ref=condition.path("fieldRef").asString("");
        if (!ref.matches("^/input/[a-z][A-Za-z0-9]{0,63}$"))return false;
        Object actual=scalar(input.path(ref.substring(7)));Object expected=scalar(condition.path("value"));
        if(actual==null || expected==null)return false;
        try{return ConditionMatcher.matches(condition.path("operator").asString(),actual,expected);}
        catch(IllegalArgumentException e){return false;}
    }
    private static Object scalar(JsonNode value) {
        if(value.isString())return value.asString();if(value.isNumber())return value.decimalValue();
        if(value.isBoolean())return value.asBoolean();return null;
    }
}
