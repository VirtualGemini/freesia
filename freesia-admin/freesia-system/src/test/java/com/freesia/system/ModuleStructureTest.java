package com.freesia.system;

import com.mybatisflex.annotation.Table;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
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
    void implementationIsOrganizedByCapabilityBeforeResponsibility() {
        noClasses().that().resideInAPackage(ROOT + ".*.internal..")
                .should().resideInAnyPackage(
                        ROOT + ".*.internal.controller..", ROOT + ".*.internal.service..",
                        ROOT + ".*.internal.query..", ROOT + ".*.internal.mapper..",
                        ROOT + ".*.internal.entity..", ROOT + ".*.internal.dto..",
                        ROOT + ".*.internal.filter..", ROOT + ".*.internal.impl..")
                .check(production);
    }

    @Test
    void httpAdaptersLiveInTheirCapabilityRatherThanTheModuleApi() {
        classes().that().areAnnotatedWith(RestController.class)
                .or().areAnnotatedWith(RestControllerAdvice.class)
                .should().resideInAPackage(ROOT + ".*.internal.*.controller..")
                .check(production.that(resideInAPackage(ROOT + "..")));
    }

    @Test
    void queriesLiveInTheirCapabilityQueryPackage() {
        classes().that().resideInAPackage(ROOT + ".*.internal..")
                .and().haveSimpleNameEndingWith("Queries")
                .should().resideInAPackage(ROOT + ".*.internal.*.query..")
                .check(production);
    }

    @Test
    void writeMappersAndDatabaseRowsStayInTheirCapability() {
        classes().that().areAnnotatedWith(Mapper.class)
                .should().resideInAPackage(ROOT + ".*.internal.*.mapper..")
                .check(production.that(resideInAPackage(ROOT + "..")));
        classes().that().areAnnotatedWith(Table.class)
                .should().resideInAPackage(ROOT + ".*.internal.*.entity..")
                .check(production.that(resideInAPackage(ROOT + "..")));
    }

    @Test
    void configurationLivesAtItsActualScope() {
        classes().that().areAnnotatedWith(Configuration.class)
                .or().areAnnotatedWith(ConfigurationProperties.class)
                .should().resideInAnyPackage(ROOT + ".*.internal.config..", ROOT + ".*.internal.*.config..")
                .check(production.that(resideInAPackage(ROOT + ".*.internal..")));
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
