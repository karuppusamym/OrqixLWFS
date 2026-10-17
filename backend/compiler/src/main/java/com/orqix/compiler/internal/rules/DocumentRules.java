package com.orqix.compiler.internal.rules;

import java.util.HashSet;
import java.util.regex.Pattern;

import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.rules.FieldTypes.Resolved;
import com.orqix.compiler.internal.rules.FieldTypes.Unknown;

/** Template placeholders name explicitly mapped scalar fields; mappings obey normal graph availability. */
final class DocumentRules {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]{0,62})}}");
    static void check(RuleContext rc) {
        for (Node node : rc.definition().nodes()) {
            if (!(node instanceof Node.GenerateDocument g)) continue;
            var names = new HashSet<String>();
            var matcher = PLACEHOLDER.matcher(g.template());
            int end = 0;
            boolean malformed = false;
            while (matcher.find()) {
                String literal = g.template().substring(end, matcher.start());
                malformed |= literal.contains("{{") || literal.contains("}}");
                names.add(matcher.group(1)); end = matcher.end();
            }
            String tail = g.template().substring(end);
            malformed |= tail.contains("{{") || tail.contains("}}");
            if (malformed || g.template().isBlank() || g.template().codePoints().anyMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t'))
                rc.issues().blocker("WF_DOCUMENT_TEMPLATE", g.configPath("template"), g.id(), null,
                        "Use plain text with placeholders such as {{customer}}, without expressions or control characters.");
            if (g.fileName().contains("..")) rc.issues().blocker("WF_DOCUMENT_FILENAME", g.configPath("fileName"), g.id(), null,
                    "Use a simple document name without a path.");
            if (!names.equals(g.inputMapping().keySet())) rc.issues().blocker("WF_DOCUMENT_MAPPING", g.configPath("inputMapping"), g.id(), null,
                    "Map exactly the fields used by this document's placeholders.");
            g.inputMapping().forEach((name, ref) -> {
                String path = g.configPath("inputMapping") + "/" + name;
                var resolved = rc.types().resolve(ref);
                if (resolved instanceof Unknown u) rc.issues().blocker("WF_DOCUMENT_FIELD_UNKNOWN", path, g.id(), null, u.message());
                if (resolved instanceof Resolved r && (r.type().kind() == FieldKind.ARRAY || r.type().mayBeAbsent()))
                    rc.issues().blocker("WF_DOCUMENT_FIELD_TYPE", path, g.id(), null, "Document fields must be scalar and always available.");
                if (resolved instanceof Resolved r && r.producer() == null && !rc.definition().inputSchema().required().contains(r.key()))
                    rc.issues().blocker("WF_DOCUMENT_FIELD_OPTIONAL", path, g.id(), null, "Make this request field required before using it in a document.");
                Conditions.availability(rc, ref, g, path, null);
            });
        }
    }
}
