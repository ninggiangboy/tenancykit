package dev.ngb.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Core is the only shared code: it never reaches into a service, so a service can depend on it safely. */
@AnalyzeClasses(packages = "dev.ngb.core")
class CoreBoundaryTest {

    @ArchTest
    static final ArchRule coreDoesNotDependOnAService = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.ngb.billing..", "dev.ngb.platform..", "dev.ngb.mailer..")
            .because("core is shared by every service, so no service may leak into it");
}
