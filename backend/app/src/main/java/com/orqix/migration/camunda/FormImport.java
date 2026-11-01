package com.orqix.migration.camunda;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * Reads a Camunda Forms document ({@code .form} JSON, or the JSON embedded in a Camunda 8 BPMN as
 * {@code zeebe:userTaskForm}) and gives every component a disposition. Supported components become fields of the workflow's
 * request form (canonical {@code inputSchema} and {@code inputUi}); everything else is reported with a reason code and is
 * never silently dropped. Pure Java and bounded: the caller has already limited the source to 1 MiB; this class limits the
 * component count and nesting. Expressions in a form are never evaluated.
 */
public final class FormImport {

    public static final String ADAPTER_VERSION = "camunda-form-0.1";
    public static final String SOURCE_SYSTEM = "camundaForm";

    /** At most this many components in one form (all levels). */
    static final int MAX_COMPONENTS = 300;
    /** The canonical request form holds at most this many fields. */
    static final int MAX_FIELDS = 30;
    private static final int MAX_DEPTH = 6;
    private static final Pattern SAFE_OPTION = Pattern.compile("^[A-Za-z0-9 _.,:/-]{1,100}$");
    private static final Set<String> DISPLAY = Set.of("text", "html", "image", "separator", "spacer", "button");
    private static final Map<String, String> BLOCKED = Map.ofEntries(
            Map.entry("table", "A table shows or edits a list of rows. Workflow request forms hold single values and bounded lists of simple items only."),
            Map.entry("dynamiclist", "A dynamic list repeats a group of fields. Workflow request forms cannot repeat groups."),
            Map.entry("filepicker", "A file picker uploads files. Attach files to a request from the request page or add a file field in the studio."),
            Map.entry("expression", "A computed field is evaluated from an expression, which Orqix never imports or runs."),
            Map.entry("iframe", "An embedded page cannot be part of a request form."),
            Map.entry("documentPreview", "A document preview needs document storage that is not part of the request form."),
            Map.entry("checklist", "A checklist holds several selected values. A request form field holds one value."),
            Map.entry("taglist", "A tag list holds several selected values. A request form field holds one value."));

    /** Strict parser for forms embedded in a BPMN (no Spring needed): duplicate keys and trailing tokens are rejected. */
    private static final Json EMBEDDED_JSON = new Json(tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build());

    private FormImport() {
    }

    /** A request form field produced from one component. {@code schema} is a canonical {@code formField} object. */
    public record Field(String componentId, String sourceKey, String fieldKey, String label, String component, String type,
            String widget, boolean required, List<String> options, String description, Map<String, Object> schema) {
    }

    /** The result of reading one form: fields in form order, a disposition per component, and the form id. */
    public record Model(String formId, String platformVersion, List<Field> fields, List<Report.Issue> issues,
            List<Report.FormField> skipped) {

        /** True when no component is blocked (info and review items never block). */
        public boolean convertible() {
            return issues.stream().noneMatch(i -> Report.BLOCKER.equals(i.severity()) || Report.ACTION.equals(i.severity()));
        }

        /**
         * The issues of this form for the step that uses it. A user task form is not a request form: its fields become a
         * note on the approval step, so the form-level item says that instead.
         */
        public List<Report.Issue> issuesFor(String target, String elementName) {
            if (!"userTask".equals(target)) {
                return issues;
            }
            List<Report.Issue> shown = new ArrayList<>();
            for (Report.Issue i : issues) {
                if ("FORM_MAPPED".equals(i.reasonCode())) {
                    shown.add(new Report.Issue(i.sourceElementId(), i.sourceName(), i.sourceKind(), Report.REVIEW, "TASK_FORM_NOTED",
                            fields.size() + " input field(s) of this form (" + String.join(", ", fields.stream().map(Field::label).toList())
                                    + ") are recorded as a plain-text note on the approval step" + (elementName == null ? "" : " '"
                                    + elementName + "'") + ". Approvals do not collect fields: the approver sees the request fields, "
                                    + "decides and comments.",
                            "Check that nothing the approver types in this form is needed by a later step.", null, Report.OPEN, i.sourcePath()));
                } else if (Report.VERIFY.equals(i.severity())) {
                    // A task form's fields only become a note, so a changed field is not changed request behaviour.
                    shown.add(new Report.Issue(i.sourceElementId(), i.sourceName(), i.sourceKind(), Report.REVIEW, i.reasonCode(),
                            i.explanation(), i.suggestedAction(), i.targetNodeId(), i.resolutionStatus(), i.sourcePath()));
                } else {
                    shown.add(i);
                }
            }
            return shown;
        }

        /** Plain-text note listing the fields of a user task form (at most 2000 characters, the schema limit). */
        public String note(String formName) {
            StringBuilder note = new StringBuilder("Imported from the Camunda form '").append(formId == null ? formName : formId)
                    .append("'. Fields the source task collected: ");
            for (int i = 0; i < fields.size(); i++) {
                Field f = fields.get(i);
                note.append(i == 0 ? "" : "; ").append(f.label()).append(" (").append(f.widget()).append(f.required() ? ", required" : "")
                        .append(")");
            }
            if (fields.isEmpty()) {
                note.append("none");
            }
            note.append(". Approvals do not collect fields; this is a record of the source form only.");
            return note.length() > 2000 ? note.substring(0, 1990) + "..." : note.toString();
        }

        public Report.FormPreview preview() {
            List<Report.FormField> shown = new ArrayList<>();
            for (Field f : fields) {
                shown.add(new Report.FormField(f.componentId(), f.sourceKey(), f.label(), f.component(), f.type(), f.widget(),
                        f.required(), f.options(), f.description(), Report.SUPPORTED, null));
            }
            return new Report.FormPreview(formId, shown, skipped);
        }
    }

