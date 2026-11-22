package dev.ngb.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The shared HTTP client is used by every service build, so it names none of them and never depends on the replica's
 * port: the transport that couples the two lives in an app's boot layer.
 */
@AnalyzeClasses(packages = "dev.ngb.core.feign")
class FeignBoundaryTest {

    @ArchTest
    static final ArchRule feignDoesNotDependOnAService = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.ngb.billing..", "dev.ngb.platform..", "dev.ngb.mailer..")
            .because("core-feign is shared by every service, so no service may leak into it");

    @ArchTest
    static final ArchRule feignDoesNotDependOnTheReplica = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.ngb.core.replica..")
            .because("the snapshot transport belongs to the app's boot layer, so core-feign stays independent of it");
}
