package com.orqix.compiler;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Text-only mail templates. No request data, expressions or arbitrary URLs are interpolated. */
public final class MailTemplates {
    private static final Set<String> FIELDS=Set.of("reference","workflow","stage","outcome","dueAt","link");
    private static final Pattern TOKEN=Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9]*)}}");
    private MailTemplates() {}
    public static void validate(JsonNode template) {
        for (String key: new String[]{"subject","body"}) {
            String text=template.path(key).asString("");
            if (text.isBlank() || text.contains("${") || "subject".equals(key) && (text.contains("\r") || text.contains("\n")))
                throw new IllegalArgumentException("Use nonempty plain-text templates without expressions or mail-header newlines.");
            var matcher=TOKEN.matcher(text);
            while(matcher.find()) if (!FIELDS.contains(matcher.group(1)))
                throw new IllegalArgumentException("Unknown template field: " + matcher.group(1));
            if (TOKEN.matcher(text).replaceAll("").contains("{{") || TOKEN.matcher(text).replaceAll("").contains("}}"))
                throw new IllegalArgumentException("Invalid template placeholder.");
        }
    }
    public static String render(String text, Map<String,String> values) {
        var matcher=TOKEN.matcher(text);
        return matcher.replaceAll(m -> java.util.regex.Matcher.quoteReplacement(values.getOrDefault(m.group(1),"")));
    }
}
