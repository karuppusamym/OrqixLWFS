package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CG-A06 (docs/build/S146-parts/B-document-extraction.md): document-type templates, the {@code document} member of the
 * AI skill contract, template fields in the compile facts, validation hints and cross-field checks, and an AI task
 * whose document input maps a request file.
 */
class DocumentTemplatesTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);

    private static ObjectNode invoiceSkill() {
        return (ObjectNode) MAPPER.readTree("""
                {"schemaVersion":"1.0","kind":"skill","key":"invoice-reader","name":"Invoice reader","mode":"extract",
                 "instructions":"Read the supplier invoice and extract its fields.",
                 "input":{"invoice":{"type":"attachment","required":true,"classification":"customer-text"},
                          "supplierHint":{"type":"string"}},
                 "document":{"type":"invoice","attachmentInput":"invoice"},
                 "modelProfile":"economy"}
                """);
    }

    private static Map<String, JsonNode> values(String json) {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        MAPPER.readTree(json).properties().forEach(e -> values.put(e.getKey(), e.getValue()));
        return values;
    }

    @Test
    void aDocumentSkillTakesTheTemplateFieldsAndTheContractChecksTheDocumentInput() {
        ObjectNode skill = invoiceSkill();
        assertThat(AiSkillContracts.checkSkill(skill)).isEmpty();
        CompileContext.AiSkillVersion facts = AiSkillContracts.facts(1, "approved", skill, "h", "run", "suggest");
        assertThat(facts.fields()).containsEntry("invoiceNumber", "string").containsEntry("totalAmount", "number")
                .containsEntry("invoiceDate", "date").hasSize(12);
        assertThat(facts.requiredFields()).containsExactlyInAnyOrder("invoiceNumber", "invoiceDate", "supplierName",
                "totalAmount");
        assertThat(facts.inputs()).containsEntry("invoice", "attachment");

        // Own fields win; a field named like a template field keeps the template's hint.
        ObjectNode own = invoiceSkill();
        own.putArray("fields").add(MAPPER.readTree("{\"name\":\"iban\",\"type\":\"string\",\"required\":true}"));
        assertThat(DocumentTemplates.effectiveFields(own)).containsExactly(new DocumentTemplates.Field("iban", "string",
                true, "The IBAN to pay to", "iban"));
        assertThat(DocumentTemplates.checks(own)).isEmpty();
        assertThat(DocumentTemplates.checks(skill)).extracting(DocumentTemplates.Check::kind)
                .containsExactly("sum", "lineItems", "after");

        // An attachment input that is not the document input, a document input of another type, a document on a
        // classify skill, and a custom document without fields are refused.
        ObjectNode stray = invoiceSkill();
        ((ObjectNode) stray.path("input")).set("other", MAPPER.readTree("{\"type\":\"attachment\"}"));
        assertThat(AiSkillContracts.checkSkill(stray)).extracting(AiSkillContracts.Problem::pointer)
                .containsExactly("/input/other/type");
        ObjectNode wrongType = invoiceSkill();
        ((ObjectNode) wrongType.path("document")).put("attachmentInput", "supplierHint");
        assertThat(AiSkillContracts.checkSkill(wrongType)).extracting(AiSkillContracts.Problem::pointer)
                .contains("/document/attachmentInput", "/input/invoice/type");
        ObjectNode custom = invoiceSkill();
        ((ObjectNode) custom.path("document")).put("type", "custom");
        assertThat(AiSkillContracts.checkSkill(custom)).isNotEmpty();
        custom.putArray("fields").add(MAPPER.readTree("{\"name\":\"policyNumber\",\"type\":\"string\",\"hint\":\"iban\"}"));
        assertThat(AiSkillContracts.checkSkill(custom)).isEmpty();
        ObjectNode classify = (ObjectNode) MAPPER.readTree("""
                {"schemaVersion":"1.0","kind":"skill","key":"triage","name":"Triage","mode":"classify",
                 "instructions":"Classify the request please.","input":{"text":{"type":"string"}},
                 "labels":[{"key":"a","description":"A"},{"key":"b","description":"B"}],
                 "document":{"type":"invoice","attachmentInput":"text"},"modelProfile":"economy"}
                """);
        assertThat(AiSkillContracts.checkSkill(classify)).extracting(AiSkillContracts.Problem::pointer)
                .contains("/document");
    }

    @Test
    void hintsAndCrossFieldChecksFlagTheFieldsTheyConcern() {
        ObjectNode skill = invoiceSkill();
        List<DocumentTemplates.Field> fields = DocumentTemplates.effectiveFields(skill);
        List<DocumentTemplates.Check> checks = DocumentTemplates.checks(skill);
        Map<String, JsonNode> good = values("""
                {"invoiceNumber":"INV-1","invoiceDate":"2026-10-01","dueDate":"2026-10-31","supplierName":"ACME",
                 "currency":"EUR","subtotalAmount":100,"taxAmount":19,"totalAmount":119.00,
                 "iban":"DE89 3704 0044 0532 0130 00"}
                """);
        assertThat(DocumentTemplates.validate(fields, checks, good, List.of(new BigDecimal("60"), new BigDecimal("40")),
                TODAY)).isEmpty();
        Map<String, JsonNode> bad = values("""
                {"invoiceNumber":"INV-1","invoiceDate":"2027-01-01","dueDate":"2026-10-31","supplierName":"ACME",
                 "currency":"EURO","subtotalAmount":100,"taxAmount":19,"totalAmount":120,
                 "iban":"DE88 3704 0044 0532 0130 00"}
                """);
        List<DocumentTemplates.Problem> problems = DocumentTemplates.validate(fields, checks, bad,
                List.of(new BigDecimal("60")), TODAY);
        assertThat(problems).extracting(p -> p.field() + ":" + p.code()).containsExactlyInAnyOrder(
                "invoiceDate:DATE_IN_FUTURE", "currency:CURRENCY_UNKNOWN", "iban:IBAN_CHECKSUM",
                "totalAmount:SUM_MISMATCH", "subtotalAmount:LINE_ITEMS_MISMATCH", "dueDate:DATE_ORDER");
        // A check is skipped while one of its values is missing.
        assertThat(DocumentTemplates.validate(fields, checks, values("{\"totalAmount\":5,\"taxAmount\":1}"), List.of(),
                TODAY)).isEmpty();

        assertThat(DocumentTemplates.ibanValid("GB82WEST12345698765432")).isTrue();
        assertThat(DocumentTemplates.ibanValid("GB82WEST1234569876543X")).isFalse();
        List<DocumentTemplates.Field> statement = DocumentTemplates.builtIn("bankStatement").orElseThrow().fields();
        List<DocumentTemplates.Check> statementChecks = DocumentTemplates.builtIn("bankStatement").orElseThrow().checks();
        assertThat(DocumentTemplates.validate(statement, statementChecks, values("""
                {"openingBalance":1000,"totalCredits":250.5,"totalDebits":200,"closingBalance":1050.40,
                 "statementStartDate":"2026-10-01","statementEndDate":"2026-09-30"}
                """), List.of(), TODAY)).extracting(DocumentTemplates.Problem::code)
                .containsExactlyInAnyOrder("SUM_MISMATCH", "DATE_ORDER");
        List<DocumentTemplates.Field> id = DocumentTemplates.builtIn("idDocument").orElseThrow().fields();
        assertThat(DocumentTemplates.validate(id, List.of(), values("""
                {"expiryDate":"2026-01-01","issuingCountry":"DEU","nationality":"XX","dateOfBirth":"1990-02-30"}
                """), List.of(), TODAY)).extracting(p -> p.field() + ":" + p.code()).containsExactlyInAnyOrder(
                "expiryDate:DATE_IN_PAST", "nationality:COUNTRY_UNKNOWN", "dateOfBirth:DATE_FORMAT");
    }

    @Test
    void anAiTaskMapsARequestFileToTheDocumentInput() {
        ObjectNode d = TestSupport.purchase();
        ((ObjectNode) d.path("inputSchema").path("properties")).set("invoiceFile",
                MAPPER.readTree("{\"type\":\"string\",\"title\":\"Invoice\",\"format\":\"attachment\"}"));
        ((ArrayNode) d.path("nodes")).add(MAPPER.readTree("""
                {"id":"read","type":"aiTask","label":"Read the invoice","config":{"skillRef":"invoice-reader",
                 "mode":"extract","autonomy":"suggest","minConfidence":0.8,
                 "inputMapping":{"invoice":"/input/invoiceFile"}}}
                """));
        ((ObjectNode) d.path("uiLayout").path("positions")).set("read", MAPPER.readTree("{\"x\":50,\"y\":50}"));
        for (JsonNode t : d.path("transitions")) {
            if ("t1".equals(t.path("id").asString())) {
                ((ObjectNode) t).put("to", "read");
            }
        }
        ArrayNode transitions = (ArrayNode) d.path("transitions");
        transitions.add(MAPPER.readTree("{\"id\":\"r1\",\"from\":\"read\",\"to\":\"manager\",\"on\":\"next\"}"));
        transitions.add(MAPPER.readTree("{\"id\":\"r2\",\"from\":\"read\",\"to\":\"manager\",\"on\":\"needsReview\"}"));
        transitions.add(MAPPER.readTree("{\"id\":\"r3\",\"from\":\"read\",\"to\":\"manager\",\"on\":\"aiUnavailable\"}"));
        CompileContext.AiSkillVersion facts = AiSkillContracts.facts(1, "approved", invoiceSkill(), "hash-1", "run-1",
                "suggest");
        CompileContext context = SimulatorFixtures.context().workspace().withAiSkills(Map.of("invoice-reader",
                new CompileContext.AiSkillInfo("invoice-reader", "Invoice reader", Map.of(1, facts))));
        List<ValidationIssue> issues = Compilers.workflowCompiler().validate(d.toString(), context).issues().stream()
                .filter(i -> i.severity() == Severity.BLOCKER).toList();
        assertThat(issues).isEmpty();

        // A plain text field cannot stand in for the file.
        ((ObjectNode) d.path("nodes").get(d.path("nodes").size() - 1).path("config").path("inputMapping"))
                .put("invoice", "/input/purpose");
        assertThat(Compilers.workflowCompiler().validate(d.toString(), context).issues().stream()
                .filter(i -> i.severity() == Severity.BLOCKER).map(ValidationIssue::code).toList())
                .contains("WF_AI_INPUT_TYPE");
        assertThat(Set.copyOf(DocumentTemplates.TYPES)).contains("invoice", "idDocument", "bankStatement", "custom");
    }
}
