package com.orqix.compiler.internal.rules;

import static com.orqix.compiler.internal.rules.RuleContext.q;

import java.time.ZoneId;
import java.util.Map;

import com.orqix.compiler.ConnectionContracts;
import com.orqix.compiler.internal.eval.DefaultFormValidator;
import com.orqix.compiler.internal.json.JsonPointers;
import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition.FormSchema;
import com.orqix.compiler.internal.rules.FieldTypes.Resolution;
import com.orqix.compiler.internal.rules.FieldTypes.Resolved;
import com.orqix.compiler.internal.rules.FieldTypes.Unknown;

import tools.jackson.databind.JsonNode;

/**
 * Form rules of docs/build/S140: time and date-time fields (bounds written like the field, a real IANA zone), lists
 * whose values must differ ({@code uniqueItems}), the checklist and tag-list widgets, lookup fields (CG-F02) and guest
 * response forms on message waits (CG-F05).
 */
final class FormFieldRules {

    /** The longest validity of a guest response link and the shortest (one hour). */
    static final long GUEST_MAX_MINUTES = 30L * 24 * 60;
    static final long GUEST_MIN_MINUTES = 60;

    private FormFieldRules() {
    }

    static void check(RuleContext rc) {
        fields(rc, rc.definition().inputSchema(), "inputSchema", true);
        fields(rc, rc.definition().outputSchema(), "outputSchema", false);
        widgets(rc);
        for (Node n : rc.definition().nodes()) {
            if (n instanceof Node.WaitForMessage m && m.guestResponse() != null && !rc.graph().isShadowed(n)) {
                guest(rc, m);
            }
        }
    }

    // ---------------------------------------------------------------- fields

    private static void fields(RuleContext rc, FormSchema form, String member, boolean request) {
        if (form == null) {
            return;
        }
        for (FormSchema.Field f : form.properties().values()) {
            String base = JsonPointers.of(member, "properties", f.key());
            temporal(rc, f, base);
            if (f.items() != null) {
                temporal(rc, f.items(), base + "/items");
                for (FormSchema.Field column : f.items().rowFields().values()) {
                    temporal(rc, column, base + "/items/properties/" + JsonPointers.escape(column.key()));
                    if (column.json() != null && column.json().has("lookup")) {
                        invalid(rc, base + "/items/properties/" + JsonPointers.escape(column.key()) + "/lookup",
                                "Lookup options are available on single request questions, not in rows.");
                    }
                }
                if (f.items().json() != null && f.items().json().has("lookup")) {
                    invalid(rc, base + "/items/lookup", "Lookup options are available on single request questions,"
                            + " not on list items.");
                }
            }
            JsonNode json = f.json();
            if (json == null) {
                continue;
            }
            if (json.path("uniqueItems").asBoolean(false) && (f.items() == null || f.items().type() == FieldKind.OBJECT)) {
                invalid(rc, base + "/uniqueItems", q(f.title()) + " is a list of rows; only lists of single values"
                        + " can require each value once.");
            }
            if (json.has("lookup")) {
                if (!request) {
                    invalid(rc, base + "/lookup", q(f.title()) + " is on the result form; lookup options are"
                            + " available on the request form only.");
                } else {
                    lookup(rc, f, json.get("lookup"), base + "/lookup");
                }
            }
        }
    }

    /** Bounds and zone of date, time and date-time fields; other fields may not have them. */
    private static void temporal(RuleContext rc, FormSchema.Field f, String base) {
        JsonNode json = f.json();
        boolean zone = json != null && json.has("timeZone");
        if (zone && f.type() != FieldKind.DATETIME) {
            invalid(rc, base + "/timeZone", q(f.title()) + " is " + f.type().description()
                    + ", so it cannot have a time zone. Only date and time fields (date-time) have one.");
        } else if (zone) {
            String text = json.get("timeZone").asString("");
            try {
                ZoneId.of(text);
            } catch (RuntimeException e) {
                invalid(rc, base + "/timeZone", q(f.title()) + " names the time zone " + q(text)
                        + ", which does not exist. Use an IANA name such as Europe/Berlin or America/New_York.");
            }
        }
        if (f.type() != FieldKind.TIME && f.type() != FieldKind.DATETIME) {
            return; // dates keep their own checks in TypeRules
        }
        Comparable<?> min = bound(rc, f, base + "/formatMinimum", f.formatMinimum(), "earliest");
        Comparable<?> max = bound(rc, f, base + "/formatMaximum", f.formatMaximum(), "latest");
        if (min != null && max != null && compare(min, max) > 0) {
            invalid(rc, base + "/formatMinimum", q(f.title()) + " has an earliest time after its latest time.");
        }
    }