    /**
     * Parses a form document that came as a stand-alone file.
     *
     * @throws SourceException when the text is not JSON or not a Camunda form
     */
    public static Model parse(String text, Json json, String where) {
        JsonNode root;
        try {
            root = (json == null ? EMBEDDED_JSON : json).parse(text);
        } catch (RuntimeException e) {
            throw SourceException.invalid("The file is not valid JSON, so it cannot be a Camunda form.");
        }
        if (!root.isObject() || Json.depth(root) > 64) {
            throw SourceException.invalid("A Camunda form is a JSON object (nesting of at most 64 levels).");
        }
        if (!root.path("components").isArray()) {
            throw SourceException.invalid("This JSON file is not a Camunda form: it has no 'components' list.");
        }
        String type = root.path("type").asString("default");
        if (!"default".equals(type)) {
            throw SourceException.invalid("This JSON file is not a Camunda form (type '" + type + "').");
        }
        return new Reader(root, where).read();
    }

    /** Compatibility report of a stand-alone form upload (source system {@code camundaForm}). */
    public static Report.CompatibilityReport report(Model model, String sourceHash, String sourceVersion, String title) {
        List<Report.Issue> issues = new ArrayList<>(model.issues());
        List<String> assumptions = new ArrayList<>();
        assumptions.add("Source detected as a Camunda form (form id '" + model.formId() + "'"
                + (sourceVersion == null ? "" : ", platform version " + sourceVersion) + ").");
        assumptions.add("A form has no target of its own: attach it to a Camunda 8 BPMN import. A form on a start event becomes the "
                + "request form; a form on a user task becomes a note on the approval step, because approvals do not collect fields.");
        assumptions.add("Layout, styling, labels of options, default values and validation patterns are not imported. Adapter "
                + ADAPTER_VERSION + " reads the components listed in the report only; it does not claim support for every Camunda form.");
        Map<String, Report.FormPreview> previews = new LinkedHashMap<>();
        previews.put(model.formId() == null ? "form" : model.formId(), model.preview());
        boolean ready = issues.stream().noneMatch(i -> i.blocksPublishing())
                && issues.stream().noneMatch(i -> Report.INFO.equals(i.severity()) && Report.OPEN.equals(i.resolutionStatus()));
        return new Report.CompatibilityReport(SOURCE_SYSTEM, sourceVersion, ADAPTER_VERSION, sourceHash, model.formId(), title,
                Report.ElementCounts.of(issues), issues, assumptions,
                new Report.RequiredMappings(List.of(), List.of(), List.of()), ready, List.of(),
                new Report.MappingHints(null, null, null, previews));
    }

