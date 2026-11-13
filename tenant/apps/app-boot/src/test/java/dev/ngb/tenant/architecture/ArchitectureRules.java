package dev.ngb.tenant.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import dev.ngb.core.arch.ArchitectureRules.NamedRule;
import dev.ngb.core.arch.ArchitectureRules.Profile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The sample tenant system's profile of the shared architecture rules plus the rules only it needs. The generic
 * rules live in {@code core-arch} and run identically in three services; what stays here is the shared core's
 * independence and the datasource rule, which names this build's wiring package.
 *
 * <p>Seven use cases are temporarily allowlisted for {@code use-case-opens-a-unit-of-work}: they call Billing or
 * read through a port without changing local state in more than one step. Follow-up 2026-01-31 either wraps them in
 * the unit of work or records them as read-only operations in {@code docs/tenant-system.md}.
 */
final class ArchitectureRules {

    static final String CORE = dev.ngb.core.arch.ArchitectureRules.CORE;

    /** The JDK: java.* plus the javax.* packages that ship with it (for example javax.crypto). */
    private static final List<String> JDK = List.of("java..", "javax..");

    /**
     * Which module owns each generated table. The execution queue belongs to resource, which enqueues in the same
     * transaction as the order; execution owns no table and reaches the queue through the resource api.
     */
    private static final Map<String, String> TABLE_OWNER = new HashMap<>();

    static {
        for (String table : List.of("Users", "UserSessions")) {
            TABLE_OWNER.put(table, "identity");
        }
        for (String table : List.of("Customers", "CustomerWalletSettings")) {
            TABLE_OWNER.put(table, "customer");
        }
        TABLE_OWNER.put("CatalogPlans", "catalog");
        TABLE_OWNER.put("UsageRecords", "usage");
        for (String table : List.of("Orders", "ExecutionQueue")) {
            TABLE_OWNER.put(table, "resource");
        }
        for (String table : List.of("WebhookEvents", "CustomerDebts", "CustomerPeriods")) {
            TABLE_OWNER.put(table, "webhook");
        }
    }

    /** Use cases that do not open a unit of work yet; the dated follow-up is on this class. */
    private static final Set<String> NOT_YET_ON_THE_UNIT_OF_WORK = Set.of(
            "RegisterCustomerUseCase",
            "TopUpUseCase",
            "OpenBillingSessionUseCase",
            "AttachBillingPlanUseCase",
            "AuthenticateUseCase",
            "ManageUsersUseCase",
            "RecordUsageUseCase");

    private ArchitectureRules() {}

    static List<NamedRule> all(String root) {
        return Stream.concat(
                        dev.ngb.core.arch.ArchitectureRules.all(profile(root)).stream(),
                        extras(root).stream())
                .toList();
    }

    static List<NamedRule> core(String core) {
        return dev.ngb.core.arch.ArchitectureRules.core(core);
    }

    static NamedRule testPackages(String root, Set<String> productionPackages) {
        return dev.ngb.core.arch.ArchitectureRules.testsMirrorProductionPackages(profile(root), productionPackages);
    }

    private static Profile profile(String root) {
        return new Profile(
                root,
                "module",
                "shared",
                "dev.ngb.tenant_jooq",
                TABLE_OWNER,
                Set.of(),
                Set.of("dev.ngb.billing..", "dev.ngb.platform.."),
                Set.of("*.adapter..persistence..", "boot.."),
                Set.of("dev.ngb.tenant_jooq.."),
                NOT_YET_ON_THE_UNIT_OF_WORK,
                Set.of());
    }

    /** What only the sample tenant system needs: its own shared core and its own wiring package. */
    private static List<NamedRule> extras(String root) {
        return List.of(
                new NamedRule("shared-core-is-independent", sharedCoreIsIndependent(root)),
                new NamedRule("datasource-and-transactions-only-in-core-spring", datasourceOnlyInInfrastructure(root)));
    }

    private static ArchRule sharedCoreIsIndependent(String root) {
        return classes().that().resideInAPackage(root + ".shared..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(concat(JDK, root + ".shared..", CORE + ".."))
                .because("the shared core takes no module dependency");
    }

    /**
     * Only the shared core's Spring module and the wiring package may open transactions or reach the datasource.
     * Anything else that touches the database runs outside a tenant-scoped transaction.
     */
    private static ArchRule datasourceOnlyInInfrastructure(String root) {
        return noClasses().that().resideOutsideOfPackages(CORE + ".spring..", root + ".boot..")
                .should().dependOnClassesThat().haveFullyQualifiedName("javax.sql.DataSource")
                .orShould().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.transaction..", "org.springframework.jdbc.datasource..")
                .because("the shared core's UnitOfWork is the only owner of transactions and the tenant setting");
    }

    private static String[] concat(List<String> prefix, String... rest) {
        List<String> all = new ArrayList<>(prefix);
        all.addAll(List.of(rest));
        return all.toArray(String[]::new);
    }
}
