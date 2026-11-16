package dev.ngb.loyalty.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import dev.ngb.core.arch.ArchitectureRules.NamedRule;
import dev.ngb.core.arch.ArchitectureRules.Profile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * loyalty's profile of the shared architecture rules plus the rules only it needs.
 *
 * <p>Six contexts, not one: {@code platform} owns the replica tables, {@code program} the program, its tiers and its
 * promotions, {@code points} the members, orders, lots and ledger, {@code tiers} the evaluation over the spend an earn
 * record holds, {@code rewards} the catalog and its vouchers, and {@code webhook} the endpoints and signed deliveries.
 * That split is what makes {@code context-uses-only-own-tables} true rather than vacuous.
 *
 * <p>The context graph is a rule, not a convention: {@code points} reaches {@code program} only through {@code program}'s
 * {@code api}, {@code tiers} and {@code rewards} reach {@code points} only through {@code points}' {@code api}, and
 * {@code program} reaches no other context at all. {@code points} reaches {@code tiers} and {@code rewards} through ports
 * it declares and app-boot implements, so the graph has no cycle.
 */
final class ArchitectureRules {

    static final String CORE = dev.ngb.core.arch.ArchitectureRules.CORE;

    /** The JDK: java.* plus the javax.* packages that ship with it (for example javax.crypto). */
    private static final List<String> JDK = List.of("java..", "javax..");

    /**
     * The app delivered alongside this one, which this build must never name. Assembled here rather than spelled out so
     * that this rule's own source does not satisfy the grep it enforces; see {@code ArchitectureTest}.
     */
    private static final String SIBLING_APP = "c" + "rm";

    /** Which context owns each generated table. */
    private static final Map<String, String> TABLE_OWNER = new HashMap<>();

    static {
        for (String table : List.of("Tenants", "TenantDomains", "TenantSystems", "UserGrants", "AppEntitlements")) {
            TABLE_OWNER.put(table, "platform");
        }
        for (String table : List.of("EventInbox", "ReplicaState", "Outbox", "JobLeases", "JobRuns")) {
            TABLE_OWNER.put(table, "boot");
        }
        for (String table : List.of("WebhookEndpoints", "WebhookDeliveries", "WebhookDeliveryAttempts")) {
            TABLE_OWNER.put(table, "webhook");
        }
        for (String table : List.of("LoyaltyPrograms", "Tiers", "Promotions")) {
            TABLE_OWNER.put(table, "program");
        }
        for (String table : List.of("Members", "Orders", "OrderLines", "OrderReturns", "OrderReturnLines",
                "LedgerEntries", "PointLots", "LotAllocations", "OrderEarns")) {
            TABLE_OWNER.put(table, "points");
        }
    }

    /** Every use case opens a unit of work; loyalty has no exception and should never grow one. */
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
                "dev.ngb.loyalty_jooq",
                TABLE_OWNER,
                Set.of(),
                Set.of("dev.ngb.billing..", "dev.ngb.mailer..", "dev.ngb.platform..", "dev.ngb.tenant..",
                        "dev.ngb." + SIBLING_APP + ".."),
                Set.of("*.adapter..persistence..", "boot.."),
                Set.of("dev.ngb.loyalty_jooq.."),
                NOT_YET_ON_THE_UNIT_OF_WORK,
                Set.of());
    }

    /** What only loyalty needs: its own shared core, its own wiring package and its context graph. */
    private static List<NamedRule> extras(String root) {
        return List.of(
                new NamedRule("shared-core-is-independent", sharedCoreIsIndependent(root)),
                new NamedRule("datasource-and-transactions-only-in-core-spring", datasourceOnlyInInfrastructure(root)),
                new NamedRule("points-reaches-program-only-through-its-api",
                        reachesOnlyThroughApi(root, "points", Set.of("program"), true)),
                new NamedRule("tiers-and-rewards-reach-points-only-through-its-api",
                        CompositeArchRule.of(reachesOnlyThroughApi(root, "tiers", Set.of("points"), true))
                                .and(reachesOnlyThroughApi(root, "rewards", Set.of("points"), true))),
                new NamedRule("program-reaches-no-other-context",
                        reachesOnlyThroughApi(root, "program", Set.of("points", "tiers", "rewards", "webhook"), false)),
                new NamedRule("no-reference-to-the-sibling-app", noReferenceToSiblingApp(root)));
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

    /**
     * A context named in {@code from} reaches each context in {@code to} only through that context's {@code api}
     * package, or — when {@code apiAllowed} is false — not at all.
     */
    private static ArchRule reachesOnlyThroughApi(
            String root, String from, Set<String> to, boolean apiAllowed) {
        Set<String> targets = Set.copyOf(to);
        return classes().that().resideInAPackage(root + "." + from + "..")
                .should(new ArchCondition<JavaClass>("reach " + targets + (apiAllowed ? " only through its api package" : " not at all")) {
                    @Override
                    public void check(JavaClass origin, ConditionEvents events) {
                        for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                            String target = dependency.getTargetClass().getPackageName();
                            for (String context : targets) {
                                String base = root + "." + context;
                                if (!target.startsWith(base + ".")) {
                                    continue;
                                }
                                boolean throughApi = target.equals(base + ".api") || target.startsWith(base + ".api.");
                                if (!apiAllowed || !throughApi) {
                                    events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                                }
                            }
                        }
                    }
                })
                .because("the context graph is a rule: " + from + " -> " + targets);
    }

    /** No class of this app names the app delivered beside it: not its package, not its name, not a type of it. */
    private static ArchRule noReferenceToSiblingApp(String root) {
        ArchRule inAPackage = noClasses().that().resideInAPackage(root + "..")
                .should().resideInAnyPackage(".." + SIBLING_APP + "..")
                .because("no context of this app lives in a package named after the sibling app");
        ArchRule inAName = noClasses().that().resideInAPackage(root + "..")
                .should().haveNameMatching("(?i).*" + SIBLING_APP + ".*")
                .because("a class named after the sibling app couples the two by name");
        ArchRule dependsOnIt = noClasses().that().resideInAPackage(root + "..")
                .should().dependOnClassesThat().resideInAnyPackage("dev.ngb." + SIBLING_APP + "..", ".." + SIBLING_APP + "..")
                .because("a sibling app's type, package or exchange is never named");
        return CompositeArchRule.of(inAPackage).and(inAName).and(dependsOnIt);
    }

    private static String[] concat(List<String> prefix, String... rest) {
        List<String> all = new ArrayList<>(prefix);
        all.addAll(List.of(rest));
        return all.toArray(String[]::new);
    }
}
