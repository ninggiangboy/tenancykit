package dev.ngb.crm.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import dev.ngb.core.arch.ArchitectureRules.NamedRule;
import dev.ngb.core.arch.ArchitectureRules.Profile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * crm's profile of the shared architecture rules plus the rules only it needs.
 *
 * <p>Six contexts, not one: {@code platform} owns the replica tables, {@code customers} the customers and their tags,
 * fields and consent, {@code orders} the orders with lines and returns, {@code activity} the activities, {@code profile}
 * the 360 read model and the export, and {@code webhook} the endpoints and the signed deliveries. That split is what
 * makes {@code context-uses-only-own-tables} true rather than vacuous, and it is what the plan's seam is written
 * against: the profile reads the other three through their {@code api} packages and writes nothing, so a customer
 * cannot be changed by reading it.
 */
final class ArchitectureRules {

    static final String CORE = dev.ngb.core.arch.ArchitectureRules.CORE;

    /** The JDK: java.* plus the javax.* packages that ship with it (for example javax.crypto). */
    private static final List<String> JDK = List.of("java..", "javax..");

    /** Which context owns each generated table. */
    private static final Map<String, String> TABLE_OWNER = new HashMap<>();

    static {
        for (String table : List.of("Tenants", "TenantDomains", "TenantSystems", "UserGrants", "AppEntitlements")) {
            TABLE_OWNER.put(table, "platform");
        }
        // The wiring layer owns the messaging and coordination tables; no context reaches them.
        for (String table : List.of("EventInbox", "ReplicaState", "Outbox", "JobLeases", "JobRuns")) {
            TABLE_OWNER.put(table, "boot");
        }
        for (String table : List.of("WebhookEndpoints", "WebhookDeliveries", "WebhookDeliveryAttempts")) {
            TABLE_OWNER.put(table, "webhook");
        }
    }

    /** Every use case opens a unit of work; crm has no exception and should never grow one. */
    private static final Set<String> NOT_YET_ON_THE_UNIT_OF_WORK = Set.of();

    /** The contexts the profile context may read, and only through their api packages. */
    private static final Set<String> PROFILE_READS = Set.of("customers", "orders", "activity");

