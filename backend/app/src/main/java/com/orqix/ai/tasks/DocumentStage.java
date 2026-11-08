package com.orqix.ai.tasks;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleUnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.orqix.ai.ocr.OcrProviders;
import com.orqix.ai.skills.AiSkills;
import com.orqix.compiler.DocumentTemplates;
import com.orqix.integration.AiTaskPort;
import com.orqix.integration.OcrDocument;
import com.orqix.integration.OcrException;
import com.orqix.integration.RunDocuments;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Document extraction (CG-A06, doc 29 section 7.11, docs/build/S146-parts/B-document-extraction.md), run inside the
 * AI task pipeline for extract skills that declare a {@code document}:
 * <ol>
 * <li>the mapped request file is read under the S25/S52 rules (quarantined, scan-blocked and removed files refused);</li>
 * <li>the workspace's OCR provider reads it (residency checked; a stored layout of the same bytes from the same provider
 * is reused, so a retried task repeats no OCR call); the text and layout are stored only as the file's S52 OCR result;</li>
 * <li>the recognized lines are sent to the model as numbered data lines under the attachment input's minimization
 * action (hash and drop refuse the document);</li>
 * <li>per field: the value, a calibrated confidence from checkable signals (the model's figure, agreement across
 * samples, the OCR line confidence, whether the value is found in the line, agreement with the OCR service's own
 * key-value pair, whose confidence is used when they agree) and the source location (page, line, box and character span
 * in the OCR text); validation hints and cross-field checks lower the confidence of the fields they flag.</li>
 * </ol>
 */
@Component
class DocumentStage {

    private static final Logger log = LoggerFactory.getLogger(DocumentStage.class);
    /** Confidence ceiling of a field a validation hint or check flags. */
    static final double FLAGGED_CEILING = 0.3;

    /** What the OCR stage produced, or why the document cannot be used. */
    record Prepared(UUID attachmentId, String sha256, String providerId, OcrDocument document, String rendered,
            boolean reused, String refusal) {

        static Prepared refused(String reason) {
            return new Prepared(null, null, null, null, null, false, reason);
        }
    }

    /** One extracted field after post-processing. */
    record FieldResult(DocumentTemplates.Field field, JsonNode value, Double raw, Double confidence, ObjectNode source,
            List<DocumentTemplates.Problem> problems) {
    }

    private final RunDocuments documents;
    private final OcrProviders ocr;
    private final Json json;
    private final Clock clock;

    DocumentStage(RunDocuments documents, OcrProviders ocr, Json json, Clock clock) {
        this.documents = documents;
        this.ocr = ocr;
        this.json = json;
        this.clock = clock;
    }

    static boolean applies(AiSkills.Version version) {
        return version != null && "extract".equals(version.mode())
                && DocumentTemplates.attachmentInput(version.document()) != null;
    }

    /** Stages 1-3 of document extraction. Never throws for file, policy or provider problems. */
    Prepared prepare(AiTaskPort.TaskRequest request, AiSkills.Version version, Map<String, String> actions,
            Instant deadline) {
        JsonNode skill = version.document();
        String input = DocumentTemplates.attachmentInput(skill);
        String value = request.input().path(input).asString(null);
        if (value == null || value.isBlank()) {
            return Prepared.refused("No file was attached for the document input " + input + ".");
        }
        String action = actions.getOrDefault(input, "send");
        if (Minimizer.maskText("", action) == null) {
            return Prepared.refused("The workspace AI policy does not allow sending this document's text to a model"
                    + " (classification action " + action + ").");
        }
        RunDocuments.Document file;
        try {
            file = documents.read(request.workspaceId(), request.runId(), value);
        } catch (RunDocuments.Refused e) {
            return Prepared.refused(e.getMessage());
        }
        OcrProviders.Selection selection = ocr.select(request.workspaceId(), file.contentType());
        if (selection.refusal() != null) {
            return Prepared.refused(selection.refusal());
        }
        OcrDocument document = null;
        boolean reused = false;
        try {
            var stored = documents.storedLayout(request.workspaceId(), file.id(), file.sha256(), selection.id());
            if (stored.isPresent()) {
                document = OcrProviders.fromJson(stored.get());
                reused = true;
            }
        } catch (RuntimeException e) {
            log.warn("Stored OCR layout not readable: {}", e.getClass().getSimpleName());
        }
        if (document == null) {
            Duration left = Duration.between(clock.instant(), deadline);
            try {
                document = selection.provider().analyze(file.content(), file.contentType(),
                        DocumentTemplates.documentType(skill), left);
            } catch (OcrException e) {
                return Prepared.refused(e.reason());
            } catch (RuntimeException e) {
                log.warn("OCR failed: {}", e.getClass().getSimpleName());
                return Prepared.refused("The OCR provider could not read the file.");
            }
            String text = document.text();
            if (text.length() > com.orqix.integration.OcrProvider.MAX_TEXT_CHARS) {
                return Prepared.refused("The document has more text than document extraction reads (100,000"
                        + " characters).");
            }
            try {
                documents.storeOcr(request.workspaceId(), file.id(), selection.id(), text, ocr.toJson(document),
                        file.sha256());
            } catch (RuntimeException e) {
                log.warn("OCR result not stored: {}", e.getClass().getSimpleName());
            }
        }
        if (document.text().isBlank()) {
            return Prepared.refused("The OCR provider found no text in the file.");
        }
        return new Prepared(file.id(), file.sha256(), selection.id(), document, render(document, action), reused, null);
    }

