package dev.ngb.booking.architecture;

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
 * Booking's profile of the shared architecture rules plus the rules only it needs.
 *
 * <p>Five contexts: {@code platform} owns the replica tables, {@code catalog} the resources, hours, offerings and
 * sessions, {@code inventory} the two claim engines and availability, {@code booking} the aggregate, lifecycle,
 * waitlist and series, {@code gate} the customer tokens and the public surface, and {@code webhook} the endpoints and
 * deliveries. Allowed edges are one-way: {@code catalog} ← {@code inventory} ← {@code booking} ← {@code gate}, and
 * {@code webhook} reads booking events only through the outbox.
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
        for (String table : List.of("EventInbox", "ReplicaState", "Outbox", "BookingSettings")) {
            TABLE_OWNER.put(table, "boot");
        }
        // Webhook endpoints and deliveries stay in the webhook context, shaped like Billing's and never shared.
        for (String table : List.of("WebhookEndpoints", "WebhookDeliveries")) {
            TABLE_OWNER.put(table, "webhook");
        }
        // The catalog owns what may be booked and when it is open.
        for (String table : List.of(
                "Resources",
                "WorkingHours",
                "CalendarExceptions",
                "ResourceGroups",
                "GroupMembers",
                "Offerings",
                "OfferingRequirements",
                "CancellationWindows",
                "Blocks",
                "SessionSeries",
                "Sessions",
                "SessionPools")) {
            TABLE_OWNER.put(table, "catalog");
        }
        // The two engines own the rows a claim is made of.
        TABLE_OWNER.put("IntervalClaims", "inventory");
        TABLE_OWNER.put("CapacityUnits", "inventory");
        // The booking context owns the aggregate and the resources it took.
        TABLE_OWNER.put("Bookings", "booking");
        TABLE_OWNER.put("BookingResources", "booking");
        TABLE_OWNER.put("BookingSeries", "booking");
        TABLE_OWNER.put("WaitlistEntries", "booking");
        TABLE_OWNER.put("WaitlistWakeups", "booking");
        // The gate owns the tenant's public-edge policy.
        TABLE_OWNER.put("GateSettings", "gate");
        TABLE_OWNER.put("QueueTickets", "gate");
        TABLE_OWNER.put("QueueState", "gate");
        // The webhook context owns its endpoints, deliveries and the append-only attempt log.
        TABLE_OWNER.put("WebhookEndpoints", "webhook");
        TABLE_OWNER.put("WebhookDeliveries", "webhook");
        TABLE_OWNER.put("WebhookDeliveryAttempts", "webhook");
    }

    /** Every use case opens a unit of work; booking has no exception and should never grow one. */
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
                "dev.ngb.booking_jooq",
                TABLE_OWNER,
                Set.of(),
                Set.of(
                        "dev.ngb.billing..", "dev.ngb.mailer..", "dev.ngb.platform..", "dev.ngb.tenant..",
                        "dev.ngb.notification..", "dev.ngb.mailroom..", "dev.ngb.survey..", "dev.ngb.attribution..",
                        "dev.ngb.customer..", "dev.ngb.loyalty.."),
                Set.of("*.adapter..persistence..", "boot.."),
                Set.of("dev.ngb.booking_jooq.."),
                NOT_YET_ON_THE_UNIT_OF_WORK,
                Set.of());
    }

    /** What only booking needs: the shared core takes no context dependency and no stray class owns a transaction. */
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
