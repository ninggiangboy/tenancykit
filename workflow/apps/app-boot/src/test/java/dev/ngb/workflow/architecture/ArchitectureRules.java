package dev.ngb.workflow.architecture;

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
 * Workflow's profile of the shared architecture rules plus the rules only it needs.
 *
 * <p>The replica belongs to {@code platform}; {@code definitions} owns the graph, {@code runtime} executes nodes,
 * {@code triggers} receives inbound facts, {@code connections} is the only context that opens an outbound connection,
 * and {@code tasks} owns the human-task inbox. Every business table belongs to exactly one of them, which is what
 * makes {@code context-uses-only-own-tables} mean something as the app fills in.
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
        for (String table : List.of("WorkflowDefinition", "WorkflowVersion")) {
            TABLE_OWNER.put(table, "definitions");
        }
        for (String table : List.of(
                "WorkflowInstance", "WorkflowToken", "WorkflowStepRun", "WorkflowCompensationRun")) {
            TABLE_OWNER.put(table, "runtime");
        }
        for (String table : List.of("WorkflowTrigger", "WorkflowInboundEvent", "WorkflowSchedule")) {
            TABLE_OWNER.put(table, "triggers");
        }
        for (String table : List.of(
                "EventInbox", "ReplicaState", "Outbox", "JobLeases", "JobRuns",
                "WebhookEndpoints", "WebhookDeliveries", "WebhookDeliveryAttempts")) {
            TABLE_OWNER.put(table, "boot");
        }
    }

    /** Every use case opens a unit of work; workflow has no exception and should never grow one. */
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
                "dev.ngb.workflow_jooq",
                TABLE_OWNER,
                Set.of(),
                Set.of("dev.ngb.billing..", "dev.ngb.mailer..", "dev.ngb.platform..", "dev.ngb.tenant.."),
                Set.of("*.adapter..persistence..", "boot.."),
                Set.of("dev.ngb.workflow_jooq.."),
                NOT_YET_ON_THE_UNIT_OF_WORK,
                Set.of());
    }

    /** What only workflow needs: its own shared core and its own wiring package. */
    private static List<NamedRule> extras(String root) {
        return List.of(
                new NamedRule("shared-core-is-independent", sharedCoreIsIndependent(root)),
                new NamedRule("datasource-and-transactions-only-in-core-spring", datasourceOnlyInInfrastructure(root)),
                new NamedRule("outbound-http-only-in-connections", outboundHttpOnlyInConnections(root)));
    }

    /** Only the connections context may open a socket, so the guards and redaction cannot be bypassed elsewhere. */
    private static ArchRule outboundHttpOnlyInConnections(String root) {
        return noClasses().that().resideOutsideOfPackages(root + ".connections..", root + ".boot..")
                .should().dependOnClassesThat().resideInAnyPackage("org.apache.hc..", "java.net.http..", "okhttp3..")
                .because("the connections context owns the guards, the credentials and the redaction of every outbound call");
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