    // ------------------------------------------------------------------------------------------------ reader

    private static final class Reader {
        private final JsonNode root;
        private final String where;
        private final String formId;
        private final List<Field> fields = new ArrayList<>();
        private final List<Report.Issue> issues = new ArrayList<>();
        private final List<Report.FormField> skipped = new ArrayList<>();
        private final Set<String> keys = new LinkedHashSet<>();
        private int visited;

        Reader(JsonNode root, String where) {
            this.root = root;
            this.where = where;
            String id = root.path("id").asString("").strip();
            this.formId = id.isEmpty() ? null : id;
        }

        Model read() {
            if (formId == null) {
                add(null, "form", "form", Report.BLOCKER, "FORM_ID_MISSING", "The form has no id, so it cannot be matched to the step "
                        + "that uses it.", "Export the form again from the modeler.", "$");
            }
            walk(root.path("components"), "components", 0, false);
            if (fields.size() > MAX_FIELDS) {
                add(null, "form", "form", Report.BLOCKER, "FORM_TOO_LARGE", "The form has " + fields.size() + " input fields; a request "
                        + "form holds at most " + MAX_FIELDS + ".", "Split the form or remove fields that no later step uses.", "$");
            }
            if (fields.isEmpty() && issues.stream().noneMatch(i -> Report.BLOCKER.equals(i.severity()))) {
                add(null, "form", "form", Report.INFO, "FORM_EMPTY", "The form has no input fields; nothing becomes a request field.",
                        "Acknowledge.", "$");
            } else if (!fields.isEmpty()) {
                add(null, "form", "form", Report.REVIEW, "FORM_MAPPED", fields.size() + " input field(s) become fields of the workflow "
                        + "request form, in the same order. Orqix decides how they are drawn (text box, number box, choice list, "
                        + "checkbox); the source layout is not imported.",
                        "Open the request form in the studio and check labels, required fields and choices.", "$");
            }
            return new Model(formId, root.path("executionPlatformVersion").asString(null), List.copyOf(fields), List.copyOf(issues),
                    List.copyOf(skipped));
        }

        private void walk(JsonNode components, String path, int depth, boolean insideGroup) {
            if (!components.isArray()) {
                return;
            }
            int index = 0;
            for (JsonNode component : components) {
                String here = path + "[" + index++ + "]";
                if (++visited > MAX_COMPONENTS) {
                    if (visited == MAX_COMPONENTS + 1) {
                        add(null, "form", "form", Report.BLOCKER, "FORM_TOO_LARGE", "The form has more than " + MAX_COMPONENTS
                                + " components; the rest were not read.", "Split the form.", here);
                    }
                    return;
                }
                if (!component.isObject()) {
                    add(null, "component", "unknown", Report.BLOCKER, "FORM_COMPONENT_NOT_SUPPORTED", "A component is not an object.",
                            "Export the form again from the modeler.", here);
                    continue;
                }
                component(component, here, depth, insideGroup);
            }
        }

