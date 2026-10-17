package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Studio-generated exact choice questions use the existing compiler and runtime form validation contract. */
class DocumentAuthoringTest {
    private CompileContext context() {
        String connection = TestSupport.read("examples/connections/microsoft-documents-v3.json");
        return new CompileContext(false,
                Map.of("managers", new CompileContext.GroupInfo("managers", "Managers", 1)),
                List.of(new CompileContext.ConnectionVersion("microsoft-documents", 3, connection,
                        CanonicalJson.sha256Hex(connection))), Set.of("microsoft-documents"));
    }

    @Test
    void studioExactReferenceFixtureCompilesWithPinnedConnectorAndSafeProvenance() throws Exception {
        String workflow = TestSupport.read("examples/document-authoring-review.json");
        var compiler = Compilers.workflowCompiler();
        var validation = compiler.validate(workflow, context());
        assertThat(validation.publishable()).as(validation.issues().toString()).isTrue();
        var compiled = compiler.compile(workflow, context());
        // The authored fixture's system step ID is "document" and the connector stays pinned to version 3.
        assertThat(compiled.bpmnXml()).contains("id=\"document\"", "fieldRef=\"/input/documentVersionId\"");
        assertThat(compiled.connections()).singleElement().satisfies(connection -> {
            assertThat(connection.key()).isEqualTo("microsoft-documents");
            assertThat(connection.definitionVersion()).isEqualTo(3);
        });
    }

    @Test
    void requesterMustSubmitTheApprovedExactDriveItemAndHistoricalVersion() {
        String schema = TestSupport.json("examples/document-authoring-review.json").path("inputSchema").toString();
        var validator = Compilers.formValidator();
        assertThat(validator.validate(schema,
                "{\"documentDriveId\":\"drive\",\"documentItemId\":\"item\",\"documentVersionId\":\"1.0\"}")).isEmpty();
        assertThat(validator.validate(schema,
                "{\"documentDriveId\":\"drive\",\"documentItemId\":\"item\",\"documentVersionId\":\"2.0\"}"))
                .extracting(FormValidator.FieldError::pointer).contains("/documentVersionId");
        assertThat(validator.validate(schema,
                "{\"documentDriveId\":\"other\",\"documentItemId\":\"item\",\"documentVersionId\":\"1.0\"}"))
                .extracting(FormValidator.FieldError::pointer).contains("/documentDriveId");
        assertThat(validator.validate(schema, "{}"))
                .extracting(FormValidator.FieldError::code).contains("required");
    }
}
