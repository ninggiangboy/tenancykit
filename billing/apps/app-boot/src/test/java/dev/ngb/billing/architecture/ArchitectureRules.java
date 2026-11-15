package dev.ngb.billing.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import dev.ngb.core.arch.ArchitectureRules.NamedRule;
import dev.ngb.core.arch.ArchitectureRules.Profile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Billing's profile of the shared architecture rules plus the rules only Billing needs. The generic rules live in
 * {@code core-arch} and run identically in three services; what stays here is what the profile cannot express: the
 * money path, the Stripe adapter, the guarded webhook sender, the ledger and the kernel.
 *
 * <p>The money path's "a use case opens the transaction itself" rule is generic now, as
 * {@code use-case-opens-a-unit-of-work}; every Billing use case depends on the unit of work.
 */
final class ArchitectureRules {

    static final String CORE = dev.ngb.core.arch.ArchitectureRules.CORE;

    /** Which context owns each generated table; boot owns the tables every context shares. */
    private static final Map<String, String> TABLE_OWNER = new HashMap<>();

    static {
        for (String table : List.of("Customers", "CustomerMappings")) {
            TABLE_OWNER.put(table, "customer");
        }
        TABLE_OWNER.put("UsageEvents", "usage");
        for (String table : List.of("WebhookEndpoints", "WebhookDeliveries", "WebhookDeliveryAttempts")) {
            TABLE_OWNER.put(table, "webhook");
        }
        for (String table : List.of(
                "ApiCredentials", "Roles", "RolePermissions", "Permissions", "BillingUsers", "RoleAssignments", "UserPermissions", "Tenants",
                "TenantDomains", "TenantSystems")) {
            TABLE_OWNER.put(table, "platform");
        }
        for (String table : List.of("PricingPlans", "PricingPlanVersions", "PricingMetrics", "UsageRatings", "UsageCharges")) {
            TABLE_OWNER.put(table, "pricing");
        }
        for (String table : List.of(
                "Payments", "PaymentAttempts", "Refunds", "StripeConfigurations", "StripeEvents", "StripeEventAttempts", "StripeIncidents",
                "StripeRoutes", "StripeCustomers")) {
            TABLE_OWNER.put(table, "payment");
        }
        for (String table : List.of("Invoices", "InvoiceItems", "InvoicePayments", "InvoiceCounters", "Subscriptions", "SubscriptionEvents")) {
            TABLE_OWNER.put(table, "invoicing");
        }
        for (String table : List.of("Wallets", "LedgerEntries", "Reservations", "Charges")) {
            TABLE_OWNER.put(table, "money");
        }
        for (String table : List.of("BillingSessions", "PortalReturnUrls")) {
            TABLE_OWNER.put(table, "session");
        }
        for (String table : List.of("Outbox", "EventInbox", "AuditLog", "JobLeases", "JobRuns", "RateLimitBuckets")) {
            TABLE_OWNER.put(table, "boot");
        }
    }

    private ArchitectureRules() {}

    /** The shared rules plus Billing's own, for the production root or for the fixture root. */
    static List<NamedRule> all(String root) {
        return Stream.concat(
                        dev.ngb.core.arch.ArchitectureRules.all(profile(root)).stream(),
                        extras(root).stream())
                .toList();
    }

    /** The shared rules that guard the core, for the production code or for the fixtures that spell the core. */
    static List<NamedRule> core(String core) {
        return dev.ngb.core.arch.ArchitectureRules.core(core);
    }

    /** The test-package rule needs the packages production occupies, so the test works them out from its import. */
    static NamedRule testPackages(String root, Set<String> productionPackages) {
        return dev.ngb.core.arch.ArchitectureRules.testsMirrorProductionPackages(profile(root), productionPackages);
    }

    private static Profile profile(String root) {
        return new Profile(
                root,
                "context",
                "sharedkernel",
                "dev.ngb.billing_jooq",
                TABLE_OWNER,
                Set.of(),
                Set.of("dev.ngb.platform..", "dev.ngb.tenant..", "dev.ngb.mailer.."),
                Set.of("*.adapter..persistence..", "boot.."),
                Set.of("dev.ngb.billing_jooq.."),
                Set.of(),
                Set.of());
    }

    /** What only Billing needs: the money path, the payment provider, the webhook edge and the kernel. */
    private static List<NamedRule> extras(String root) {
        return List.of(
                new NamedRule("ledger-repository-is-append-only", ledgerRepositoryIsAppendOnly()),
                new NamedRule("datasource-and-transactions-only-in-infrastructure", datasourceOnlyInInfrastructure(root)),
                new NamedRule("outbound-http-only-through-the-guarded-sender", outboundHttpOnlyThroughTheGuardedSender(root)),
                new NamedRule("stripe-types-stay-in-the-stripe-adapter", stripeTypesStayInTheStripeAdapter(root)),
                new NamedRule("shared-kernel-is-independent", sharedKernelIsIndependent(root)),
                new NamedRule("domain-id-names-its-aggregate", domainIdNamesItsAggregate(root)),
                new NamedRule("domain-features-are-free-of-cycles", domainFeaturesAreFreeOfCycles(root)),
                new NamedRule("shared-kernel-classes-in-a-group", sharedKernelClassesInAGroup(root)));
    }

