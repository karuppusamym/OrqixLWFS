package com.orqix.compiler.internal.rules;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.orqix.compiler.AdapterMessages;
import com.orqix.compiler.CompileContext;
import com.orqix.compiler.internal.json.StrictJson;
import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.rules.FieldTypes.Resolved;
import com.orqix.compiler.internal.rules.FieldTypes.Unknown;

import tools.jackson.databind.JsonNode;

/**
 * "Send through an adapter" (S68). The step names an adapter of the workspace and must state its kind, so it is never
 * silently re-pointed to another kind of system. Recipients and the Kafka topic are fixed in the step and must be on the
 * adapter's allowlist: request data can never choose who receives a message or where it goes, and the adapter being
 * available grants nothing by itself. The runtime checks the same rules again against the adapter's current settings.
 */
final class AdapterStepRules {

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@([A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63})+)$");
    private static final Pattern TOPIC = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$");

    private AdapterStepRules() {
    }

    static void check(RuleContext rc) {
        for (Node node : rc.definition().nodes()) {
            if (node instanceof Node.SendAdapter step && !rc.graph().isShadowed(node)) {
                check(rc, step);
            }
        }
    }

    private static void check(RuleContext rc, Node.SendAdapter s) {
        CompileContext.AdapterInfo adapter = rc.workspace().adapters().get(s.adapterRef());
        if (adapter == null) {
            rc.issues().blocker("WF_ADAPTER_UNKNOWN", s.configPath("adapter"), s.id(), null, RuleContext.q(s)
                    + " sends through the adapter " + RuleContext.q(s.adapterRef())
                    + ", which does not exist in this workspace. A connection manager must create it first.");
        } else if (!adapter.kind().equals(s.adapterKind())) {
            rc.issues().blocker("WF_ADAPTER_KIND", s.configPath("kind"), s.id(), null, RuleContext.q(s)
                    + " expects a " + s.adapterKind() + " adapter, but " + RuleContext.q(adapter.name()) + " is a "
                    + adapter.kind() + " adapter.");
        } else if (!adapter.enabled()) {
            rc.issues().blocker("WF_ADAPTER_DISABLED", s.configPath("adapter"), s.id(), null, RuleContext.q(s)
                    + " sends through " + RuleContext.q(adapter.name()) + ", which is disabled.");
        }
        JsonNode config = adapter == null ? null : StrictJson.parse(adapter.configJson());
        boolean kindMatches = adapter != null && adapter.kind().equals(s.adapterKind());

        Set<String> names = s.inputMapping().keySet();
        switch (s.adapterKind()) {
            case "email" -> {
                only(rc, s, Set.of("to", "subject", "body"));
                if (s.to().isEmpty() || s.subject() == null || s.body() == null) {
                    message(rc, s, "to", "An email needs recipients, a subject and a body.");
                }
                singleLine(rc, s, "subject", s.subject());
                recipients(rc, s, kindMatches ? config : null);
                placeholders(rc, s, "subject", s.subject(), names);
                placeholders(rc, s, "body", s.body(), names);
            }
            case "teams" -> {
                only(rc, s, Set.of("title", "text"));
                if (s.text() == null) {
                    message(rc, s, "text", "A Teams message needs a text.");
                }
                singleLine(rc, s, "title", s.title());
                placeholders(rc, s, "title", s.title(), names);
                placeholders(rc, s, "text", s.text(), names);
            }
            case "kafka" -> {
                only(rc, s, Set.of("topic", "key"));
                if (s.topic() == null || !TOPIC.matcher(s.topic()).matches()) {
                    message(rc, s, "topic", "Name one exact topic to send to.");
                } else if (kindMatches && !strings(config.path("allowedTopics")).contains(s.topic())) {
                    rc.issues().blocker("WF_ADAPTER_TOPIC", s.configPath("topic"), s.id(), null, "The topic "
                            + RuleContext.q(s.topic()) + " is not approved by the adapter " + RuleContext.q(adapter.name())
                            + ". Approved topics: " + String.join(", ", strings(config.path("allowedTopics"))) + ".");
                }
                placeholders(rc, s, "key", s.messageKey(), names);
                if (names.isEmpty()) {
                    message(rc, s, "inputMapping", "Map at least one value to send as the message value.");
                }
            }
            case "jdbc" -> {
                only(rc, s, Set.of());
                if (kindMatches) {
                    Set<String> declared = new TreeSet<>(strings(config.path("parameters")));
                    if (!declared.equals(new TreeSet<>(names))) {
                        rc.issues().blocker("WF_ADAPTER_PARAMETERS", s.configPath("inputMapping"), s.id(), null,
                                "Map exactly the parameters the adapter declares: "
                                        + (declared.isEmpty() ? "none" : String.join(", ", declared)) + ".");
                    }
                }
            }
            default -> message(rc, s, "kind", "Choose email, teams, kafka or jdbc.");
        }

        s.inputMapping().forEach((name, ref) -> {
            String path = s.configPath("inputMapping") + "/" + name;
            var resolved = rc.types().resolve(ref);
            if (resolved instanceof Unknown u) {
                rc.issues().blocker("WF_ADAPTER_INPUT_UNKNOWN", path, s.id(), null, u.message());
            }
            if (resolved instanceof Resolved r && (r.type().kind() == FieldKind.ARRAY || r.type().kind() == FieldKind.OBJECT
                    || r.type().kind() == FieldKind.ATTACHMENT)) {
                rc.issues().blocker("WF_ADAPTER_INPUT_TYPE", path, s.id(), null,
                        "An adapter receives single values only; map a text, number, true/false or date field.");
            }
            if (resolved instanceof Resolved r && r.type().mayBeAbsent()) {
                rc.issues().blocker("WF_ADAPTER_INPUT_OPTIONAL", path, s.id(), null,
                        "This value may be missing when the step starts. Make it required or map one that is always present.");
            }
            Conditions.availability(rc, ref, s, path, null);
        });
    }

    /** Members of another adapter kind are refused so nothing is silently ignored. */
    private static void only(RuleContext rc, Node.SendAdapter s, Set<String> allowed) {
        String[][] members = {{"to", s.to().isEmpty() ? null : "x"}, {"subject", s.subject()}, {"body", s.body()},
                {"title", s.title()}, {"text", s.text()}, {"topic", s.topic()}, {"key", s.messageKey()}};
        for (String[] member : members) {
            if (member[1] != null && !allowed.contains(member[0])) {
                message(rc, s, member[0], "A " + s.adapterKind() + " step does not use " + RuleContext.q(member[0])
                        + "; remove it.");
            }
        }
    }

    private static void recipients(RuleContext rc, Node.SendAdapter s, JsonNode config) {
        Set<String> unique = new LinkedHashSet<>();
        Set<String> allowed = config == null ? null : new LinkedHashSet<>(strings(config.path("allowedRecipientDomains")));
        int max = config == null ? 10 : config.path("maxRecipients").asInt(5);
        for (int i = 0; i < s.to().size(); i++) {
            String address = s.to().get(i);
            String path = s.configPath("to") + "/" + i;
            Matcher m = EMAIL.matcher(address);
            if (!m.matches() || address.contains("{{")) {
                rc.issues().blocker("WF_ADAPTER_RECIPIENT", path, s.id(), null, RuleContext.q(address)
                        + " is not a plain email address. Recipients are fixed addresses, never values from the request.");
                continue;
            }
            if (!unique.add(address.toLowerCase(Locale.ROOT))) {
                rc.issues().blocker("WF_ADAPTER_RECIPIENT", path, s.id(), null, RuleContext.q(address) + " is listed twice.");
            }
            String domain = m.group(1).toLowerCase(Locale.ROOT);
            if (allowed != null && !allowed.contains(domain)) {
                rc.issues().blocker("WF_ADAPTER_RECIPIENT", path, s.id(), null, "The domain " + RuleContext.q(domain)
                        + " is not approved by this adapter. Approved domains: " + String.join(", ", allowed) + ".");
            }
        }
        if (s.to().size() > max) {
            rc.issues().blocker("WF_ADAPTER_RECIPIENT", s.configPath("to"), s.id(), null,
                    "This adapter allows at most " + max + " recipients per message.");
        }
    }

    private static void singleLine(RuleContext rc, Node.SendAdapter s, String member, String text) {
        if (text != null && (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0)) {
            message(rc, s, member, "Line breaks are not allowed in the " + member + ".");
        }
    }

    private static void placeholders(RuleContext rc, Node.SendAdapter s, String member, String text, Set<String> names) {
        if (text == null) {
            return;
        }
        String problem = AdapterMessages.problem(text, names);
        if (problem != null) {
            rc.issues().blocker("WF_ADAPTER_PLACEHOLDER", s.configPath(member), s.id(), null, problem);
        }
    }

    private static void message(RuleContext rc, Node.SendAdapter s, String member, String text) {
        rc.issues().blocker("WF_ADAPTER_MESSAGE", s.configPath(member), s.id(), null, RuleContext.q(s) + ": " + text);
    }

    private static Set<String> strings(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asString()));
        }
        return out;
    }
}
