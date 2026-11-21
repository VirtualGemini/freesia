package com.freesia.system;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class ModuleStructureTest {
    private static final String ROOT = FreesiaSystemConfiguration.class.getPackageName();

    private final ApplicationModules modules = ApplicationModules.of(FreesiaSystemConfiguration.class);
    private final JavaClasses production = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT, "com.freesia.common", "com.freesia.framework");

    @Test
    void moduleDependenciesStayAcyclicAndRespectPublicInterfaces() {
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder("iam", "file", "notification", "audit", "dashboard");
        modules.verify();
    }

    @Test
    void everyModuleDeclaresItsApiAsTheNamedInterface() {
        assertThat(modules).allSatisfy(module ->
                assertThat(module.getNamedInterfaces().getByName("api")).as("api of module %s", module.getIdentifier()).isPresent());
    }

    /** A type directly in a module's root package would be public to every other module without anyone deciding so. */
    @Test
    void moduleTypesLiveInApiOrInternal() {
        classes().that().resideInAPackage(ROOT + ".*..")
                .and().doNotHaveSimpleName("package-info")
                .should().resideInAnyPackage(ROOT + ".*.api..", ROOT + ".*.internal..")
                .check(production);
    }

    @Test
    void theRootPackageHoldsOnlyTheLibraryAssembly() {
        classes().that().resideInAPackage(ROOT)
                .should().be(FreesiaSystemConfiguration.class)
                .check(production);
    }

    @Test
    void theFrameworkDoesNotDependOnBusinessModules() {
        noClasses().that().resideInAnyPackage("com.freesia.common..", "com.freesia.framework..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + "..")
                .check(production);
    }

    @Test
    void businessModulesNeverDependOnTheExecutableServer() {
        noClasses().that().resideInAPackage(ROOT + "..")
                .should().dependOnClassesThat().resideInAPackage("com.freesia.admin..")
                .check(production);
    }
}