    /**
     * Only the unit of work may open transactions or reach the datasource. Anything else that touches the
     * database runs outside a tenant-scoped transaction, where row-level security returns no rows.
     */
    private static ArchRule datasourceOnlyInInfrastructure(String root) {
        return noClasses().that().resideOutsideOfPackage(root + ".boot.infrastructure..")
                .should().dependOnClassesThat().haveFullyQualifiedName("javax.sql.DataSource")
                .orShould().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.transaction..", "org.springframework.jdbc.datasource..")
                .because("the UnitOfWork is the only owner of transactions and the tenant setting");
    }

    /** The ledger's repository port can append and read; a method that changes or removes an entry cannot exist. */
    private static ArchRule ledgerRepositoryIsAppendOnly() {
        return noMethods().that().areDeclaredInClassesThat().haveSimpleName("LedgerRepository")
                .should().haveNameMatching("(update|delete|remove|save|set|merge|upsert|truncate).*")
                .because("the ledger is append-only: corrections are reversal entries, never edits");
    }

    /**
     * Webhook URLs are chosen by Tenants, so every call to one must go through the sender whose resolver refuses
     * non-public addresses. Any other HTTP client inside the webhook module would bypass that guard.
     */
    private static ArchRule outboundHttpOnlyThroughTheGuardedSender(String root) {
        return noClasses().that().resideInAPackage(root + ".webhook..")
                .and().resideOutsideOfPackage(root + ".webhook.adapter.delivery.http..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.net.http..", "org.apache.hc..", "org.springframework.web.client..", "org.springframework.http.client..",
                        "org.springframework.web.reactive.function.client..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.URL")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.net.HttpURLConnection")
                .because("a Tenant-chosen destination must only be called through the guarded sender");
    }

    /**
     * Stripe's SDK types may be named only by the Stripe adapter. Everything else talks to the PaymentProvider
     * port, which is what keeps a second provider a matter of one adapter and keeps API drift in one place.
     */
    private static ArchRule stripeTypesStayInTheStripeAdapter(String root) {
        return noClasses().that().resideOutsideOfPackage(root + ".payment.adapter.stripe.provider..")
                .should().dependOnClassesThat().resideInAPackage("com.stripe..")
                .because("Stripe's types belong to the Stripe adapter; the rest of the system sees the PaymentProvider port");
    }

    private static ArchRule sharedKernelIsIndependent(String root) {
        return classes().that().resideInAPackage(root + ".sharedkernel..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "javax..", root + ".sharedkernel..", CORE + "..")
                .because("the kernel takes no module dependency");
    }

    /** A typed id in the domain is named after the aggregate it identifies: {@code InvoiceId} beside {@code Invoice}. */
    private static ArchRule domainIdNamesItsAggregate(String root) {
        return classes().that().resideInAnyPackage(root + ".*.domain..")
                .and().haveSimpleNameEndingWith("Id")
                .and(areTopLevel())
                .should(new ArchCondition<>("have an aggregate of the same name without Id in its package") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        String aggregate = item.getSimpleName().substring(0, item.getSimpleName().length() - "Id".length());
                        boolean found = item.getPackage().getClasses().stream()
                                .anyMatch(other -> other.getSimpleName().equals(aggregate));
                        if (!found) {
                            events.add(SimpleConditionEvent.violated(item,
                                    item.getFullName() + " identifies no aggregate named " + aggregate + " in its package"));
                        }
                    }
                })
                .because("an id is named after the aggregate it identifies, so the pair is found by name");
    }

    /**
     * The features of a domain depend on one another in one direction. A cycle means a type sits in the wrong
     * feature. The application layer is not held to this: the money path legitimately crosses its features.
     */
    private static ArchRule domainFeaturesAreFreeOfCycles(String root) {
        return slices().matching(root + ".(*).domain.(*)..").should().beFreeOfCycles()
                .because("a feature of the domain that needs another and is needed by it belongs with it, or its shared type moves");
    }

    /** The kernel is grouped (id, money, event, runtime, security, util); a new class joins a group or starts one. */
    private static ArchRule sharedKernelClassesInAGroup(String root) {
        return classes().that().resideInAPackage(root + ".sharedkernel")
                .and().doNotHaveSimpleName("package-info")
                .should(new ArchCondition<>("not sit directly in the kernel root") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        events.add(SimpleConditionEvent.violated(item, item.getFullName()
                                + " must not sit directly in the kernel root: put it in a group such as id, money, event, runtime, security or util"));
                    }
                })
                .because("a growing kernel is split by what its classes are for, not extended")
                .allowEmptyShould(true);
    }

    private static DescribedPredicate<JavaClass> areTopLevel() {
        return DescribedPredicate.describe("top-level", javaClass -> javaClass.getEnclosingClass().isEmpty());
    }
}
