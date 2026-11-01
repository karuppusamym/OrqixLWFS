package com.orqix.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.OrqixApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * Module rules (S1 section 3, spike S-9): Spring Modulith {@code verify()} with the allowed dependencies declared in
 * each module's package-info, plus ArchUnit rules for engine access, controller placement and secret reading.
 */
class ArchitectureTest {

    // Exclude test output regardless of the enclosing Maven build directory (including isolated verification builds).
    static final ImportOption PRODUCTION_CLASSES = location ->
            !location.asURI().toString().replace('\\', '/').contains("/test-classes/")
            && ImportOption.Predefined.DO_NOT_INCLUDE_TESTS.includes(location);

    static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(PRODUCTION_CLASSES)
            .importPackages("com.orqix");

    @Test
    void modulithStructureIsValid() {
        ApplicationModules.of(OrqixApplication.class, PRODUCTION_CLASSES).verify();
    }

    @Test
    void engineAccessStaysInRuntimeAndTheCmmnAdapter() {
        // The case module is a separate CMMN execution boundary; only its service is an engine adapter.
        noClasses().that().resideOutsideOfPackage("com.orqix.runtime..")
                .and().doNotHaveFullyQualifiedName("com.orqix.cases.CaseService")
                .should().dependOnClassesThat().resideInAPackage("org.flowable..")
                .check(CLASSES);
    }

    @Test
    void cmmnAdapterUsesOnlyPublicCaseTaskAndIdentityApis() {
        noClasses().that().resideInAPackage("com.orqix.cases..")
                .should().dependOnClassesThat(resideInAnyPackage("org.flowable..")
                        // org.flowable.common.engine.api holds FlowableException, the public base exception of
                        // those APIs, which CaseService catches when a CMMN deployment is rejected.
                        .and(not(resideInAnyPackage("org.flowable.cmmn.api..", "org.flowable.task.api..",
                                "org.flowable.identitylink.api..", "org.flowable.common.engine.api.."))))
                .check(CLASSES);
    }

    @Test
    void controllersLiveInApiPackagesAndDoNotUseJdbc() {
        classes().that().areAnnotatedWith(RestController.class).and().resideOutsideOfPackage("com.orqix.shared..")
                .should().resideInAPackage("..api..")
                .check(CLASSES);
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
                .check(CLASSES);
    }

    @Test
    void onlyIntegrationReadsSecrets() {
        noClasses().that().resideOutsideOfPackage("com.orqix.integration..")
                .should().dependOnClassesThat().haveSimpleName("SecretStore")
                .check(CLASSES);
    }

    @Test
    void compilerHasNoSpringFlowableOrJdbc() {
        noClasses().that().resideInAPackage("com.orqix.compiler..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "org.flowable..", "java.sql..",
                        "java.net.http..")
                .check(CLASSES);
    }
}
