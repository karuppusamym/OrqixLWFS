package com.orqix.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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

    static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.orqix");

    @Test
    void modulithStructureIsValid() {
        ApplicationModules.of(OrqixApplication.class).verify();
    }

    @Test
    void onlyRuntimeImportsFlowable() {
        noClasses().that().resideOutsideOfPackage("com.orqix.runtime..")
                .should().dependOnClassesThat().resideInAPackage("org.flowable..")
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
