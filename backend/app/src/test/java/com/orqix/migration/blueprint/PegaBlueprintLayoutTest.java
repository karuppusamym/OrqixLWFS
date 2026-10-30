package com.orqix.migration.blueprint;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orqix.integration.DocumentExtraction;
import com.orqix.migration.camunda.Report;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Pega Blueprint export layout against the genuine Retail Loan Origination export (docs/build/S91): real worker
 * extraction with geometry, layout choice, stage columns across pages, icon kinds, field tables, personas, Live Data,
 * case type choice, findings and conversion of a reviewed subset.
 */
class PegaBlueprintLayoutTest {
    private static final Json JSON = new Json(JsonMapper.builder().build());
    private static List<BlueprintLayout.Page> pages;

    @BeforeAll
    static void extract() throws Exception {
        var extracted = new DocumentExtraction().extractPdfPages(BlueprintPdfs.pegaExport(), System.nanoTime() + TimeUnit.SECONDS.toNanos(60));
        assertThat(extracted.geometry()).hasSize(extracted.pages().size());
        pages = new ArrayList<>();
        for (int i = 0; i < extracted.pages().size(); i++)
            pages.add(new BlueprintLayout.Page(i + 1, extracted.pages().get(i), Json.sha256Hex(extracted.pages().get(i)), extracted.geometry().get(i)));
    }

    private static BlueprintFindings.DocumentFacts facts(BlueprintLayout.Result result) {
        return new BlueprintFindings.DocumentFacts(pages.size(), result.unclassified(), result.pageTexts().stream().map(String::isBlank).toList(), false, false,
            PegaBlueprintLayout.ID, PegaBlueprintLayout.REVISION, result.otherCaseTypes());
    }

