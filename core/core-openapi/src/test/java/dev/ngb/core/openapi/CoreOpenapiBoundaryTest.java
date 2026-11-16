package dev.ngb.core.openapi;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** The OpenAPI bridge may see the framework, springdoc and the core, and never names a service. */
class CoreOpenapiBoundaryTest {

    private static JavaClasses openapi() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.ngb.core.openapi");
        assertThat(classes.contain(ListingOpenApiCustomizer.class)).isTrue();
        return classes;
    }

    @Test
    void theBridgeNamesNoService() {
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("dev.ngb.billing..", "dev.ngb.tenant..", "dev.ngb.platform..", "dev.ngb.mailer..")
                .check(openapi());
    }

    @Test
    void theBridgeSeesOnlyTheCoreTheFrameworkAndSpringdoc() {
        classes().should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "javax..", "jakarta..", "dev.ngb.core..", "org.springframework..", "org.springdoc..", "io.swagger..")
                .check(openapi());
    }
}
