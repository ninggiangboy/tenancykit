package dev.ngb.survey.architecture;

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
 * Email's profile of the shared architecture rules plus the rules only it needs.
 *
 * <p>Four contexts, not one: {@code platform} owns the replica tables, {@code content} the templates and renderer,
 * {@code audience} the receivers, groups, suppressions and segments, and {@code sending} the sent-email ledger,
 * campaigns, tracking and the {@code email.send} publisher. That split is what makes
 * {@code context-uses-only-own-tables} true rather than vacuous: a single context could own every table, and then
 * {@code sending} would read templates and receivers directly with no seam.
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
        for (String table : List.of("EventInbox", "ReplicaState", "Outbox")) {
            TABLE_OWNER.put(table, "boot");
        }
        // Surveys, their drafts, immutable versions, translation-free lineage and the tenant settings belong to
        // authoring: the settings and the version document are the same decision, so they move together.
        for (String table : List.of(
                "Surveys", "SurveyDrafts", "SurveyVersions", "QuestionLineage", "SurveySettings")) {
            TABLE_OWNER.put(table, "authoring");
        }
        // Links, visits, page events, responses, typed answers, the counter and uploads belong to collection.
        for (String table : List.of(
                "SurveyLinks", "Visits", "VisitPageEvents", "Responses", "Answers", "SurveyCounters", "Uploads")) {
            TABLE_OWNER.put(table, "collection");
        }
        // Only the export ledger is insight's; it reads everything else through the other contexts' api packages.
        TABLE_OWNER.put("Exports", "insight");
        // Webhook endpoints and deliveries stay in the webhook context, shaped like Billing's and never shared.
        for (String table : List.of("WebhookEndpoints", "WebhookDeliveries")) {
            TABLE_OWNER.put(table, "webhook");
        }
    }

    /** Every use case opens a unit of work; survey has no exception and should never grow one. */
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
                "dev.ngb.survey_jooq",
                TABLE_OWNER,
                Set.of(),
                Set.of(
                        "dev.ngb.billing..", "dev.ngb.mailer..", "dev.ngb.platform..",
                        "dev.ngb.notification..", "dev.ngb.mailroom.."),
                Set.of("*.adapter..persistence..", "boot.."),
                Set.of("dev.ngb.survey_jooq.."),
                NOT_YET_ON_THE_UNIT_OF_WORK,
                Set.of());
    }

    /**
     * What only survey needs. The first two are the seams the plan draws between the contexts: {@code collection} and
     * {@code insight} reach {@code authoring} only through its {@code api} package, and {@code insight} reaches
     * {@code collection} the same way. The third keeps {@code insight} read-only: it writes no table but its own
     * {@code exports}, so a delete goes through {@code collection}'s use case.
     */
    private static List<NamedRule> extras(String root) {
        return List.of(
                new NamedRule("shared-core-is-independent", sharedCoreIsIndependent(root)),
                new NamedRule("datasource-and-transactions-only-in-core-spring", datasourceOnlyInInfrastructure(root)),
                new NamedRule("insight-writes-only-exports", insightWritesOnlyExports(root)));
    }

    /**
     * {@code insight} answers questions about responses; it never changes one. A hard delete belongs to
     * {@code collection}, which owns the rows and the upload objects, so the only write use case insight may hold is
     * the one that queues an export.
     */
    private static ArchRule insightWritesOnlyExports(String root) {
        return classes().that().resideInAPackage(root + ".insight.application..command..")
                .and().haveSimpleNameEndingWith("UseCase")
                .should().haveSimpleNameContaining("Export")
                .because("insight writes no table but its own exports; a delete goes through collection's use case")
                .allowEmptyShould(true);
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