    private static Comparable<?> bound(RuleContext rc, FormSchema.Field f, String path, String text, String which) {
        if (text == null) {
            return null;
        }
        Comparable<?> value = f.type() == FieldKind.TIME ? DefaultFormValidator.parseTime(text)
                : DefaultFormValidator.parseDateTime(text) == null ? null
                : DefaultFormValidator.parseDateTime(text).toInstant();
        if (value == null) {
            invalid(rc, path, q(f.title()) + " has an " + which + " value " + q(text) + " that is not "
                    + (f.type() == FieldKind.TIME ? "a time written HH:MM or HH:MM:SS."
                    : "a date and time with an offset, for example 2026-11-06T09:00:00+01:00."));
        }
        return value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Comparable a, Comparable b) {
        return a.compareTo(b);
    }

    // ---------------------------------------------------------------- widgets

    private static void widgets(RuleContext rc) {
        if (rc.definition().inputUi() == null) {
            return;
        }
        Map<String, FormSchema.Field> fields = rc.definition().inputSchema().properties();
        for (Map.Entry<String, String> e : rc.definition().inputUi().widgets().entrySet()) {
            FormSchema.Field f = fields.get(e.getKey());
            if (f == null) {
                continue;
            }
            boolean scalarTextList = f.type() == FieldKind.ARRAY && f.items() != null
                    && f.items().type() == FieldKind.STRING;
            String need = switch (e.getValue()) {
                case "checklist" -> scalarTextList && f.items().enumValues() != null ? null
                        : "a list of text values with a list of choices (items.enum)";
                case "tags" -> scalarTextList && f.items().enumValues() == null ? null
                        : "a list of free text values (items without enum)";
                default -> null;
            };
            if (need != null) {
                rc.issues().warning("WF_UI_WIDGET_MISMATCH", JsonPointers.of("inputUi", "widgets", e.getKey()), null,
                        null, "The " + e.getValue() + " widget needs " + need + "; " + q(f.title()) + " is not.");
            }
        }
    }

    // ---------------------------------------------------------------- lookups (CG-F02)

    private static void lookup(RuleContext rc, FormSchema.Field f, JsonNode lookup, String path) {
        String title = q(f.title());
        if (f.type() != FieldKind.STRING || f.enumValues() != null || (f.json() != null
                && (f.json().has("pattern") || f.json().has("format")))) {
            lookupIssue(rc, path, title + " gets its options from a lookup, so it must be a plain text question"
                    + " without a fixed list of choices, pattern or format.");
            return;
        }
        if (f.incidentEditable()) {
            lookupIssue(rc, path, title + " gets its options from a lookup and cannot be corrected during an"
                    + " incident, because the corrected value could not be checked against the system.");
        }
        for (Node n : rc.definition().nodes()) {
            if (n instanceof Node.Approval a && a.editableFields() != null && a.editableFields().contains(f.key())) {
                lookupIssue(rc, path, title + " gets its options from a lookup, so " + q(n)
                        + " cannot let the approver correct it.");
            }
        }
        String connection = lookup.path("connection").asString("");
        String operationName = lookup.path("operation").asString("");
        Connections.Pinned pinned = rc.connections().pinned(connection);
        if (pinned == null) {
            rc.issues().blocker("WF_CONNECTION_UNKNOWN", path + "/connection", null, null, title
                    + " uses the connection " + q(connection) + ", which is not listed in dependencies.connections."
                    + " Add it with the version to use.");
            return;
        }
        if (pinned.status() != Connections.Status.OK) {
            return; // reported by BindingRules
        }
        JsonNode operation = rc.connections().operation(connection, operationName);
        if (operation == null) {
            lookupIssue(rc, path + "/operation", "The connection " + q(connection) + " has no operation "
                    + q(operationName) + ".");
            return;
        }
        if (!ConnectionContracts.isV1(pinned.definition()) || !"http".equals(pinned.definition().path("kind").asString(""))) {
            lookupIssue(rc, path + "/connection", title + " needs a connection definition of format 1.0 (HTTP), so"
                    + " its rows can be read.");
            return;
        }
        if (!"readOnly".equals(operation.path("effectClass").asString(""))) {
            lookupIssue(rc, path + "/operation", "The operation " + q(operationName) + " is not read-only. A lookup"
                    + " may only read; choose an operation whose effect class is readOnly.");
            return;
        }
        if (operation.has("async") || operation.has("agent")) {
            lookupIssue(rc, path + "/operation", "The operation " + q(operationName) + " answers later or runs an"
                    + " agent; a lookup needs an immediate read.");
            return;
        }
        Map<String, Connections.RequestField> inputs = Connections.requestFields(operation);
        String search = lookup.path("searchInput").asString("");
        String value = lookup.path("valueInput").asString(search);
        for (String name : new java.util.LinkedHashSet<>(java.util.List.of(search, value))) {
            Connections.RequestField input = inputs.get(name);
            if (input == null || input.kind() != FieldKind.STRING || input.enumValues() != null) {
                lookupIssue(rc, path + (name.equals(search) ? "/searchInput" : "/valueInput"), "The operation "
                        + q(operationName) + " has no free text input " + q(name) + " for the "
                        + (name.equals(search) ? "search text" : "chosen value") + ".");
                return;
            }
        }
        for (String required : Connections.requiredRequestFields(operation)) {
            if (!required.equals(search) && !required.equals(value)) {
                lookupIssue(rc, path + "/operation", "The operation " + q(operationName) + " also requires "
                        + q(required) + ", which a lookup cannot fill. Choose an operation that needs only the"
                        + " search text.");
                return;
            }
            if (!search.equals(value)) {
                // with two inputs, a search sends only searchInput and a check only valueInput
                lookupIssue(rc, path + "/operation", "The operation " + q(operationName) + " requires "
                        + q(required) + " on every call, but the lookup sends it only for "
                        + (required.equals(search) ? "searches" : "checks of the chosen value") + ".");
                return;
            }
        }
        String rows = lookup.path("rows").asString("");
        Connections.ResponseList list = Connections.responseList(operation, rows);
        if (list == null || list.itemKind() != FieldKind.OBJECT) {
            lookupIssue(rc, path + "/rows", "The result " + q(rows) + " of " + q(operationName) + " is not a list of"
                    + " rows. Use a row pointer such as /result/[*].");
            return;
        }
        ConnectionContracts.ResultPointer parsed = ConnectionContracts.parseResult(rows);
        ConnectionContracts.SchemaAt at = ConnectionContracts.schemaAt(operation.get("responseSchema"), parsed.base());
        Map<String, JsonNode> columns = ConnectionContracts.rowColumns(at.schema().get("items"));
        for (String key : new String[] {"valueColumn", "labelColumn", "detailColumn"}) {
            if (!lookup.has(key)) {
                continue;
            }
            String column = lookup.get(key).asString("");
            JsonNode schema = columns.get(column);
            if (schema == null || !"string".equals(schema.path("type").asString(""))) {
                lookupIssue(rc, path + "/" + key, "The rows of " + q(operationName) + " have no text column "
                        + q(column) + ".");
            }
        }
    }