        private void component(JsonNode c, String path, int depth, boolean insideGroup) {
            String type = c.path("type").asString("");
            String id = c.path("id").asString("").strip();
            String key = c.path("key").asString("").strip();
            String label = c.path("label").asString("").strip();
            String ref = !id.isEmpty() ? id : !key.isEmpty() ? key : type + "#" + path;
            String shown = !label.isEmpty() ? label : !key.isEmpty() ? key : type;
            if (c.has("conditional") && !c.path("conditional").path("hide").asString("").isBlank()) {
                skip(ref, key, shown, type, "FORM_EXPRESSION_NOT_SUPPORTED", "'" + shown + "' is shown or hidden by the expression "
                        + c.path("conditional").path("hide").asString("") + ". Request forms always show every field, and a hidden "
                        + "required field would block the request, so this component is not imported.",
                        "Rebuild the conditional part as a separate step or a choice in the studio.", path);
                return;
            }
            if (label.startsWith("=")) {
                skip(ref, key, shown, type, "FORM_EXPRESSION_NOT_SUPPORTED", "The label of '" + type + "' is the expression " + label
                        + ".", "Use a fixed label.", path);
                return;
            }
            switch (type) {
                case "group" -> group(c, ref, shown, path, depth);
                case "textfield", "textarea" -> text(c, type, ref, key, shown, path);
                case "number" -> number(c, ref, key, shown, path);
                case "checkbox" -> checkbox(c, ref, key, shown, path);
                case "select", "radio" -> choice(c, type, ref, key, shown, path);
                case "datetime" -> date(c, ref, key, shown, path);
                default -> {
                    if (DISPLAY.contains(type)) {
                        skipped.add(new Report.FormField(ref, key.isEmpty() ? null : key, shown, type, null, null, false, List.of(),
                                null, Report.INFO, "FORM_DISPLAY_NOT_IMPORTED"));
                        add(ref, shown, "component", Report.INFO, "FORM_DISPLAY_NOT_IMPORTED", "The " + type + " component '" + shown
                                + "' only displays content (no value is collected) and is not imported.",
                                "Acknowledge; add the explanation to the workflow description or a step note if requesters need it.", path);
                    } else if (BLOCKED.containsKey(type)) {
                        skip(ref, key, shown, type, "FORM_COMPONENT_NOT_SUPPORTED", "The " + type + " component '" + shown + "' cannot "
                                + "be imported. " + BLOCKED.get(type), "Rebuild this part in the studio, or remove it from the source form.", path);
                    } else {
                        skip(ref, key, shown, type, "FORM_COMPONENT_NOT_SUPPORTED", "The component type '" + type + "' is not known to "
                                + "the importer.", "Rebuild this part in the studio, or remove it from the source form.", path);
                    }
                }
            }
        }

        private void group(JsonNode c, String ref, String shown, String path, int depth) {
            boolean dynamic = c.path("isDynamic").asBoolean(false) || !c.path("path").asString("").isBlank();
            if (dynamic || depth >= MAX_DEPTH) {
                skip(ref, "", shown, "group", "FORM_COMPONENT_NOT_SUPPORTED", "The group '" + shown + "' "
                        + (dynamic ? "stores its values under a path or repeats, which needs nested or repeated data."
                        : "is nested too deeply.") + " Request forms are flat.",
                        "Rebuild this part in the studio, or flatten the group in the source form.", path);
                return;
            }
            add(ref, shown, "component", Report.INFO, "FORM_GROUP_FLATTENED", "The group '" + shown + "' is only a visual frame. Its "
                    + "fields are imported one after another without the frame.", "Acknowledge.", path);
            walk(c.path("components"), path + "/components", depth + 1, true);
        }

        private void text(JsonNode c, String type, String ref, String key, String shown, String path) {
            Field base = start(c, ref, key, shown, type, "string", "textfield".equals(type) ? "text" : "textarea", path);
            if (base == null) {
                return;
            }
            Map<String, Object> schema = base.schema();
            JsonNode validate = c.path("validate");
            List<String> notes = new ArrayList<>();
            Integer min = intOf(validate.path("minLength"));
            Integer max = intOf(validate.path("maxLength"));
            if (min != null && min >= 0 && min <= 4000) {
                schema.put("minLength", min);
            } else if (validate.has("minLength")) {
                notes.add("minimum length");
            }
            if (max != null && max >= 1 && max <= 4000 && (min == null || min <= max)) {
                schema.put("maxLength", max);
            } else if (validate.has("maxLength")) {
                notes.add("maximum length");
            }
            if (!validate.path("pattern").asString("").isBlank() || !validate.path("validationType").asString("").isBlank()) {
                notes.add("pattern or format check");
            }
            finish(c, base, notes, path);
        }

        private void number(JsonNode c, String ref, String key, String shown, String path) {
            JsonNode digits = c.path("decimalDigits");
            boolean whole = digits.isNumber() && digits.asInt(-1) == 0;
            Field base = start(c, ref, key, shown, "number", whole ? "integer" : "number", "number", path);
            if (base == null) {
                return;
            }
            Map<String, Object> schema = base.schema();
            JsonNode validate = c.path("validate");
            List<String> notes = new ArrayList<>();
            if (digits.isNumber() && digits.asInt(-1) == 2) {
                schema.put("multipleOf", 0.01);
            } else if (digits.isNumber() && !whole) {
                notes.add("number of decimal digits");
            }
            if (c.has("increment") && !c.path("increment").asString("").isBlank()) {
                notes.add("step size");
            }
            for (String bound : List.of("min", "max")) {
                JsonNode value = validate.path(bound);
                if (value.isNumber()) {
                    schema.put("min".equals(bound) ? "minimum" : "maximum", value.numberValue());
                } else if (validate.has(bound)) {
                    notes.add(bound + "imum as an expression");
                }
            }
            finish(c, base, notes, path);
        }

