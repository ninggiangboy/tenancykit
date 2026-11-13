package dev.ngb.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The suite policy is a core module shared by every app, so it never reaches into one. It answers from the replica
 * and never fetches it, so an app that only enforces policy never pays for the snapshot client.
 */
@AnalyzeClasses(packages = "dev.ngb.core.suite")
class SuiteBoundaryTest {

    @ArchTest
    static final ArchRule suiteDoesNotDependOnAService = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.ngb.billing..", "dev.ngb.tenant..", "dev.ngb.platform..")
            .because("core-suite is shared by every service, so no service may leak into it");

    @ArchTest
    static final ArchRule suiteDoesNotUseGeneratedSchema = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.ngb.billing_jooq..", "dev.ngb.platform_jooq..", "dev.ngb.tenant_jooq..")
            .because("the replica tables belong to the app's schema, so the policy reads them with plain SQL");

    @ArchTest
    static final ArchRule suiteDoesNotUseTheSync = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.ngb.core.sync..", "feign..", "dev.ngb.core.feign..")
            .because("enforcing a request reads the replica; fetching the snapshot is the other module's job");
}