    /** The lines as the model sees them: {@code [L<n>] text}, masked under the input's minimization action. */
    static String render(OcrDocument document, String action) {
        StringBuilder s = new StringBuilder();
        int n = 1;
        int page = -1;
        for (OcrDocument.LineRef ref : document.lines()) {
            if (ref.page() != page) {
                page = ref.page();
                s.append("--- page ").append(page).append(" ---\n");
            }
            s.append("[L").append(n++).append("] ").append(Minimizer.maskText(ref.line().text(), action)).append('\n');
        }
        return s.toString();
    }

    /** The data sent for the document input. */
    ObjectNode sent(AiSkills.Version version, Prepared prepared) {
        ObjectNode o = json.object();
        o.put("documentType", DocumentTemplates.documentType(version.document()));
        o.put("pages", prepared.document().pages().size());
        o.put("lines", prepared.rendered());
        return o;
    }

    /** Stage 7 for documents: per-field values, confidences, sources and validation. */
    List<FieldResult> fields(AiSkills.Version version, OcrDocument document, SkillAnswers.Answer chosen,
            List<SkillAnswers.Answer> answers, int samples, int repairs, DoubleUnaryOperator calibrate) {
        JsonNode skill = version.document();
        List<DocumentTemplates.Field> fields = DocumentTemplates.effectiveFields(skill);
        List<OcrDocument.LineRef> lines = document.lines();
        Map<String, JsonNode> finalValues = new LinkedHashMap<>();
        Map<String, Double> raws = new LinkedHashMap<>();
        Map<String, ObjectNode> sources = new LinkedHashMap<>();
        List<DocumentTemplates.Problem> problems = new ArrayList<>();
        for (DocumentTemplates.Field f : fields) {
            String name = f.name();
            JsonNode value = chosen.fields() == null ? null : chosen.fields().get(name);
            double raw = chosen.fieldConfidence() == null ? 0.5
                    : chosen.fieldConfidence().path(name).asDouble(chosen.reported() == null ? 0.5 : chosen.reported());
            if (samples > 1 && value != null && answers.size() > 0) {
                JsonNode chosenValue = value;
                long agree = answers.stream().filter(a -> a.fields() != null && same(a.fields().get(name), chosenValue))
                        .count();
                raw = Math.min(raw, agree / (double) answers.size());
            }
            OcrDocument.LineRef line = null;
            int[] span = null;
            if (value != null) {
                int claimed = chosen.fieldSources() == null ? -1 : chosen.fieldSources().path(name).asInt(-1);
                if (claimed >= 1 && claimed <= lines.size()) {
                    span = locate(lines.get(claimed - 1), value);
                    line = span == null ? null : lines.get(claimed - 1);
                }
                if (line == null) {
                    for (OcrDocument.LineRef ref : lines) {
                        span = locate(ref, value);
                        if (span != null) {
                            line = ref;
                            break;
                        }
                    }
                }
                if (line == null) {
                    // The value cannot be found in the recognized text: weaker evidence.
                    raw *= 0.8;
                    if (claimed >= 1 && claimed <= lines.size()) {
                        line = lines.get(claimed - 1);
                    }
                } else if (line.line().confidence() != null) {
                    raw = Math.min(raw, line.line().confidence());
                }
            }
            ObjectNode source = json.object();
            OcrDocument.KeyValue kv = keyValue(document, name);
            boolean fromKeyValue = false;
            if (kv != null) {
                JsonNode typed = typed(kv.value(), f.type());
                if (value == null && typed != null) {
                    value = typed;
                    raw = kv.confidence() == null ? 0.6 : kv.confidence();
                    fromKeyValue = true;
                } else if (value != null && typed != null && same(value, typed)) {
                    if (kv.confidence() != null) {
                        raw = Math.max(raw, kv.confidence());
                    }
                    fromKeyValue = true;
                } else if (value != null) {
                    raw = Math.min(raw, 0.5);
                    problems.add(new DocumentTemplates.Problem(name, "DIFFERS_FROM_OCR", "The OCR service read a"
                            + " different value for this field."));
                }
            }
            if (fromKeyValue) {
                source.put("kind", "ocrKeyValue").put("page", kv.page() == null ? 1 : kv.page());
                putBox(source, kv.box() != null ? kv.box() : line == null ? null : line.line().box());
                if (line != null && span != null) {
                    source.put("line", lines.indexOf(line) + 1);
                    source.putObject("span").put("start", span[0]).put("end", span[1]);
                }
            } else if (line != null) {
                source.put("kind", "ocrLine").put("page", line.page()).put("line", lines.indexOf(line) + 1);
                putBox(source, line.line().box());
                if (span != null) {
                    source.putObject("span").put("start", span[0]).put("end", span[1]);
                }
            } else {
                source.put("kind", "none");
            }
            if (repairs > 0) {
                raw *= 0.9;
            }
            raws.put(name, Math.max(0, Math.min(1, raw)));
            sources.put(name, source);
            if (value != null) {
                finalValues.put(name, value);
            }
        }
        problems.addAll(DocumentTemplates.validate(fields, DocumentTemplates.checks(skill), finalValues,
                document.lineItemAmounts(), LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)));
        List<FieldResult> results = new ArrayList<>();
        for (DocumentTemplates.Field f : fields) {
            JsonNode value = finalValues.get(f.name());
            List<DocumentTemplates.Problem> own = problems.stream().filter(p -> p.field().equals(f.name())).toList();
            Double raw = value == null ? (f.required() ? 0.0 : null) : raws.get(f.name());
            Double confidence = raw == null ? null : value == null ? 0.0 : calibrate.applyAsDouble(raw);
            if (confidence != null && !own.isEmpty()) {
                confidence = Math.min(confidence, FLAGGED_CEILING);
            }
            List<DocumentTemplates.Problem> all = new ArrayList<>(own);
            if (value == null && f.required()) {
                all.add(new DocumentTemplates.Problem(f.name(), "MISSING", "This required field was not found."));
            }
            results.add(new FieldResult(f, value, raw, confidence == null ? null : AiTaskEngine.round(confidence),
                    sources.get(f.name()), all));
        }
        return results;
    }

    /** The lowest confidence of the fields that have one (1 when none has). */
    static double overall(List<FieldResult> results) {
        double lowest = 1;
        for (FieldResult r : results) {
            if (r.confidence() != null) {
                lowest = Math.min(lowest, r.confidence());
            }
        }
        return lowest;
    }

    /**
     * The verification payload: the document reference, its pages and every field with value, confidence, whether it
     * needs attention, problems and source location. No recognized text: the verification screen reads the snippets
     * from the S52 OCR result while it exists.
     */
    ObjectNode verification(AiSkills.Version version, Prepared prepared, List<FieldResult> results,
            BigDecimal threshold) {
        ObjectNode o = json.object();
        o.put("attachmentId", prepared.attachmentId().toString());
        o.put("documentType", DocumentTemplates.documentType(version.document()));
        o.put("provider", prepared.providerId());
        o.put("threshold", threshold);
        ArrayNode pages = o.putArray("pages");
        for (OcrDocument.Page p : prepared.document().pages()) {
            pages.addObject().put("number", p.number()).put("width", p.width()).put("height", p.height())
                    .put("unit", p.unit());
        }
        o.set("fields", fieldsJson(results, threshold.doubleValue()));
        return o;
    }

    ArrayNode fieldsJson(List<FieldResult> results, double threshold) {
        ArrayNode fields = json.mapper().createArrayNode();
        for (FieldResult r : results) {
            ObjectNode f = fields.addObject();
            f.put("name", r.field().name()).put("type", r.field().type()).put("required", r.field().required());
            if (r.field().description() == null) {
                f.putNull("description");
            } else {
                f.put("description", r.field().description());
            }
            if (r.field().hint() == null) {
                f.putNull("hint");
            } else {
                f.put("hint", r.field().hint());
            }
            f.set("value", r.value() == null ? JsonNodeFactory.instance.nullNode() : r.value());
            if (r.confidence() == null) {
                f.putNull("confidence");
            } else {
                f.put("confidence", r.confidence());
            }
            f.put("needsAttention", !r.problems().isEmpty() || r.confidence() != null && r.confidence() < threshold);
            ArrayNode problems = f.putArray("problems");
            r.problems().forEach(p -> problems.addObject().put("code", p.code()).put("message", p.message()));
            f.set("source", r.source());
        }
        return fields;
    }

    private static void putBox(ObjectNode o, OcrDocument.Box box) {
        if (box == null) {
            o.putNull("box");
        } else {
            o.putObject("box").put("x", box.x()).put("y", box.y()).put("w", box.w()).put("h", box.h());
        }
    }

    private static OcrDocument.KeyValue keyValue(OcrDocument document, String field) {
        OcrDocument.KeyValue best = null;
        for (OcrDocument.KeyValue kv : document.keyValues()) {
            if (field.equals(kv.field()) && (best == null || nz(kv.confidence()) > nz(best.confidence()))) {
                best = kv;
            }
        }
        return best;
    }

    private static double nz(Double d) {
        return d == null ? 0 : d;
    }

    /** Character span of the value in the document text, searched in one line (null when not found). */
    static int[] locate(OcrDocument.LineRef ref, JsonNode value) {
        String text = ref.line().text();
        for (String candidate : renderings(value)) {
            if (candidate.isEmpty()) {
                continue;
            }
            int at = text.toLowerCase(Locale.ROOT).indexOf(candidate.toLowerCase(Locale.ROOT));
            if (at >= 0) {
                return new int[] {ref.start() + at, ref.start() + at + candidate.length()};
            }
        }
        return null;
    }

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("d.M.yyyy"), DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"), DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH), DateTimeFormatter.ofPattern("MMMM d, yyyy",
                    Locale.ENGLISH));

    /** Ways a value may be printed: as written, numbers with thousands separators and two decimals, dates. */
    static List<String> renderings(JsonNode value) {
        List<String> out = new ArrayList<>();
        if (value.isNumber()) {
            BigDecimal n = value.decimalValue();
            out.add(String.format(Locale.ROOT, "%,.2f", n));
            out.add(String.format(Locale.GERMANY, "%,.2f", n));
            out.add(String.format(Locale.ROOT, "%.2f", n));
            out.add(String.format(Locale.GERMANY, "%.2f", n));
            out.add(n.stripTrailingZeros().toPlainString());
        } else if (value.isString()) {
            String s = value.asString().strip();
            out.add(s);
            if (s.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                try {
                    LocalDate d = LocalDate.parse(s);
                    DATE_FORMATS.forEach(f -> out.add(f.format(d)));
                } catch (DateTimeParseException ignored) {
                    // the type check already refused it
                }
            }
            if (s.replace(" ", "").length() != s.length()) {
                out.add(s.replace(" ", ""));
            }
        } else {
            out.add(value.asString(value.toString()));
        }
        return out;
    }

    /** A value the OCR service printed, converted to the field's type (null when it does not convert). */
    static JsonNode typed(String text, String type) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.strip();
        JsonNodeFactory f = JsonNodeFactory.instance;
        switch (type) {
            case "number", "integer" -> {
                String digits = t.replaceAll("[^0-9.,-]", "");
                if (digits.isEmpty()) {
                    return null;
                }
                if (digits.contains(",") && digits.lastIndexOf(',') > digits.lastIndexOf('.')) {
                    digits = digits.replace(".", "").replace(',', '.');
                } else {
                    digits = digits.replace(",", "");
                }
                try {
                    BigDecimal n = new BigDecimal(digits);
                    if ("integer".equals(type)) {
                        return n.stripTrailingZeros().scale() <= 0 ? f.numberNode(n.longValueExact()) : null;
                    }
                    return f.numberNode(n);
                } catch (NumberFormatException | ArithmeticException e) {
                    return null;
                }
            }
            case "date" -> {
                if (t.matches("^\\d{4}-\\d{2}-\\d{2}.*")) {
                    try {
                        return f.stringNode(LocalDate.parse(t.substring(0, 10)).toString());
                    } catch (DateTimeParseException e) {
                        return null;
                    }
                }
                for (DateTimeFormatter format : DATE_FORMATS) {
                    try {
                        return f.stringNode(LocalDate.parse(t, format).toString());
                    } catch (DateTimeParseException ignored) {
                        // next format
                    }
                }
                return null;
            }
            case "boolean" -> {
                return null;
            }
            default -> {
                return f.stringNode(t);
            }
        }
    }

    /** Values compared as the evaluation compares them: numbers by value, text ignoring case and spaces. */
    static boolean same(JsonNode a, JsonNode b) {
        if (a == null || b == null || a.isNull() || b.isNull()) {
            return a == b || (a != null && b != null && a.isNull() && b.isNull());
        }
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        return a.asString(a.toString()).replace(" ", "").equalsIgnoreCase(b.asString(b.toString()).replace(" ", ""));
    }
}
