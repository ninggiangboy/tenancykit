package dev.ngb.attribution.architecture;

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
 * Attribution's profile of the shared architecture rules plus the rules only it needs.
 *
 * <p>Six contexts, not one: {@code platform} owns the replica tables, {@code links} the campaigns, links,
 * destinations and the redirect decision, {@code tracking} the click, page-view, session and goal events plus visitor
 * identity, {@code attribution} the goals, conversions, chains and credits, {@code spend} the cost entries and ad
 * connections, and {@code insight} the rollups, reports and exports. That split is what makes
 * {@code context-uses-only-own-tables} true rather than vacuous: a single context could own every table.
 */
final class ArchitectureRules {

    static final String CORE = dev.ngb.core.arch.ArchitectureRules.CORE;

    /** The JDK: java.* plus the javax.* packages that ship with it (for example javax.crypto). */
    private static final List<String> JDK = List.of("java..", "javax..");

    /** Which context owns each generated table. A table of a later phase is added here with its migration. */
    private static final Map<String, String> TABLE_OWNER = new HashMap<>();

    static {
        for (String table : List.of("Tenants", "TenantDomains", "TenantSystems", "UserGrants", "AppEntitlements")) {
            TABLE_OWNER.put(table, "platform");
        }
        // The inbox, the replica's state and the outbox belong to the wiring package.
        for (String table : List.of("EventInbox", "ReplicaState", "Outbox")) {
            TABLE_OWNER.put(table, "boot");
        }
        // Campaigns and tracked links are the links context: the campaigns, the links, the destination rules, the
        // per-link click counter and the bulk-request ledger.
        for (String table : List.of(
                "Campaigns", "Links", "LinkDestinations", "LinkCounters", "BulkLinkRequests", "AttributionSettings")) {
            TABLE_OWNER.put(table, "links");
        }
        // The click events are the tracking context: the stored, classified clicks the redirect produced. The
        // partition-managed allow list is the migration's own bookkeeping and belongs to no context.
        TABLE_OWNER.put("ClickEvents", "tracking");
        TABLE_OWNER.put("PartitionManagedTables", "boot");
        // The collected page views, sessions and bindings are the tracking context's too.
        for (String table : List.of("PageEvents", "Sessions", "VisitorClicks")) {
            TABLE_OWNER.put(table, "tracking");
        }
        // Goals, conversions, their credits and the recompute queue are the attribution context's.
        for (String table : List.of("Goals", "Conversions", "ConversionCredits", "RecomputeRuns")) {
            TABLE_OWNER.put(table, "attribution");
        }
        // The rollups and the deployment-wide cursor are insight's; a report reads them and never a raw event.
        for (String table : List.of(
                "RollupTrafficHourly", "RollupReferrersDaily", "RollupConversionsDaily", "RollupCursor")) {
            TABLE_OWNER.put(table, "insight");
        }
        // Cost entries are spend's; insight reads them only through the cost api.
        TABLE_OWNER.put("SpendEntries", "spend");
    }

    /** Every use case opens a unit of work; attribution has no exception and should never grow one. */
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
                "dev.ngb.attribution_jooq",
                TABLE_OWNER,
                Set.of(),
                Set.of("dev.ngb.billing..", "dev.ngb.mailer..", "dev.ngb.mailroom..", "dev.ngb.platform..",
                        "dev.ngb.survey.."),
                Set.of("*.adapter..persistence..", "boot.."),
                Set.of("dev.ngb.attribution_jooq.."),
                NOT_YET_ON_THE_UNIT_OF_WORK,
                Set.of());
    }

    /** What only attribution needs: its own shared core and its own wiring package. */
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
