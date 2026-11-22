package dev.ngb.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The sync is a core module shared by every app, so it never reaches into one. It fetches the snapshot and rebuilds
 * the replica; it holds no request-side type, and it never reads back the policy that core-suite enforces.
 */
@AnalyzeClasses(packages = "dev.ngb.core.sync")
class PlatformSyncBoundaryTest {

    @ArchTest
    static final ArchRule syncDoesNotDependOnAService = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.ngb.billing..", "dev.ngb.platform..", "dev.ngb.mailer..")
            .because("core-platform-sync is shared by every service, so no service may leak into it");

    @ArchTest
    static final ArchRule syncDoesNotUseGeneratedSchema = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.ngb.billing_jooq..", "dev.ngb.platform_jooq..",
                    "dev.ngb.mailer_jooq..")
            .because("the replica tables belong to the app's schema, so the rebuild writes through core-replica");

    @ArchTest
    static final ArchRule syncDoesNotUseTheRequestSide = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.security..", "jakarta.servlet..", "dev.ngb.core.suite..")
            .because("fetching the snapshot and rebuilding the replica happen before any request exists");
}
