package dev.ngb.mailer.architecture;

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
 * Mailer's profile of the shared architecture rules plus the rules only it needs.
 *
 * <p>The generic rules live in {@code core-arch} and run identically in every service. What stays here is the shared
 * core's independence, the datasource rule that names this build's wiring package, and the table ownership map, which
 * is what makes {@code context-uses-only-own-tables} fire rather than pass vacuously.
 *
 * <p>Mailer has one bounded context, {@code email}, so every table it owns belongs to it. The tenant list is in the
 * same context rather than in {@code boot}, because the sweep reads it and a boot class that reached for a generated
 * table would be a wiring class owning persistence.
 */
final class ArchitectureRules {

    static final String CORE = dev.ngb.core.arch.ArchitectureRules.CORE;

    /** The JDK: java.* plus the javax.* packages that ship with it (for example javax.crypto). */
    private static final List<String> JDK = List.of("java..", "javax..");

    /** Which context owns each generated table. Mailer has one, so every table maps to it. */
    private static final Map<String, String> TABLE_OWNER = new HashMap<>();

    static {
        for (String table : List.of(
                "MailerTenants", "EmailDeliveries", "EmailDeliveryAttempts", "EmailBodies", "MailerCredentials", "EventInbox")) {
            TABLE_OWNER.put(table, "email");
        }
    }

    /** Every use case opens a unit of work; mailer has no exception and should never grow one. */
    private static final Set<String> NOT_YET_ON_THE_UNIT_OF_WORK = Set.of();

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
                "context",
                "shared",
                "dev.ngb.mailer_jooq",
                TABLE_OWNER,
                Set.of(),
                Set.of("dev.ngb.billing..", "dev.ngb.platform.."),
                Set.of("*.adapter..persistence..", "boot.."),
                Set.of("dev.ngb.mailer_jooq.."),
                NOT_YET_ON_THE_UNIT_OF_WORK,
                Set.of());
    }

    /** What only mailer needs: its own shared core and its own wiring package. */
    private static List<NamedRule> extras(String root) {
        return List.of(
                new NamedRule("shared-core-is-independent", sharedCoreIsIndependent(root)),
                new NamedRule("datasource-and-transactions-only-in-core-spring", datasourceOnlyInInfrastructure(root)));
    }

    private static ArchRule sharedCoreIsIndependent(String root) {
        return classes().that().resideInAPackage(root + ".shared..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(concat(JDK, root + ".shared..", CORE + ".."))
                .because("the shared core takes no context dependency");
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
