package dev.ngb.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Platform's architecture rules, parameterised by root package so the same set runs against production code and
 * against the deliberately violating fixtures. They cover what the layered classpaths cannot: cross-context
 * imports, package placement, persistence boundaries and the core's independence.
 */
final class ArchitectureRules {

    record NamedRule(String id, ArchRule rule) {}

    static final String CORE = "dev.ngb.core";
    private static final String JAVA = "java..";
    private static final Set<String> NON_CONTEXT = Set.of("sharedkernel", "boot");
    private static final List<String> LAYERS = List.of("api", "domain", "application", "adapter");

    /** Which context owns each generated table; boot owns the tables every context shares. */
    private static final Map<String, String> TABLE_OWNER = new HashMap<>();

    static {
        for (String table : List.of("Tenants", "TenantDomains", "AppEntitlements")) {
            TABLE_OWNER.put(table, "tenancy");
        }
        for (String table : List.of("Users", "Roles", "RolePermissions", "RoleAssignments", "UserPermissions", "Permissions")) {
            TABLE_OWNER.put(table, "access");
        }
        for (String table : List.of("TenantSystems", "ApiCredentials", "RateLimitBuckets")) {
            TABLE_OWNER.put(table, "integration");
        }
        for (String table : List.of("Outbox", "EventInbox", "AuditLog", "JobLeases", "JobRuns")) {
            TABLE_OWNER.put(table, "boot");
        }
    }

    private ArchitectureRules() {}

    static List<NamedRule> all(String root) {
        return List.of(
                new NamedRule("layer-direction", layerDirection(root)),
                new NamedRule("inner-layer-purity", innerLayerPurity(root)),
                new NamedRule("no-cross-context-import", noCrossContextImport(root)),
                new NamedRule("persistence-types-in-adapter", persistenceTypesInAdapter(root)),
                new NamedRule("api-exposes-no-internals", apiExposesNoInternals(root)),
                new NamedRule("no-jpa", noJpa(root)),
                new NamedRule("role-package-not-layer-root", rolePackageNotLayerRoot(root)),
                new NamedRule("use-cases-and-queries-in-command-or-query", useCasesAndQueriesInCommandOrQuery(root)),
                new NamedRule("ports-in-port-or-query", portsInPortOrQuery(root)),
                new NamedRule("generated-records-in-adapter-persistence", generatedRecordsInAdapterPersistence(root)),
                new NamedRule("context-uses-only-own-tables", contextUsesOnlyItsOwnTables(root)),
                new NamedRule("adapter-classes-in-feature-then-technology", adapterClassesInFeatureThenTechnology(root)),
                new NamedRule("web-does-not-depend-on-persistence", webDoesNotDependOnPersistence(root)),
                new NamedRule("no-dependency-on-another-service", noDependencyOnAnotherService(root)),
                new NamedRule("domain-events-live-in-an-events-holder", domainEventsLiveInAnEventsHolder(root)));
    }

    /** The core rules, pointed at fixtures that spell the core's package like an application's. */
    static List<NamedRule> core(String core) {
        return List.of(
                new NamedRule("core-feign-is-self-contained", coreFeignIsSelfContained(core)),
                new NamedRule("core-is-self-contained", coreIsSelfContained(core)),
                new NamedRule("core-messaging-is-self-contained", coreMessagingIsSelfContained(core)),
                new NamedRule("core-spring-is-self-contained", coreSpringIsSelfContained(core)));
    }