    private static void lookupIssue(RuleContext rc, String path, String message) {
        rc.issues().blocker("WF_LOOKUP_INVALID", path, null, null, message);
    }

    // ---------------------------------------------------------------- guest response forms (CG-F05)

    private static void guest(RuleContext rc, Node.WaitForMessage m) {
        JsonNode g = m.guestResponse();
        String base = m.configPath("guestResponse");
        String validFor = g.path("linkValidFor").asString("");
        long minutes;
        try {
            minutes = com.orqix.compiler.internal.model.Durations.minutes(validFor);
        } catch (RuntimeException e) {
            minutes = -1;
        }
        if (minutes < GUEST_MIN_MINUTES || minutes > GUEST_MAX_MINUTES) {
            rc.issues().blocker("WF_GUEST_LINK_VALIDITY", base + "/linkValidFor", m.id(), null, q(m)
                    + " sends a guest link valid for " + validFor + ". Choose a time from 1 hour (PT1H) to 30 days"
                    + " (P30D).");
        }
        for (String key : new String[] {"title", "instructions"}) {
            String text = g.path(key).asString("");
            if (text.contains("${") || text.contains("#{") || text.contains("<")
                    || text.codePoints().anyMatch(c -> (c < 0x20 && c != '\n') || c == 0x7F)) {
                rc.issues().blocker("WF_GUEST_FORM_INVALID", base + "/" + key, m.id(), null, "The guest form "
                        + key + " must be plain text without \"<\", \"${\" or \"#{\".");
            }
        }
        if (m.payload().isEmpty() && !g.has("attachments")) {
            rc.issues().blocker("WF_GUEST_FORM_INVALID", base, m.id(), null, q(m) + " has a guest form without"
                    + " questions or files. Declare the values to ask for (payload) or allow attachments.");
        }
        if (g.has("recipientFrom")) {
            String ref = g.get("recipientFrom").asString("");
            Resolution resolution = rc.types().resolve(ref);
            if (!ref.startsWith("/input/")) {
                // The address is read from the stored request when the link is sent (D-CG7), never kept elsewhere.
                rc.issues().blocker("WF_GUEST_RECIPIENT", base + "/recipientFrom", m.id(), null, q(m)
                        + " sends the guest link to " + ref + ". Choose a request field (/input/...) with the guest's"
                        + " email address.");
            } else if (resolution instanceof Unknown u) {
                rc.issues().blocker("WF_GUEST_RECIPIENT", base + "/recipientFrom", m.id(), null, q(m)
                        + " sends the guest link to " + ref + ", but " + u.message());
            } else if (resolution instanceof Resolved r) {
                if (r.type().kind() != FieldKind.STRING || r.type().domain() != null) {
                    rc.issues().blocker("WF_GUEST_RECIPIENT", base + "/recipientFrom", m.id(), null, q(m)
                            + " sends the guest link to " + ref + ", which is " + r.type().kind().description()
                            + ". Choose a text field with the guest's email address.");
                } else {
                    Conditions.availability(rc, ref, m, base + "/recipientFrom", null);
                }
            }
        }
    }

    private static void invalid(RuleContext rc, String path, String message) {
        rc.issues().blocker("WF_FORM_INVALID", path, null, null, message);
    }
}
