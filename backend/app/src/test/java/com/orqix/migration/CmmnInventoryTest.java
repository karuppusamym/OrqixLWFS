package com.orqix.migration;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.orqix.migration.camunda.SourceException;
import com.orqix.migration.cmmn.CmmnInventory;

class CmmnInventoryTest {
    private String fixture() throws Exception { return Files.readString(Path.of("src/test/resources/cases/cmmn-compatibility.cmmn")); }

    @Test void inventoriesEveryElementAndNeverClaimsConversion() throws Exception {
        var report = CmmnInventory.analyze(fixture());
        assertThat(report.sourceSystem()).isEqualTo("cmmn");
        assertThat(report.processId()).isEqualTo("compatibilityCase");
        assertThat(report.publishable()).isFalse();
        assertThat(report.issues()).extracting(i -> i.sourceElementId()).contains("collect", "review", "collected", "reviewedMilestone");
        assertThat(report.issues()).extracting(i -> i.reasonCode()).contains("CMMN_RUNTIME_NOT_EXPOSED", "CMMN_EXTENSION_ATTRIBUTE_REVIEW_REQUIRED");
        assertThat(report.issues().toString()).doesNotContain("case-workers");
        assertThat(report.issues().stream().filter(i -> i.sourceElementId().equals("review")).map(i -> i.sourcePath()))
                .anyMatch(path -> path.contains("case[compatibilityCase]/casePlanModel[plan]/planItem[review]"));
        assertThat(report.issues()).allMatch(i -> i.resolutionStatus().equals("open"));
    }

    @Test void flagsBrokenReferencesDuplicatesAndUnknownElements() throws Exception {
        String changed = fixture().replace("sentryRef=\"collected\"", "sentryRef=\"absent\"")
                .replace("id=\"reviewedMilestone\"", "id=\"collect\"")
                .replace("</casePlanModel>", "<unexpected id=\"unmapped\"/></casePlanModel>");
        var report = CmmnInventory.analyze(changed);
        assertThat(report.issues()).extracting(i -> i.reasonCode()).contains("CMMN_REFERENCE_MISSING", "CMMN_DUPLICATE_ID", "CMMN_ELEMENT_UNRECOGNIZED");
    }

    @Test void rejectsWrongNamespaceAndExternalEntities() throws Exception {
        assertThatThrownBy(() -> CmmnInventory.analyze("<definitions xmlns=\"wrong\"><case id=\"x\"/></definitions>"))
                .isInstanceOf(SourceException.class);
        assertThatThrownBy(() -> CmmnInventory.analyze("<!DOCTYPE x [<!ENTITY steal SYSTEM 'file:///etc/passwd'>]>" + fixture()))
                .isInstanceOf(SourceException.class);
    }
}