        private void checkbox(JsonNode c, String ref, String key, String shown, String path) {
            Field base = start(c, ref, key, shown, "checkbox", "boolean", "checkbox", path);
            if (base == null) {
                return;
            }
            List<String> notes = new ArrayList<>();
            if (base.required()) {
                notes.add("'required' on a checkbox means it must be ticked; the request form only needs a yes or no answer");
            }
            finish(c, base, notes, path);
        }

        private void choice(JsonNode c, String type, String ref, String key, String shown, String path) {
            if (c.has("valuesKey") || c.has("valuesExpression")) {
                skip(ref, key, shown, type, c.has("valuesExpression") ? "FORM_EXPRESSION_NOT_SUPPORTED" : "FORM_OPTIONS_NOT_STATIC",
                        "The options of '" + shown + "' come from " + (c.has("valuesExpression") ? "the expression "
                        + c.path("valuesExpression").asString("") : "the variable '" + c.path("valuesKey").asString("") + "'")
                        + ". Only a fixed list of options can be imported.",
                        "Use a fixed list in the source form, or rebuild the choice in the studio.", path);
                return;
            }
            List<String> values = new ArrayList<>();
            boolean labelsDiffer = false;
            JsonNode options = c.path("values");
            if (!options.isArray() || options.isEmpty() || options.size() > 50) {
                skip(ref, key, shown, type, "FORM_OPTION_NOT_SUPPORTED", "'" + shown + "' needs 1 to 50 fixed options.",
                        "Fix the options in the source form.", path);
                return;
            }
            for (JsonNode option : options) {
                String value = option.path("value").asString("");
                if (!SAFE_OPTION.matcher(value).matches() || values.contains(value)) {
                    skip(ref, key, shown, type, "FORM_OPTION_NOT_SUPPORTED", "An option value of '" + shown + "' is empty, repeated, "
                            + "longer than 100 characters or uses characters workflows do not allow (letters, digits, spaces and "
                            + "_ . , : / - only).", "Rename the option values in the source form.", path);
                    return;
                }
                values.add(value);
                if (!value.equals(option.path("label").asString(value))) {
                    labelsDiffer = true;
                }
            }
            Field base = start(c, ref, key, shown, type, "string", type, path);
            if (base == null) {
                return;
            }
            base.schema().put("enum", List.copyOf(values));
            List<String> notes = new ArrayList<>();
            if (labelsDiffer) {
                notes.add("option labels differ from option values: requesters see and send the values");
            }
            Field withOptions = new Field(base.componentId(), base.sourceKey(), base.fieldKey(), base.label(), base.component(),
                    base.type(), base.widget(), base.required(), List.copyOf(values), base.description(), base.schema());
            fields.set(fields.indexOf(base), withOptions);
            finish(c, withOptions, notes, path);
        }

        private void date(JsonNode c, String ref, String key, String shown, String path) {
            String subtype = c.path("subtype").asString("date");
            if (!"date".equals(subtype)) {
                skip(ref, key, shown, "datetime", "FORM_COMPONENT_NOT_SUPPORTED", "'" + shown + "' collects a " + subtype
                        + ". Request forms collect calendar dates (day, month, year) only.",
                        "Use a date component, or rebuild this field in the studio.", path);
                return;
            }
            Field base = start(c, ref, key, shown, "datetime", "string", "text", path);
            if (base == null) {
                return;
            }
            base.schema().put("format", "date");
            finish(c, base, List.of(), path);
        }