    @Test
    void theExportIsRecognizedAndTheFirstCaseTypeTranscribesToTheCommittedExpectation() throws Exception {
        assertThat(BlueprintLayout.forPages(pages)).isInstanceOf(PegaBlueprintLayout.class);
        var result = BlueprintLayout.forPages(pages).transcribe(pages, null);
        ObjectNode actual = result.transcription().toJson(JSON);
        if (Boolean.getBoolean("blueprint.regenerate"))
            Files.writeString(BlueprintPdfs.PEGA_EXPORT_EXPECTED, JSON.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n");
        assertThat(actual).isEqualTo(JSON.parse(Files.readString(BlueprintPdfs.PEGA_EXPORT_EXPECTED)));

        assertThat(result.caseTypes()).containsExactly("Home Loan Application", "Unsecured Retail Loan Application", "Secured Retail Loan Application");
        assertThat(result.caseType()).isEqualTo("Home Loan Application");
        assertThat(result.otherCaseTypes()).containsExactly("Unsecured Retail Loan Application", "Secured Retail Loan Application");
        var t = result.transcription();
        assertThat(t.caseType().meta().sourcePage()).isEqualTo(2);
        assertThat(t.stages()).extracting(Transcription.Stage::name).containsExactly("Capture Borrower's Information", "Mortgage Application Submission",
            "Evidence Provision", "Verification of Data", "Credit Assessment", "Property Assessment and Loan Approval", "Loan Agreement", "Loan Disbursement");
        assertThat(t.steps()).hasSize(58);
        assertThat(t.decisions()).extracting(Transcription.Decision::id).containsExactly("decision-check-initial-eligibility-criteria",
            "decision-validate-debt-to-income-ratio", "decision-assess-loan-amount", "decision-check-property-value", "decision-assess-loan-to-value",
            "decision-accept-pricing-and-fees");
        // The first stage column, read from the icons: user task x3, automation, decision, approval, notification.
        assertThat(t.start()).isEqualTo("step-collect-applicant-information");
        assertThat(t.steps().subList(0, 6)).extracting(Transcription.Step::name, Transcription.Step::type, Transcription.Step::stage, Transcription.Step::next)
            .containsExactly(tuple("Collect Applicant Information", "task", "stage-capture-borrower-s-information", "step-existing-customer"),
                tuple("Existing Customer?", "task", "stage-capture-borrower-s-information", "step-existing-pre-qualification"),
                tuple("Existing pre-qualification?", "task", "stage-capture-borrower-s-information", "step-select-product-purpose"),
                tuple("Select product & purpose", "automated", "stage-capture-borrower-s-information", "decision-check-initial-eligibility-criteria"),
                tuple("In-principal Approval or Reject", "approval", "stage-capture-borrower-s-information", "step-notify-customer-on-outcome-of-pre-qualification-checks"),
                tuple("Notify Customer on Outcome of Pre-Qualification Checks", "automated", "stage-capture-borrower-s-information", "step-collect-applicant-information-2"));
        assertThat(t.decisions().getFirst().otherwise()).isEqualTo("step-in-principal-approval-or-reject");
        assertThat(t.decisions().getFirst().rules()).isEmpty();
        // Steps continued on page 3 stay in their stage column from page 2.
        assertThat(t.steps()).filteredOn(s -> s.meta().sourcePage() == 3).extracting(Transcription.Step::name, Transcription.Step::stage)
            .contains(tuple("Approve/Reject Loan", "stage-property-assessment-and-loan-approval"), tuple("Send Confirmation", "stage-loan-agreement"),
                tuple("Check Signed Contract", "stage-loan-agreement"));
        assertThat(t.steps()).filteredOn(s -> "approval".equals(s.type())).extracting(Transcription.Step::name)
            .containsExactly("In-principal Approval or Reject", "Conditional Approval/Reject", "Approve/Reject Loan", "Approval Loan Contract");
        assertThat(t.steps()).allSatisfy(s -> { assertThat(s.role()).isNull(); assertThat(s.onReject()).isNull(); assertThat(s.type()).isNotNull(); });
        assertThat(t.steps().getLast().next()).isNull();
        // The field table continues on page 4; date-times are not guessed.
        assertThat(t.fields()).hasSize(24);
        assertThat(t.fields()).extracting(Transcription.Field::id, Transcription.Field::type).contains(tuple("applicantAddress", "string"),
            tuple("urgency", "number"), tuple("createdDateTime", "string"), tuple("resolvedDateTime", null));
        assertThat(t.roles()).extracting(Transcription.Role::name).containsExactly("Mortgage Advisor", "Underwriter", "Customer Service Representative",
            "Credit Risk Analyst", "Operational Risk Analyst", "Applicant/Borrower");
        assertThat(t.integrations()).extracting(Transcription.Integration::name, Transcription.Integration::target).containsExactly(
            tuple("Loan Application", "backend loan management system or core banking applications"),
            tuple("Applicant", "Customer Relationship Management (CRM) system"), tuple("Loan Product", "Product Catalog"), tuple("Credit Score", null),
            tuple("Loan Approval", "Loan Approval System"), tuple("Loan Disbursement", "Disbursement System"), tuple("Loan Document", "Document Management System"));
        assertThat(t.outcomes()).isEmpty();
        assertThat(t.slas()).isEmpty();
        assertThat(t.elements()).allSatisfy(e -> assertThat(e.meta().disposition()).isEqualTo("proposed"));
        // Pages 1 (title, application context) and 10 (copyright notice) hold no elements; pages 5-8 are the other case types.
        assertThat(result.unclassified()).containsExactly(11, 0, 0, 0, 43, 9, 44, 14, 0, 2);
    }

    @Test
    void pagesAreRenderedStageByStageAndEveryExcerptIsFoundInItsPage() {
        var result = new PegaBlueprintLayout().transcribe(pages, null);
        assertThat(result.pageTexts()).hasSize(10);
        assertThat(result.pageTexts().get(1)).contains("Case Types\nHome Loan Application\n",
            "Capture Borrower's Information\n  Collect Applicant Information\n  Existing Customer?\n",
            "Credit Assessment\n  Get Credit Score\n  Analyze Income and Expenses\n  Review Credit History\n  Validate Debt-to-Income Ratio\n  Conditional Approval/Reject\n");
        assertThat(result.pageTexts().get(2)).startsWith("  Approve/Reject Loan\n").contains("Field Name\tDescription\tType\tPrimary\nApplicant Address\tApplicant Address\tText\n");
        assertThat(result.pageTexts().get(0)).contains("Organization name\tIndustry\tIndustry subsegment\tDepartment/function\tLanguage\nU+ Bank\tBanking\tRetail Banking\tLending\tEnglish\n");
        for (var e : result.transcription().elements())
            assertThat(result.pageTexts().get(e.meta().sourcePage() - 1)).as(e.id()).contains(e.meta().sourceExcerpt());
    }

    @Test
    void anotherCaseTypeCanBeChosenByNameAndAnUnknownNameTranscribesNothing() {
        var secured = new PegaBlueprintLayout().transcribe(pages, "secured retail loan  application");
        assertThat(secured.caseType()).isEqualTo("Secured Retail Loan Application");
        var t = secured.transcription();
        assertThat(t.stages()).extracting(Transcription.Stage::name).containsExactly("Capture Applicant Information", "Eligibility and Evaluation",
            "Additional Documentation and Collateral", "Loan Amount and Terms", "Loan Agreement and Disbursement");
        assertThat(t.steps().size() + t.decisions().size()).isEqualTo(30);
        assertThat(t.fields()).hasSize(19);
        assertThat(t.fields()).extracting(Transcription.Field::id, Transcription.Field::type).contains(tuple("loanAmount", "number"));
        assertThat(t.roles()).hasSize(6);
        assertThat(secured.otherCaseTypes()).containsExactly("Home Loan Application", "Unsecured Retail Loan Application");
        assertThat(secured.unclassified().subList(6, 8)).containsExactly(0, 0);
        assertThat(secured.unclassified().subList(1, 6)).allMatch(n -> n > 0);

        var unknown = new PegaBlueprintLayout().transcribe(pages, "Car Insurance Claim");
        assertThat(unknown.caseType()).isNull();
        assertThat(unknown.transcription().elements()).isEmpty();
        assertThat(unknown.caseTypes()).hasSize(3);
    }

    @Test
    void findingsNameTheOtherCaseTypesAndEverySemanticTheExportDoesNotState() {
        var result = new PegaBlueprintLayout().transcribe(pages, null);
        var report = BlueprintFindings.report(result.transcription(), facts(result), "0".repeat(64), false, false, Map.of());
        assertThat(report.publishable()).isFalse();
        assertThat(report.issues()).filteredOn(i -> i.reasonCode().equals("BLUEPRINT_CASE_TYPES_NOT_TRANSCRIBED")).singleElement().satisfies(i -> {
            assertThat(i.severity()).isEqualTo(Report.INFO);
            assertThat(i.explanation()).contains("3 case types", "'Home Loan Application'", "Unsecured Retail Loan Application, Secured Retail Loan Application");
        });
        assertThat(report.issues().stream().map(Report.Issue::reasonCode).toList()).contains("BLUEPRINT_OUTCOME_MISSING", "BLUEPRINT_STEP_ROLE_MISSING",
            "BLUEPRINT_STEP_UNSUPPORTED", "BLUEPRINT_DECISION_OUTCOMES_MISSING", "BLUEPRINT_REJECTION_ROUTE_MISSING", "BLUEPRINT_INTEGRATION_UNSUPPORTED",
            "BLUEPRINT_INTEGRATION_TARGET_MISSING", "BLUEPRINT_FIELD_TYPE_UNKNOWN", "BLUEPRINT_TEXT_UNCLASSIFIED", "BLUEPRINT_STAGE_INFORMATIONAL")
            .doesNotContain("BLUEPRINT_NO_STRUCTURE", "BLUEPRINT_CASE_TYPE_MISSING", "BLUEPRINT_STEP_KIND_UNKNOWN");
        assertThat(report.sourceVersion()).isEqualTo("Blueprint PDF (pega-blueprint 0.1, 10 pages)");
        assertThat(report.assumptions().getFirst()).startsWith("LAYOUT NOTICE: the transcription was read with the Pega Blueprint export layout");
    }

    @Test
    void aReviewedApprovalPathOfTheExportConverts() {
        var result = new PegaBlueprintLayout().transcribe(pages, null);
        ObjectNode node = result.transcription().toJson(JSON);
        List<String> approvals = List.of("step-in-principal-approval-or-reject", "step-conditional-approval-reject", "step-approve-reject-loan", "step-approval-loan-contract");
        List<String> roles = List.of("role-mortgage-advisor", "role-credit-risk-analyst", "role-underwriter", "role-underwriter");
        for (JsonNode step : node.path("steps")) {
            ObjectNode s = (ObjectNode) step;
            int at = approvals.indexOf(s.path("id").asString());
            if (at < 0) { exclude(s, "Not an approval; rebuilt in the studio after conversion."); continue; }
            s.put("role", roles.get(at)).put("next", at + 1 < approvals.size() ? approvals.get(at + 1) : "outcome-approved").put("onReject", "outcome-rejected");
        }
        for (String list : List.of("decisions", "integrations")) for (JsonNode e : node.path(list)) exclude((ObjectNode) e, "Rebuilt in the studio after conversion.");
        for (JsonNode f : node.path("fields")) if (!List.of("loanAmount", "applicantName").contains(f.path("id").asString())) exclude((ObjectNode) f, "Not a request input.");
        node.put("start", approvals.getFirst());
        var outcomes = node.putArray("outcomes");
        outcomes.addObject().put("id", "outcome-approved").put("name", "Approved").put("type", "success").put("disposition", "manuallyResolved");
        outcomes.addObject().put("id", "outcome-rejected").put("name", "Rejected").put("type", "rejected").put("disposition", "manuallyResolved");
        var reviewed = Transcription.parse(node).withDispositions(e -> e.meta().excluded() ? "excluded" : e.meta().sourcePage() == null ? "manuallyResolved" : "mapped");
        var report = BlueprintFindings.report(reviewed, facts(result), "0".repeat(64), true, true, Map.of());
        assertThat(report.issues().stream().filter(Report.Issue::blocksPublishing).toList()).isEmpty();
        assertThat(report.requiredMappings().groups()).containsExactly("role-mortgage-advisor", "role-credit-risk-analyst", "role-underwriter");
        var request = JSON.object().put("workflowKey", "home-loan").put("name", "Home loan").put("acknowledgeInfo", true).put("acknowledgeReview", true);
        request.putObject("groupMappings").put("role-mortgage-advisor", "advisors").put("role-credit-risk-analyst", "credit-risk").put("role-underwriter", "underwriters");
        var converted = BlueprintConverter.convert(reviewed, request, JSON);
        assertThat(converted.converterReport().publishable()).as(converted.converterReport().issues().toString()).isTrue();
        assertThat(converted.sourceMap()).containsKeys(approvals.toArray(String[]::new)).doesNotContainKey("step-collect-applicant-information");
        assertThat(converted.definition().path("inputSchema").path("properties").path("loanAmount").path("type").asString()).isEqualTo("string");
    }

    private static void exclude(ObjectNode element, String reason) { element.put("disposition", "excluded").put("dispositionReason", reason); }
}
