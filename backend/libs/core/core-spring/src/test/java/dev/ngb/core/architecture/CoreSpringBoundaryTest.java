package dev.ngb.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The Spring implementation may see the framework and jOOQ and nothing else, and it never names a service. This is
 * the module-level form of {@code core-spring-is-self-contained}.
 */
class CoreSpringBoundaryTest {

    private static JavaClasses spring;

    @BeforeAll
    static void importSpringImplementation() {
        spring = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.ngb.core.spring");
        // Guards against an import that silently matches nothing and makes every rule pass.
        assertThat(spring.contain("dev.ngb.core.spring.runtime.SpringUnitOfWork")).isTrue();
    }

    @Test
    void theSpringImplementationNamesNoService() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("dev.ngb.billing..", "dev.ngb.platform..",
                        "dev.ngb.mailer..")
                .because("core is shared by every service, so no service may leak into it")
                .check(spring);
    }

    @Test
    void theSpringImplementationSeesOnlyTheCoreAndTheFramework() {
        classes()
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "javax..", "jakarta..", "dev.ngb.core..", "org.springframework..", "org.jooq..")
                .because("the Spring implementation is the only core module that may see a framework type")
                .check(spring);
    }
}