        /** Creates the field with title, description, required flag and a valid key; null when the component is rejected. */
        private Field start(JsonNode c, String ref, String key, String shown, String component, String type, String widget,
                String path) {
            String fieldKey = key.isEmpty() ? null : Analyzer.fieldKey(key);
            if (fieldKey == null) {
                skip(ref, key, shown, component, "FORM_KEY_NOT_SUPPORTED", "The field '" + shown + "' has "
                        + (key.isEmpty() ? "no key" : "the key '" + key + "', which cannot be used as a request field name (letters "
                        + "and digits only, starting with a letter)") + ".", "Set a simple key in the source form.", path);
                return null;
            }
            if (!keys.add(fieldKey)) {
                skip(ref, key, shown, component, "FORM_KEY_NOT_SUPPORTED", "The key '" + key + "' is used by more than one field "
                        + "(or becomes the same request field name as another key).", "Give each field its own key.", path);
                return null;
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", type);
            schema.put("title", shown.length() > 100 ? shown.substring(0, 100) : shown);
            String description = c.path("description").asString("").strip();
            if (!description.isEmpty()) {
                schema.put("description", description.length() > 500 ? description.substring(0, 500) : description);
            }
            boolean required = c.path("validate").path("required").asBoolean(false);
            Field field = new Field(ref, key, fieldKey, shown, component, type, widget, required, List.of(),
                    description.isEmpty() ? null : description, schema);
            fields.add(field);
            return field;
        }

        private void finish(JsonNode c, Field field, List<String> notes, String path) {
            List<String> changes = new ArrayList<>(notes);
            if (c.has("defaultValue") && !c.path("defaultValue").isNull() && !c.path("defaultValue").asString("").isBlank()) {
                add(field.componentId(), field.label(), "component", Report.INFO, "FORM_DEFAULT_NOT_IMPORTED", "The default value of '"
                        + field.label() + "' is not imported; request fields start empty.",
                        "Acknowledge, or describe the usual value in the field description.", path);
            }
            if (c.path("readonly").asBoolean(false) || c.path("disabled").asBoolean(false)) {
                changes.add("it is read-only in the source, but requesters can edit it here");
            }
            if (!field.sourceKey().equals(field.fieldKey())) {
                changes.add("the key '" + field.sourceKey() + "' becomes the request field '" + field.fieldKey() + "'");
            }
            if (field.label().length() > 100) {
                changes.add("the label was shortened to 100 characters");
            }
            if (changes.isEmpty()) {
                add(field.componentId(), field.label(), "component", Report.SUPPORTED, "FORM_FIELD_MAPPED", "'" + field.label() + "' becomes "
                        + describe(field) + (field.required() ? ", required" : "") + ".", null, path);
            } else {
                add(field.componentId(), field.label(), "component", Report.VERIFY, "FORM_FIELD_MAPPED_WITH_CHANGES", "'" + field.label()
                        + "' becomes " + describe(field) + (field.required() ? ", required" : "") + ", with differences: "
                        + String.join("; ", changes) + ".", "Check the field in the request form before publishing.", path);
            }
        }

        private static String describe(Field f) {
            return switch (f.widget()) {
                case "textarea" -> "a long text field";
                case "number" -> "integer".equals(f.type()) ? "a whole-number field" : "a number field";
                case "checkbox" -> "a yes/no checkbox";
                case "select" -> "a choice list of " + f.options().size() + " options";
                case "radio" -> "a one-of-" + f.options().size() + " choice";
                default -> "date".equals(f.schema().get("format")) ? "a date field" : "a text field";
            };
        }

        private void skip(String ref, String key, String shown, String component, String code, String explanation, String suggestion,
                String path) {
            skipped.add(new Report.FormField(ref, key.isEmpty() ? null : key, shown, component, null, null, false, List.of(), null,
                    Report.BLOCKER, code));
            add(ref, shown, "component", Report.BLOCKER, code, explanation, suggestion, path);
        }

        private void add(String id, String name, String kind, String severity, String code, String explanation, String suggestion,
                String path) {
            String owner = id == null ? (formId == null ? "form" : formId) : id;
            issues.add(new Report.Issue(owner, name, "form".equals(kind) ? "form" : "formComponent", severity, code, explanation,
                    suggestion, null, Report.SUPPORTED.equals(severity) ? Report.NOT_NEEDED : Report.OPEN,
                    (where == null ? "form" : where) + "/" + path));
        }

        private static Integer intOf(JsonNode node) {
            return node.isIntegralNumber() && node.canConvertToInt() ? node.asInt() : null;
        }
    }
}