    /** The other apps of the suite: crm names none of them, and depends on none of them. */
    private static final List<String> OTHER_APPS = List.of(
            "loyalty", "billing", "mailroom", "survey", "attribution", "notification", "mailer");

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
        List<String> foreign = new ArrayList<>();
        foreign.addAll(List.of("dev.ngb.billing..", "dev.ngb.mailer..", "dev.ngb.platform..", "dev.ngb.tenant.."));
        foreign.addAll(List.of(
                "dev.ngb.mailroom..", "dev.ngb.survey..", "dev.ngb.attribution..", "dev.ngb.notification..",
                "dev.ngb.loyalty.."));
        return new Profile(
                root,
                "context",
                "shared",
                "dev.ngb.crm_jooq",
                TABLE_OWNER,
                Set.of(),
                Set.copyOf(foreign),
                Set.of("*.adapter..persistence..", "boot.."),
                Set.of("dev.ngb.crm_jooq.."),
                NOT_YET_ON_THE_UNIT_OF_WORK,
                Set.of());
    }

    /** What only crm needs: its own shared core, its own wiring package and the seams between its contexts. */
    private static List<NamedRule> extras(String root) {
        return List.of(
                new NamedRule("shared-core-is-independent", sharedCoreIsIndependent(root)),
                new NamedRule("datasource-and-transactions-only-in-core-spring", datasourceOnlyInInfrastructure(root)),
                new NamedRule("profile-reads-through-api", profileReadsThroughApi(root)),
                new NamedRule("profile-writes-nothing", profileWritesNothing(root)),
                new NamedRule("orders-and-activity-reach-customers-through-api", writesReachCustomersThroughApi(root)),
                new NamedRule("business-contexts-do-not-import-webhook", businessContextsDoNotImportWebhook(root)),
                new NamedRule("contexts-do-not-reach-platform-adapters", contextsDoNotReachPlatformAdapters(root)),
                new NamedRule("no-class-names-another-app", noClassNameAnotherApp(root)));
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

    /** The 360 profile and the export read their three contexts through their api packages and nothing else. */
    private static ArchRule profileReadsThroughApi(String root) {
        return classes().that().resideInAPackage(root + ".profile..")
                .should(dependOnAnotherContextOutsideItsApi(root, PROFILE_READS))
                .because("the profile reads customers, orders and activity through their api packages only");
    }

    /** The profile is a read model: it writes no table, so a read cannot change a customer. */
    private static ArchRule profileWritesNothing(String root) {
        return noClasses().that().resideInAPackage(root + ".profile..")
                .should().dependOnClassesThat().resideInAnyPackage("org.jooq..")
                .because("the profile is a read-only projection and owns no table");
    }

    /** Orders and activities name a customer; they resolve it through the customers context's api package only. */
    private static ArchRule writesReachCustomersThroughApi(String root) {
        return classes().that().resideInAnyPackage(root + ".orders..", root + ".activity..")
                .should(dependOnAnotherContextOutsideItsApi(root, Set.of("customers")))
                .because("orders and activities reach the customers context through its api package only");
    }

    /** A business context never imports webhooks: the facts leave through the outbox, and the webhook context listens. */
    private static ArchRule businessContextsDoNotImportWebhook(String root) {
        return noClasses().that().resideInAnyPackage(
                        root + ".customers..", root + ".orders..", root + ".activity..", root + ".profile..",
                        root + ".platform..")
                .should().dependOnClassesThat().resideInAnyPackage(root + ".webhook..")
                .because("a business context writes the outbox and never knows that a webhook exists");
    }

    /** Another context reaches the replica through {@code platform.api}; the jOOQ adapters stay inside platform. */
    private static ArchRule contextsDoNotReachPlatformAdapters(String root) {
        return noClasses().that().resideOutsideOfPackages(root + ".platform..", root + ".boot..")
                .should().dependOnClassesThat().resideInAnyPackage(root + ".platform.adapter..")
                .because("a context uses the platform context's api package and never its adapters");
    }

    /**
     * No class of crm names another app of the suite. crm and loyalty are separate deployables that share only
     * {@code core/} and Platform's events, so a class named after one — or a dependency on one — is the link the plan
     * forbids. A foreign exchange name is a literal rather than a type, so the source scan in {@code ArchitectureTest}
     * covers it.
     */
    private static ArchRule noClassNameAnotherApp(String root) {
        return classes().that().resideInAPackage(root + "..")
                .should(new ArchCondition<>("name no other suite app") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        String name = item.getSimpleName().toLowerCase(Locale.ROOT);
                        for (String app : OTHER_APPS) {
                            if (name.contains(app)) {
                                events.add(SimpleConditionEvent.violated(
                                        item, item.getFullName() + " is named after the " + app + " app"));
                            }
                        }
                        for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                            String target = dependency.getTargetClass().getPackageName();
                            for (String app : OTHER_APPS) {
                                if (target.startsWith("dev.ngb." + app)) {
                                    events.add(SimpleConditionEvent.violated(
                                            item, item.getFullName() + " depends on the " + app + " app"));
                                }
                            }
                        }
                    }
                })
                .because("crm never names another app of the suite: the Tenant System connects them");
    }

    /** A dependency on another context of this build that is not inside that context's {@code api} package. */
    private static ArchCondition<JavaClass> dependOnAnotherContextOutsideItsApi(String root, Set<String> targets) {
        return new ArchCondition<>("depend on another context outside its api package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    String target = dependency.getTargetClass().getPackageName();
                    for (String context : targets) {
                        String base = root + "." + context;
                        if (!target.equals(base) && !target.startsWith(base + ".")) {
                            continue;
                        }
                        boolean inApi = target.equals(base + ".api") || target.startsWith(base + ".api.");
                        if (!inApi) {
                            events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                        }
                    }
                }
            }
        };
    }

    private static String[] concat(List<String> prefix, String... rest) {
        List<String> all = new ArrayList<>(prefix);
        all.addAll(List.of(rest));
        return all.toArray(String[]::new);
    }
}
