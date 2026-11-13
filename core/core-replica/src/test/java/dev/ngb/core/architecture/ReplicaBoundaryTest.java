package dev.ngb.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The replica is a core module, so it is shared by every app and never reaches into one. It may use Spring (the
 * resource server, the web client) and jOOQ (the plain-SQL replica tables), because it is the library that owns them.
 */
@AnalyzeClasses(packages = "dev.ngb.core.replica")
class ReplicaBoundaryTest {

    @ArchTest
    static final ArchRule replicaDoesNotDependOnAService = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.ngb.billing..", "dev.ngb.tenant..", "dev.ngb.platform..")
            .because("core-replica is shared by every service, so no service may leak into it");

    @ArchTest
    static final ArchRule replicaDoesNotUseGeneratedSchema = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.ngb.billing_jooq..", "dev.ngb.platform_jooq..", "dev.ngb.tenant_jooq..")
            .because("the replica tables belong to the app's schema, so the writer addresses them with plain SQL");

    @ArchTest
    static final ArchRule replicaDoesNotUseAnHttpClient = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("feign..", "dev.ngb.core.feign..")
            .because("the snapshot transport belongs to the app's boot layer, so core-replica names no HTTP client");
}