    /** adapter -> application -> domain -> api; never the other way. */
    private static ArchRule layerDirection(String root) {
        ArchRule domainStaysInside = noClasses().that().resideInAPackage(root + ".*.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(root + ".*.application..", root + ".*.adapter..")
                .because("domain must not depend on application or adapter");
        ArchRule applicationStaysInside = noClasses().that().resideInAPackage(root + ".*.application..")
                .should().dependOnClassesThat().resideInAPackage(root + ".*.adapter..")
                .because("application must not depend on adapter");
        return CompositeArchRule.of(domainStaysInside).and(applicationStaysInside);
    }

    /** domain and application know only the JDK, project code and the shared core; no framework, no JPA, no jOOQ. */
    private static ArchRule innerLayerPurity(String root) {
        return classes().that().resideInAnyPackage(root + ".*.domain..", root + ".*.application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(JAVA, root + "..", CORE + "..")
                .because("domain and application are plain Java");
    }

    private static ArchRule noCrossContextImport(String root) {
        return classes().should(new ArchCondition<JavaClass>("not depend on another context outside its api package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String from = contextOf(root, origin.getPackageName());
                if (from == null) {
                    return;
                }
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    String targetPackage = dependency.getTargetClass().getPackageName();
                    String to = contextOf(root, targetPackage);
                    if (to != null && !to.equals(from) && !isApi(root, to, targetPackage)) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        }).because("a context sees another only through its api package");
    }

    /** jOOQ and generated types belong to an adapter; nothing above it may see them. */
    private static ArchRule persistenceTypesInAdapter(String root) {
        return noClasses().that().resideOutsideOfPackages(root + ".*.adapter..", root + ".boot..")
                .should().dependOnClassesThat().resideInAnyPackage("org.jooq..", "dev.ngb.platform_jooq..")
                .because("persistence is an adapter detail");
    }

    /** Another context may use an api package and nothing below it. */
    private static ArchRule apiExposesNoInternals(String root) {
        return noClasses().that().resideInAnyPackage(root + ".*.api..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(root + ".*.domain..", root + ".*.application..", root + ".*.adapter..")
                .because("an api package declares shapes and never reaches into the layers that implement them")
                .allowEmptyShould(true);
    }

    private static ArchRule noJpa(String root) {
        return noClasses().that().resideInAnyPackage(root + "..", CORE + "..")
                .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..")
                .because("persistence is jOOQ only");
    }

    private static ArchRule rolePackageNotLayerRoot(String root) {
        String[] roots = LAYERS.stream().map(layer -> root + ".*." + layer).toArray(String[]::new);
        return classes().that().resideInAnyPackage(roots)
                .and().doNotHaveSimpleName("package-info")
                .should(new ArchCondition<>("not sit directly in a layer root package") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        events.add(SimpleConditionEvent.violated(
                                item, item.getFullName() + " must not sit directly in a layer root package"));
                    }
                })
                .because("a context groups packages by feature and then by role")
                .allowEmptyShould(true);
    }

    private static ArchRule useCasesAndQueriesInCommandOrQuery(String root) {
        return classes().that().resideInAnyPackage(root + ".*.application..")
                .should(new ArchCondition<>("be in a command or query package") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        String name = item.getSimpleName();
                        String packageName = item.getPackageName();
                        if (name.endsWith("UseCase") && !hasSegment(packageName, "command")) {
                            events.add(SimpleConditionEvent.violated(
                                    item, item.getFullName() + " is a use case but does not live in a ..command.. package"));
                        }
                        if (name.endsWith("Queries") && !hasSegment(packageName, "query")) {
                            events.add(SimpleConditionEvent.violated(
                                    item, item.getFullName() + " is a query class but does not live in a ..query.. package"));
                        }
                    }
                })
                .because("the command side owns use cases and the query side owns query classes");
    }

    /** An application interface is a port (port/) or a read port (query/); nothing else lives in an interface. */
    private static ArchRule portsInPortOrQuery(String root) {
        return classes().that().resideInAnyPackage(root + ".*.application..")
                .and().areInterfaces()
                .should(new ArchCondition<>("be in a port or query package") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        String packageName = item.getPackageName();
                        if (!hasSegment(packageName, "port") && !hasSegment(packageName, "query")) {
                            events.add(SimpleConditionEvent.violated(
                                    item, item.getFullName() + " is an interface outside a ..port.. or ..query.. package"));
                        }
                    }
                })
                .because("an interface in the application layer is a port the adapter implements");
    }

    private static ArchRule generatedRecordsInAdapterPersistence(String root) {
        return noClasses().that().resideOutsideOfPackages(root + ".*.adapter..persistence..", root + ".boot..")
                .should().dependOnClassesThat().resideInAPackage("dev.ngb.platform_jooq.tables.records..")
                .because("a generated record lives in an adapter persistence package next to the repository that uses it");
    }

    /** A context's adapters use only the tables it owns; the shared tables belong to boot. */
    private static ArchRule contextUsesOnlyItsOwnTables(String root) {
        return classes().that().resideInAnyPackage(root + ".*.adapter..", root + ".boot..")
                .should(new ArchCondition<>("use only the tables its own context owns") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        String context = contextOf(root, item.getPackageName());
                        if (context == null) {
                            return;
                        }
                        for (var access : item.getFieldAccessesFromSelf()) {
                            if (!"dev.ngb.platform_jooq.Tables".equals(access.getTarget().getOwner().getName())) {
                                continue;
                            }
                            String owner = TABLE_OWNER.get(tableName(access.getTarget().getName()));
                            if (owner != null && !owner.equals(context)) {
                                events.add(SimpleConditionEvent.violated(
                                        item, item.getFullName() + " uses " + access.getTarget().getName() + ", owned by " + owner));
                            }
                        }
                    }
                })
                .because("each context persists only its own tables");
    }

    private static ArchRule adapterClassesInFeatureThenTechnology(String root) {
        return classes().that().resideInAnyPackage(root + ".*.adapter..")
                .and().doNotHaveSimpleName("package-info")
                .and(areTopLevel())
                .should(new ArchCondition<JavaClass>("sit in adapter.<feature>.<technology>") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        String packageName = item.getPackageName();
                        int adapter = packageName.indexOf(".adapter");
                        String below = adapter < 0 ? "" : packageName.substring(adapter + ".adapter".length());
                        int segments = below.isEmpty() ? 0 : below.substring(1).split("\\.").length;
                        if (segments < 2) {
                            events.add(SimpleConditionEvent.violated(item,
                                    item.getFullName() + " is not in a feature package and then a technology package below adapter"));
                        }
                    }
                })
                .because("an adapter is grouped by feature first and by technology second");
    }

    private static ArchRule webDoesNotDependOnPersistence(String root) {
        return noClasses().that().resideInAnyPackage(root + ".*.adapter..web..")
                .should().dependOnClassesThat().resideInAnyPackage(root + ".*.adapter..persistence..")
                .because("web and persistence are separate technologies");
    }

    private static ArchRule domainEventsLiveInAnEventsHolder(String root) {
        return classes().that().resideInAnyPackage(root + ".*.domain..")
                .and().areNotInterfaces()
                .should(new ArchCondition<>("be nested in a class whose name ends in Events when it implements DomainEvent") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        boolean isEvent = item.getAllRawInterfaces().stream()
                                .anyMatch(api -> api.getSimpleName().equals("DomainEvent"));
                        if (!isEvent) {
                            return;
                        }
                        boolean held = item.getEnclosingClass().map(holder -> holder.getSimpleName().endsWith("Events")).orElse(false);
                        if (!held) {
                            events.add(SimpleConditionEvent.violated(
                                    item, item.getFullName() + " is a domain event but is not nested in an *Events class"));
                        }
                    }
                })
                .because("one place per feature lists what it announces");
    }

    /**
     * The plain-Java core depends on nothing of an application's. The framework modules are excluded on purpose: the
     * Spring implementation, the messaging infrastructure, the replica, the snapshot sync, the suite policy and the
     * Feign client all ship framework types.
     */
    private static ArchRule coreIsSelfContained(String core) {
        return classes().that().resideInAnyPackage(core + "..")
                .and().resideOutsideOfPackages(
                        core + ".spring..", core + ".messaging..", core + ".replica..", core + ".sync..",
                        core + ".suite..", core + ".feign..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(JAVA, core + "..")
                .because("the core is shared by every application build and depends on nothing of theirs");
    }

    /** The shared Feign client sees the JDK, the core, Feign, Spring's MVC annotations and Jackson; never a service. */
    private static ArchRule coreFeignIsSelfContained(String core) {
        return classes().that().resideInAPackage(core + ".feign..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(JAVA, core + "..", "feign..", "org.springframework..", "tools.jackson..")
                .because("core-feign is shared by every service build and names no service type");
    }

    private static ArchRule coreMessagingIsSelfContained(String core) {
        return classes().that().resideInAPackage(core + ".messaging..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(JAVA, "javax..", "jakarta..", core + "..", "org.springframework..", "org.jooq..",
                        "tools.jackson..", "com.fasterxml..", "io.micrometer..", "org.slf4j..", "com.rabbitmq..", "com.nimbusds..")
                .because("core-messaging sees the JDK, the core, jOOQ, Spring, Jackson, Micrometer, SLF4J and the AMQP client only");
    }

    private static ArchRule coreSpringIsSelfContained(String core) {
        return classes().that().resideInAPackage(core + ".spring..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(JAVA, "javax..", "jakarta..", core + "..", "org.springframework..")
                .because("the core's Spring implementation sees the JDK, the core and Spring only");
    }

    /** Another service's code is never named here: services meet through the broker, the replica and signed tokens. */
    private static ArchRule noDependencyOnAnotherService(String root) {
        return noClasses().that().resideInAPackage(root + "..")
                .should().dependOnClassesThat().resideInAnyPackage("dev.ngb.billing..", "dev.ngb.tenant..")
                .because("services share only the core and the contracts, never each other's code");
    }

    private static DescribedPredicate<JavaClass> areTopLevel() {
        return DescribedPredicate.describe("top-level", javaClass -> javaClass.getEnclosingClass().isEmpty());
    }

    private static String tableName(String constant) {
        StringBuilder name = new StringBuilder();
        for (String part : constant.split("_")) {
            name.append(part.charAt(0)).append(part.substring(1).toLowerCase());
        }
        return name.toString();
    }

    private static String contextOf(String root, String packageName) {
        String prefix = root + ".";
        if (!packageName.startsWith(prefix)) {
            return null;
        }
        String rest = packageName.substring(prefix.length());
        int dot = rest.indexOf('.');
        String first = dot < 0 ? rest : rest.substring(0, dot);
        return NON_CONTEXT.contains(first) ? null : first;
    }

    private static boolean isApi(String root, String context, String packageName) {
        String api = root + "." + context + ".api";
        return packageName.equals(api) || packageName.startsWith(api + ".");
    }

    private static boolean hasSegment(String packageName, String segment) {
        for (String part : packageName.split("\\.")) {
            if (part.equals(segment)) {
                return true;
            }
        }
        return false;
    }
}
