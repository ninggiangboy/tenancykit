package dev.ngb.core.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.elements.GivenClassesConjunction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The architecture rule set every service in the suite runs, parameterised by an {@link Profile} so the same rules
 * run against production code and against the deliberately violating fixtures. ArchUnit owns layer direction,
 * purity, package placement, persistence boundaries and read/write separation; Spring Modulith owns module
 * verification where a service uses it.
 *
 * <p>Package convention: {@code <root>.<context>.{domain,application,adapter,api}}. A context is any direct
 * sub-package of the root except the context's shared kernel and the wiring package. Inside a context, packages are
 * grouped by feature and then by role: {@code command} (use cases), {@code query} (read side), {@code port}
 * (outbound interfaces), {@code service} (collaborators of use cases) and {@code exception}. An adapter is grouped
 * the same way: {@code adapter.<feature>.<technology>}.
 *
 * <p>The rules a service needs beyond this set stay in that service's own rule class and are documented there.
 */
public final class ArchitectureRules {

    /** The shared core lives in its own Gradle build; every layer may name it where it may name the kernel. */
    public static final String CORE = "dev.ngb.core";

    /** The JDK: {@code java.*} plus the {@code javax.*} packages that ship with it. */
    private static final List<String> JDK = List.of("java..", "javax..");

    /** One profile per service: what the shared rules need to know about the application they guard. */
    public record Profile(
            String root,
            String component,
            String kernel,
            String jooq,
            Map<String, String> tableOwner,
            Set<String> allowedQueryDomainTypes,
            Set<String> otherServiceRoots,
            Set<String> persistenceVisibleIn,
            Set<String> persistencePackages,
            Set<String> unitOfWorkAllowlist,
            Set<String> writeSideReadAllowlist) {

        public Profile {
            tableOwner = Map.copyOf(tableOwner);
            allowedQueryDomainTypes = Set.copyOf(allowedQueryDomainTypes);
            otherServiceRoots = Set.copyOf(otherServiceRoots);
            persistenceVisibleIn = Set.copyOf(persistenceVisibleIn);
            persistencePackages = Set.copyOf(persistencePackages);
            unitOfWorkAllowlist = Set.copyOf(unitOfWorkAllowlist);
            writeSideReadAllowlist = Set.copyOf(writeSideReadAllowlist);
        }
    }

    /** A rule and the id it reports under; the ids are the contract the meta tests compare across services. */
    public record NamedRule(String id, ArchRule rule) {}

    private static final List<String> LAYERS = List.of("domain", "application", "adapter", "api");

    /** Interfaces in application whose name ends in one of these are outbound (write-side) ports. */
    private static final Set<String> OUTBOUND_PORT_SUFFIXES = Set.of(
            "Repository", "Port", "Provider", "Access", "Sender", "Signer", "Cipher", "Writer", "Store", "Keys", "Limiter",
            "Units", "Registry", "Verifier");

    /** The role packages of the application layer; every application class sits in exactly one of them. */
    private static final Set<String> APPLICATION_ROLES = Set.of("command", "query", "port", "service", "exception");

    private ArchitectureRules() {}

    /** Every rule a service runs, in the order the meta test reports them. */
    public static List<NamedRule> all(Profile profile) {
        return List.of(
                new NamedRule("layer-direction", layerDirection(profile)),
                new NamedRule("inner-layer-purity", innerLayerPurity(profile)),
                new NamedRule("no-cross-context-import", noCrossContextImport(profile)),
                new NamedRule("persistence-types-in-adapter", persistenceTypesInAdapter(profile)),
                new NamedRule("api-exposes-no-internals", apiExposesNoInternals(profile)),
                new NamedRule("no-jpa", noJpa()),
                new NamedRule("no-dependency-on-another-service", noDependencyOnAnotherService(profile)),
                new NamedRule("role-package-not-layer-root", rolePackageNotLayerRoot(profile)),
                new NamedRule("use-cases-and-queries-in-command-or-query", useCasesAndQueriesInCommandOrQuery(profile)),
                new NamedRule("ports-in-port-or-query", portsInPortOrQuery(profile)),
                new NamedRule("application-classes-in-a-role-package", applicationClassesInARolePackage(profile)),
                new NamedRule("command-package-holds-only-use-cases", commandPackageHoldsOnlyUseCases(profile)),
                new NamedRule("port-package-holds-no-implementations", portPackageHoldsNoImplementations(profile)),
                new NamedRule("use-case-opens-a-unit-of-work", useCaseOpensAUnitOfWork(profile)),
                new NamedRule("adapter-classes-in-feature-then-technology", adapterClassesInFeatureThenTechnology(profile)),
                new NamedRule("api-implementations-are-services", apiImplementationsAreServices(profile)),
                new NamedRule("web-does-not-depend-on-persistence", webDoesNotDependOnPersistence(profile)),
                new NamedRule("web-package-holds-no-persistence", webPackageHoldsNoPersistence(profile)),
                new NamedRule("generated-records-in-adapter-persistence", generatedRecordsInAdapterPersistence(profile)),
                new NamedRule("context-uses-only-own-tables", contextUsesOnlyItsOwnTables(profile)),
                new NamedRule("repository-pair-shares-a-package", repositoryPairSharesAPackage(profile)),
                new NamedRule("write-side-does-not-depend-on-query", writeSideDoesNotDependOnQuery(profile)),
                new NamedRule("read-side-does-not-depend-on-write", readSideDoesNotDependOnWrite(profile)),
                new NamedRule("query-side-uses-no-domain-aggregates", querySideUsesNoDomainAggregates(profile)),
                new NamedRule("write-ports-serve-no-reads", writePortsServeNoReads(profile)),
                new NamedRule("domain-events-live-in-an-events-holder", domainEventsLiveInAnEventsHolder(profile)),
                new NamedRule("every-endpoint-declares-a-caller", everyEndpointDeclaresACaller(profile)));
    }

    /**
     * The test-package rule, run over an import of the test classes. It is separate from {@link #all(Profile)}
     * because it needs the packages production occupies, which only the test can work out from the production
     * import: a test that sits in a layer package of the project belongs beside the class it proves.
     *
     * @param productionPackages every package a production class of this service occupies
     */
    public static NamedRule testsMirrorProductionPackages(Profile profile, Set<String> productionPackages) {
        Set<String> packages = Set.copyOf(productionPackages);
        return new NamedRule("tests-mirror-production-packages",
                classes().that(areTestsInALayerPackage(profile.root))
                        .should(new ArchCondition<>("live in a package a production class occupies") {
                            @Override
                            public void check(JavaClass item, ConditionEvents events) {
                                if (!packages.contains(item.getPackageName())) {
                                    events.add(SimpleConditionEvent.violated(item, item.getFullName()
                                            + " proves a class of another package: put it in the package its production class lives in"));
                                }
                            }
                        })
                        .because("a test is found next to the class it proves, so a package production never had cannot appear")
                        .allowEmptyShould(true));
    }

    /**
     * The rules that guard the shared core. They are separate from the application rules because the core is a
     * different build with a different vocabulary: it knows no context, no layer and no framework.
     *
     * @param core the core's root package, so the same rules run against the production code and the fixtures
     */
    public static List<NamedRule> core(String core) {
        return List.of(
                new NamedRule("core-feign-is-self-contained", coreFeignIsSelfContained(core)),
                new NamedRule("core-is-self-contained", coreIsSelfContained(core)),
                new NamedRule("core-messaging-is-self-contained", coreMessagingIsSelfContained(core)),
                new NamedRule("core-spring-is-self-contained", coreSpringIsSelfContained(core)),
                new NamedRule("core-visitor-is-self-contained", coreVisitorIsSelfContained(core)),
                new NamedRule("core-webhook-is-self-contained", coreWebhookIsSelfContained(core)));
    }

    /** adapter -> application -> domain; never the other way. */
    private static ArchRule layerDirection(Profile profile) {
        String root = profile.root();
        ArchRule domainStaysInside = noClasses().that().resideInAPackage(root + ".*.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(root + ".*.application..", root + ".*.adapter..")
                .because("domain must not depend on application or adapter");
        ArchRule applicationStaysInside = noClasses().that().resideInAPackage(root + ".*.application..")
                .should().dependOnClassesThat().resideInAPackage(root + ".*.adapter..")
                .because("application must not depend on adapter");
        return CompositeArchRule.of(domainStaysInside).and(applicationStaysInside);
    }

    /** domain and application know only the JDK, project code and the shared core; no framework, no JPA, no jOOQ. */
    private static ArchRule innerLayerPurity(Profile profile) {
        String root = profile.root();
        return classes().that().resideInAnyPackage(root + ".*.domain..", root + ".*.application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(concat(JDK, root + "..", CORE + ".."))
                .because("domain and application are plain Java");
    }

    /** A context reaches another only through that other context's api package. */
    private static ArchRule noCrossContextImport(Profile profile) {
        return classes().should(notReachIntoOtherContexts(profile))
                .because("a " + profile.component() + " may use another only through its api package");
    }

    /** Persistence types live in an adapter persistence package; nothing above it may see them. */
    private static ArchRule persistenceTypesInAdapter(Profile profile) {
        return noClasses().that().resideOutsideOfPackages(packagesBelow(profile, profile.persistenceVisibleIn()))
                .should().dependOnClassesThat().resideInAnyPackage(profile.persistencePackages().toArray(String[]::new))
                .because("persistence is an adapter detail");
    }

    /** The api package is the contract: it may name only the JDK, the kernel, the shared core and other api packages. */
    private static ArchRule apiExposesNoInternals(Profile profile) {
        String root = profile.root();
        GivenClassesConjunction api = classes().that().resideInAPackage(root + ".*.api..")
                .and().doNotHaveSimpleName("package-info");
        return api.should().onlyDependOnClassesThat()
                .resideInAnyPackage(concat(JDK, root + "." + profile.kernel() + "..", CORE + "..", root + ".*.api.."))
                .because("the api package must not leak domain or adapter types")
                .allowEmptyShould(true);
    }

    private static ArchRule noJpa() {
        return noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.persistence..", "javax.persistence..", "org.hibernate..", "org.springframework.data.jpa..")
                .because("persistence is jOOQ only");
    }

    /** Services meet through the broker, the replica and signed tokens; none reaches into another service's code. */
    private static ArchRule noDependencyOnAnotherService(Profile profile) {
        return noClasses().that().resideInAPackage(profile.root() + "..")
                .should().dependOnClassesThat().resideInAnyPackage(profile.otherServiceRoots().toArray(String[]::new))
                .because("services share only the core and the contracts, never each other's code");
    }

    /**
     * Inside a context no class sits directly in a layer root package: every package is grouped by feature
     * and then by role, so a reader sees one job per package. {@code package-info} declarations stay.
     */
    private static ArchRule rolePackageNotLayerRoot(Profile profile) {
        String root = profile.root();
        List<String> roots = new ArrayList<>();
        for (String layer : LAYERS) {
            roots.add(root + ".*." + layer);
        }
        return classes().that().resideInAnyPackage(roots.toArray(String[]::new))
                .and().doNotHaveSimpleName("package-info")
                .should(never("must not sit directly in a layer root package: group it under a feature and role package"))
                .because("a " + profile.component() + " groups packages by feature and then by role")
                .allowEmptyShould(true);
    }

    /** A use case belongs to the command side and a query class to the query side. */
    private static ArchRule useCasesAndQueriesInCommandOrQuery(Profile profile) {
        return classes().that().resideInAnyPackage(profile.root() + ".*.application..")
                .should(new ArchCondition<>("be in a command or query package") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        String name = item.getSimpleName();
                        String packageName = item.getPackageName();
                        if (name.endsWith("UseCase") && !hasSegment(packageName, "command")) {
                            events.add(SimpleConditionEvent.violated(item,
                                    item.getFullName() + " is a use case but does not live in a ..command.. package"));
                        }
                        if (name.endsWith("Queries") && !hasSegment(packageName, "query")) {
                            events.add(SimpleConditionEvent.violated(item,
                                    item.getFullName() + " is a query class but does not live in a ..query.. package"));
                        }
                    }
                })
                .because("the command side owns use cases and the query side owns query classes");
    }

    /** Outbound write ports live in port/, read ports in query/. A read port is named *ReadRepository. */
    private static ArchRule portsInPortOrQuery(Profile profile) {
        return classes().that().resideInAnyPackage(profile.root() + ".*.application..")
                .and().areInterfaces()
                .should(new ArchCondition<>("be in a port or query package") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        String name = item.getSimpleName();
                        String packageName = item.getPackageName();
                        if (name.endsWith("ReadRepository")) {
                            if (!hasSegment(packageName, "query")) {
                                events.add(SimpleConditionEvent.violated(item,
                                        item.getFullName() + " is a read port but does not live in a ..query.. package"));
                            }
                        } else if (isOutboundPort(name) && !hasSegment(packageName, "port")) {
                            events.add(SimpleConditionEvent.violated(item,
                                    item.getFullName() + " is a write port but does not live in a ..port.. package"));
                        }
                    }
                })
                .because("write ports live with the command side and read ports with the query side");
    }

    /**
     * Every application class sits in a command, query, port, service or exception package below its feature, so
     * a reader can tell a use case from a helper, a port and an error by the package alone.
     */
    private static ArchRule applicationClassesInARolePackage(Profile profile) {
        return classes().that().resideInAnyPackage(profile.root() + ".*.application..")
                .and().doNotHaveSimpleName("package-info")
                .and(areTopLevel())
                .should(new ArchCondition<>("sit in a command, query, port, service or exception package") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        boolean inRole = false;
                        for (String role : APPLICATION_ROLES) {
                            inRole |= hasSegment(item.getPackageName(), role);
                        }
                        if (!inRole) {
                            events.add(SimpleConditionEvent.violated(item,
                                    item.getFullName() + " is not in a command, query, port, service or exception package"));
                        }
                    }
                })
                .because("an application package has one job: use cases, read side, ports, services or errors");
    }

    /** The command package holds use cases only; collaborators of a use case live in service. */
    private static ArchRule commandPackageHoldsOnlyUseCases(Profile profile) {
        return classes().that().resideInAnyPackage(profile.root() + ".*.application..")
                .and().doNotHaveSimpleName("package-info")
                .and(areTopLevel())
                .should(new ArchCondition<>("be a *UseCase when it sits in a command package") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        if (hasSegment(item.getPackageName(), "command") && !item.getSimpleName().endsWith("UseCase")) {
                            events.add(SimpleConditionEvent.violated(item,
                                    item.getFullName() + " is in a command package but is not named *UseCase"));
                        }
                    }
                })
                .because("a command package holds use cases; a helper belongs in a service package");
    }

    /** A port package declares interfaces and their data carriers; an implementation belongs to the adapter. */
    private static ArchRule portPackageHoldsNoImplementations(Profile profile) {
        return classes().that().resideInAnyPackage(profile.root() + ".*.application..")
                .and().doNotHaveSimpleName("package-info")
                .and(areTopLevel())
                .should(new ArchCondition<>("be an interface, record or enum when it sits in a port package") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        if (hasSegment(item.getPackageName(), "port")
                                && !(item.isInterface() || item.isRecord() || item.isEnum())) {
                            events.add(SimpleConditionEvent.violated(item,
                                    item.getFullName() + " is a class in a port package; ports are interfaces"));
                        }
                    }
                })
                .because("a port package declares what the application needs; a service class belongs in service");
    }

    /**
     * A use case changes state inside one transaction it opens itself, so a retry or a failure never leaves half a
     * change behind. A use case listed in the profile's allowlist is waiting for a dated follow-up and is the only
     * place this rule may be bent.
     */
    private static ArchRule useCaseOpensAUnitOfWork(Profile profile) {
        return classes().that().resideInAnyPackage(profile.root() + "..application..")
                .and().haveSimpleNameEndingWith("UseCase")
                .and().areNotInterfaces()
                .and(DescribedPredicate.not(named(profile.unitOfWorkAllowlist(), "not yet on the unit of work")))
                .should().dependOnClassesThat().haveSimpleName("UnitOfWork")
                .because("a use case commits in one transaction it opens itself")
                .allowEmptyShould(true);
    }

    /** An adapter class sits in a feature package and then a technology package: adapter.&lt;feature&gt;.&lt;technology&gt;. */
    private static ArchRule adapterClassesInFeatureThenTechnology(Profile profile) {
        return classes().that().resideInAnyPackage(profile.root() + ".*.adapter..")
                .and().doNotHaveSimpleName("package-info")
                .and(areTopLevel())
                .should(new ArchCondition<>("sit in adapter.<feature>.<technology>") {
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

    /**
     * A class that implements a public api interface for other contexts, other than a use case, is the
     * {@code <Interface>Service} of its feature and lives in the service package.
     */
    private static ArchRule apiImplementationsAreServices(Profile profile) {
        String root = profile.root();
        return classes().that().resideInAnyPackage(root + ".*.application..")
                .and(areTopLevel())
                .and().areNotInterfaces()
                .and().haveSimpleNameNotEndingWith("UseCase")
                .should(new ArchCondition<>("be <Interface>Service in a service package when it implements an api interface") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (JavaClass api : item.getAllRawInterfaces()) {
                            if (!api.getPackageName().startsWith(root + ".") || !isApiPackage(root, api.getPackageName())) {
                                continue;
                            }
                            String expected = api.getSimpleName() + "Service";
                            if (!item.getSimpleName().equals(expected) || !hasSegment(item.getPackageName(), "service")) {
                                events.add(SimpleConditionEvent.violated(item, item.getFullName() + " implements the api interface "
                                        + api.getSimpleName() + " but is not " + expected + " in a service package"));
                            }
                        }
                    }
                })
                .because("one convention for the class that serves another context: <Interface>Service in service");
    }

    /** A controller reaches the data through the application layer; a shared wire format lives in its own package. */
    private static ArchRule webDoesNotDependOnPersistence(Profile profile) {
        return noClasses().that().resideInAnyPackage(profile.root() + ".*.adapter..web..")
                .should().dependOnClassesThat().resideInAnyPackage(profile.root() + ".*.adapter..persistence..")
                .because("web and persistence are separate technologies; a shape both use belongs in its own package, such as json")
                .allowEmptyShould(true);
    }

    /**
     * A web package speaks HTTP. A persistence type in it, such as a jOOQ record or a JdbcTemplate, means the
     * technology moved into the adapter's edge, where a controller ends up owning the database.
     */
    private static ArchRule webPackageHoldsNoPersistence(Profile profile) {
        List<String> persistence = new ArrayList<>(List.of("org.jooq..", "org.springframework.jdbc.."));
        persistence.add(profile.jooq() + "..");
        return noClasses().that().resideInAnyPackage(profile.root() + ".*.adapter..web..")
                .should().dependOnClassesThat().resideInAnyPackage(persistence.toArray(String[]::new))
                .because("a web package holds no jOOQ or JdbcTemplate type; persistence is its own adapter package")
                .allowEmptyShould(true);
    }

    /** A generated jOOQ record is an adapter detail and lives next to the repository that uses it. */
    private static ArchRule generatedRecordsInAdapterPersistence(Profile profile) {
        String root = profile.root();
        return noClasses().that().resideOutsideOfPackages(root + ".*.adapter..persistence..", root + ".boot..")
                .should().dependOnClassesThat().resideInAPackage(profile.jooq() + ".tables.records..")
                .because("a generated record lives in an adapter persistence package next to the repository that uses it");
    }

    /**
     * A context's adapters use only the tables it owns. The outbox, inbox, audit log and rate limit buckets are
     * shared infrastructure and belong to boot.
     */
    private static ArchRule contextUsesOnlyItsOwnTables(Profile profile) {
        return classes().that().resideInAnyPackage(profile.root() + ".*.adapter..", profile.root() + ".boot..")
                .should(new ArchCondition<>("use only the tables its own " + profile.component() + " owns") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        String context = owningContextOf(profile, item.getPackageName());
                        if (context == null) {
                            return;
                        }
                        for (var access : item.getFieldAccessesFromSelf()) {
                            if (!(profile.jooq() + ".Tables").equals(access.getTarget().getOwner().getName())) {
                                continue;
                            }
                            String table = tableName(access.getTarget().getName());
                            String owner = profile.tableOwner().get(table);
                            if (owner != null && !owner.equals(context)) {
                                events.add(SimpleConditionEvent.violated(item,
                                        item.getFullName() + " uses " + table + ", owned by " + owner));
                            }
                        }
                    }
                })
                .because("each " + profile.component() + " persists only its own tables");
    }

    /** A feature's write repository and read repository sit side by side, so one package holds one feature's persistence. */
    private static ArchRule repositoryPairSharesAPackage(Profile profile) {
        return classes().that().resideInAnyPackage(profile.root() + ".*.adapter..persistence..")
                .and().haveSimpleNameStartingWith("Jooq").and().haveSimpleNameEndingWith("Repository")
                .should(new ArchCondition<>("sit in the same package as its feature's write repository") {
                    private final Map<String, JavaClass> byContextAndName = new HashMap<>();

                    @Override
                    public void init(Collection<JavaClass> allObjectsToTest) {
                        for (JavaClass candidate : allObjectsToTest) {
                            byContextAndName.put(
                                    owningContextOf(profile, candidate.getPackageName()) + "/" + candidate.getSimpleName(),
                                    candidate);
                        }
                    }

                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        String name = item.getSimpleName();
                        if (!name.endsWith("ReadRepository")) {
                            return;
                        }
                        String writeName = name.substring(0, name.length() - "ReadRepository".length()) + "Repository";
                        JavaClass write =
                                byContextAndName.get(owningContextOf(profile, item.getPackageName()) + "/" + writeName);
                        if (write != null && !write.getPackageName().equals(item.getPackageName())) {
                            events.add(SimpleConditionEvent.violated(item,
                                    item.getFullName() + " is not in the same package as " + write.getFullName()));
                        }
                    }
                })
                .because("the write and read repository of one feature share a package")
                .allowEmptyShould(true);
    }

    /**
     * The command side never reads through the query side; a use case that must read decides with its own port. A
     * use case listed in the profile's allowlist is waiting for a dated follow-up.
     */
    private static ArchRule writeSideDoesNotDependOnQuery(Profile profile) {
        String root = profile.root();
        return noClasses().that().resideInAnyPackage(root + ".*..command..", root + ".*..port..")
                .and(DescribedPredicate.not(named(profile.writeSideReadAllowlist(), "temporarily allowlisted")))
                .should().dependOnClassesThat().resideInAnyPackage(root + ".*..query..")
                .because("the write side decides with its own ports and never through a read model")
                .allowEmptyShould(true);
    }

    /**
     * The query side names views and read ports only. It never reaches into the command side and never depends
     * on a write port, so a read cannot accidentally drive a write.
     */
    private static ArchRule readSideDoesNotDependOnWrite(Profile profile) {
        String root = profile.root();
        ArchRule byPackage = noClasses().that().resideInAnyPackage(root + ".*..query..")
                .should().dependOnClassesThat().resideInAnyPackage(root + ".*..command..", root + ".*..port..")
                .because("a query class reads through a read port and never through the write side")
                .allowEmptyShould(true);
        ArchRule byName = noClasses().that().resideInAnyPackage(root + "..")
                .and().haveSimpleNameStartingWith("Jooq").and().haveSimpleNameEndingWith("ReadRepository")
                .should().dependOnClassesThat().resideInAnyPackage(root + ".*..command..", root + ".*..port..")
                .because("a read repository selects into a view and never into the write side")
                .allowEmptyShould(true);
        return CompositeArchRule.of(byPackage).and(byName).allowEmptyShould(true);
    }

    /**
     * A read model is never a domain aggregate. Should a read need a value object or an enum, the explicit
     * allowlist records it for review instead of letting the domain leak in silently.
     */
    private static ArchRule querySideUsesNoDomainAggregates(Profile profile) {
        return classes().that().resideInAnyPackage(profile.root() + ".*..query..")
                .should(new ArchCondition<>("not use a domain aggregate") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                            JavaClass target = dependency.getTargetClass();
                            if (isDomainPackage(profile.root(), target.getPackageName())
                                    && !target.isEnum()
                                    && !profile.allowedQueryDomainTypes().contains(target.getFullName())) {
                                events.add(SimpleConditionEvent.violated(item,
                                        item.getFullName() + " depends on domain type " + target.getFullName()));
                            }
                        }
                    }
                })
                .because("a read model is shaped for its caller and is never a domain aggregate");
    }

    /** The write side never serves reads: a port has no list, page or search method. */
    private static ArchRule writePortsServeNoReads(Profile profile) {
        return noMethods().that().areDeclaredInClassesThat().resideInAnyPackage(profile.root() + ".*..port..")
                .and().areDeclaredInClassesThat().areInterfaces()
                .should().haveNameMatching("(list|page|search).*")
                .because("the write side never serves reads; a list, page or search method belongs to a read port")
                .allowEmptyShould(true);
    }

    /**
     * A domain event is a record nested in the {@code <Aggregate>Events} class of its feature, so the events a
     * feature announces are found in one place. A noun that only contains "Event" is a domain term, not an
     * announcement, and does not implement DomainEvent.
     */
    private static ArchRule domainEventsLiveInAnEventsHolder(Profile profile) {
        return classes().that().resideInAnyPackage(profile.root() + ".*.domain..")
                .and().areNotInterfaces()
                .should(new ArchCondition<>("be nested in a class whose name ends in Events when it implements DomainEvent") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        boolean isEvent = item.getAllRawInterfaces().stream()
                                .anyMatch(api -> api.getSimpleName().equals("DomainEvent"));
                        if (!isEvent) {
                            return;
                        }
                        boolean held = item.getEnclosingClass()
                                .map(holder -> holder.getSimpleName().endsWith("Events"))
                                .orElse(false);
                        if (!held) {
                            events.add(SimpleConditionEvent.violated(item,
                                    item.getFullName() + " is a domain event but is not nested in an *Events class"));
                        }
                    }
                })
                .because("one place per feature lists what it announces");
    }

    /**
     * The shared core is shared by more than one application build, so it may name only the JDK and itself. The
     * framework modules (the Spring implementation, the messaging infrastructure, the replica, the snapshot sync,
     * the suite policy and the Feign client) see the framework types they are built from; none may reach back into
     * an application.
     */
    private static ArchRule coreIsSelfContained(String core) {
        return classes().that().resideInAnyPackage(core + "..")
                .and().resideOutsideOfPackages(
                        core + ".spring..", core + ".messaging..", core + ".replica..", core + ".sync..",
                        core + ".suite..", core + ".feign..", core + ".visitor..", core + ".webhook..", core + ".openapi..", core + ".arch..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(concat(JDK, core + ".."))
                .because("the core is shared by every application build and depends on nothing of theirs")
                .allowEmptyShould(true);
    }

    /** The shared Feign client sees the JDK, the core, Feign, Spring's MVC annotations and Jackson; never a service. */
    private static ArchRule coreFeignIsSelfContained(String core) {
        return classes().that().resideInAPackage(core + ".feign..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(concat(JDK, core + "..", "feign..", "org.springframework..", "tools.jackson.."))
                .because("core-feign is shared by every service build and names no service type")
                .allowEmptyShould(true);
    }

    private static ArchRule coreMessagingIsSelfContained(String core) {
        return classes().that().resideInAPackage(core + ".messaging..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(concat(JDK, "jakarta..", core + "..", "org.springframework..", "org.jooq..",
                        "tools.jackson..", "com.fasterxml..", "io.micrometer..", "org.slf4j..", "com.rabbitmq..", "com.nimbusds.."))
                .because("core-messaging sees the JDK, the core, jOOQ, Spring, Jackson, Micrometer, SLF4J and the AMQP client only")
                .allowEmptyShould(true);
    }

    private static ArchRule coreSpringIsSelfContained(String core) {
        return classes().that().resideInAPackage(core + ".spring..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(concat(JDK, "jakarta..", core + "..", "org.springframework..", "org.jooq.."))
                .because("the core's Spring implementation sees the JDK, the core, Spring and the jOOQ audit log only")
                .allowEmptyShould(true);
    }

    /**
     * The shared visitor library sees the JDK, the core, the public-suffix list and the MaxMind reader; never a service,
     * never Spring, because the redirect path and a worker both use it.
     */
    private static ArchRule coreVisitorIsSelfContained(String core) {
        return classes().that().resideInAPackage(core + ".visitor..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(concat(JDK, core + "..", "org.apache.hc..", "com.maxmind.."))
                .because("core-visitor is shared by the apps that measure traffic and names no app's type")
                .allowEmptyShould(true);
    }

    /** The shared webhook mechanics see the JDK, the core, HttpClient 5 and SLF4J; never a service, never Spring. */
    private static ArchRule coreWebhookIsSelfContained(String core) {
        return classes().that().resideInAPackage(core + ".webhook..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(concat(JDK, core + "..", "org.apache.hc..", "org.slf4j.."))
                .because("core-webhook is shared by every app that delivers a webhook and names no app's type")
                .allowEmptyShould(true);
    }

    /**
     * A class of a test source set that sits in a layer package, so it proves layered code rather than wiring an
     * app. Every class counts, not only the {@code *Test} ones: a helper beside it mirrors production too.
     */
    private static DescribedPredicate<JavaClass> areTestsInALayerPackage(String root) {
        return DescribedPredicate.describe(
                "classes inside a layer package", javaClass -> hasLayerSegment(root, javaClass.getPackageName()));
    }

    private static DescribedPredicate<JavaClass> named(Set<String> simpleNames, String description) {
        return DescribedPredicate.describe(description, javaClass -> simpleNames.contains(javaClass.getSimpleName()));
    }

    private static ArchCondition<JavaClass> notReachIntoOtherContexts(Profile profile) {
        return new ArchCondition<>("not depend on another " + profile.component() + " outside its api package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String from = contextOf(profile, origin.getPackageName());
                if (from == null) {
                    return;
                }
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    String targetPackage = dependency.getTargetClass().getPackageName();
                    String to = contextOf(profile, targetPackage);
                    if (to != null && !to.equals(from) && !isApi(profile.root(), to, targetPackage)) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> never(String reason) {
        return new ArchCondition<>("not exist: " + reason) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                events.add(SimpleConditionEvent.violated(item, item.getFullName() + " " + reason));
            }
        };
    }

    private static boolean hasLayerSegment(String root, String packageName) {
        String prefix = root + ".";
        if (!packageName.startsWith(prefix)) {
            return false;
        }
        String below = packageName.substring(prefix.length());
        for (String layer : LAYERS) {
            if (hasSegment(below, layer)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isApiPackage(String root, String packageName) {
        String rest = packageName.substring(root.length() + 1);
        int dot = rest.indexOf('.');
        return dot > 0 && (rest.substring(dot + 1).equals("api") || rest.substring(dot + 1).startsWith("api."));
    }

    private static DescribedPredicate<JavaClass> areTopLevel() {
        return DescribedPredicate.describe("top-level", javaClass -> javaClass.getEnclosingClass().isEmpty());
    }

    private static boolean isOutboundPort(String simpleName) {
        return OUTBOUND_PORT_SUFFIXES.stream().anyMatch(simpleName::endsWith);
    }

    private static boolean hasSegment(String packageName, String segment) {
        for (String part : packageName.split("\\.")) {
            if (part.equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDomainPackage(String root, String packageName) {
        if (!packageName.startsWith(root + ".")) {
            return false;
        }
        return hasSegment(packageName.substring(root.length() + 1), "domain");
    }

    private static String owningContextOf(Profile profile, String packageName) {
        String prefix = profile.root() + ".";
        if (!packageName.startsWith(prefix)) {
            return null;
        }
        String rest = packageName.substring(prefix.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    private static String tableName(String constant) {
        StringBuilder name = new StringBuilder();
        for (String part : constant.split("_")) {
            name.append(part.charAt(0)).append(part.substring(1).toLowerCase());
        }
        return name.toString();
    }

    /**
     * Every endpoint names who may call it: {@code @Callers} on the method or the class, {@code @PublicEndpoint}, or an
     * annotation that implies a caller (Billing's portal permission). The OpenAPI label derivation reads the same
     * sources, so a route that would document as unknown fails here before it ships.
     */
    private static ArchRule everyEndpointDeclaresACaller(Profile profile) {
        String root = profile.root();
        return classes().that().resideInAPackage(root + "..")
                .and().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should(new ArchCondition<>("declare a caller on every endpoint") {
                    @Override
                    public void check(JavaClass type, ConditionEvents events) {
                        boolean onClass = type.isAnnotatedWith("dev.ngb.core.security.Callers")
                                || type.isMetaAnnotatedWith("dev.ngb.core.security.Callers");
                        for (JavaMethod method : type.getMethods()) {
                            if (!isEndpoint(method)) {
                                continue;
                            }
                            boolean onMethod = method.isAnnotatedWith("dev.ngb.core.security.Callers")
                                    || method.isMetaAnnotatedWith("dev.ngb.core.security.Callers")
                                    || method.isAnnotatedWith("dev.ngb.core.security.PublicEndpoint")
                                    || impliesACaller(method);
                            if (!onClass && !onMethod) {
                                events.add(SimpleConditionEvent.violated(
                                        method, method.getFullName() + " declares no caller"));
                            }
                        }
                    }
                })
                .because("a route's caller decides its prefix and its label, so every endpoint must name one")
                .allowEmptyShould(true);
    }

    private static boolean isEndpoint(JavaMethod method) {
        return method.isAnnotatedWith("org.springframework.web.bind.annotation.GetMapping")
                || method.isAnnotatedWith("org.springframework.web.bind.annotation.PostMapping")
                || method.isAnnotatedWith("org.springframework.web.bind.annotation.PutMapping")
                || method.isAnnotatedWith("org.springframework.web.bind.annotation.PatchMapping")
                || method.isAnnotatedWith("org.springframework.web.bind.annotation.DeleteMapping")
                || method.isAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping");
    }

    /** A portal permission is the one caller-implying annotation a service owns; the core names no service. */
    private static boolean impliesACaller(JavaMethod method) {
        return method.getAnnotations().stream()
                .anyMatch(annotation -> annotation.getRawType().getSimpleName().equals("RequiresPortalPermission"));
    }

    private static String contextOf(Profile profile, String packageName) {
        String prefix = profile.root() + ".";
        if (!packageName.startsWith(prefix)) {
            return null;
        }
        String rest = packageName.substring(prefix.length());
        int dot = rest.indexOf('.');
        String first = dot < 0 ? rest : rest.substring(0, dot);
        boolean notAContext = first.equals(profile.kernel()) || first.equals("boot");
        return notAContext ? null : first;
    }

    private static boolean isApi(String root, String context, String packageName) {
        String api = root + "." + context + ".api";
        return packageName.equals(api) || packageName.startsWith(api + ".");
    }

    private static String[] concat(List<String> prefix, String... rest) {
        List<String> all = new ArrayList<>(prefix);
        all.addAll(List.of(rest));
        return all.toArray(String[]::new);
    }

    /** Turns the profile's package names, which are relative to the service root, into full package identifiers. */
    private static String[] packagesBelow(Profile profile, Set<String> relative) {
        return relative.stream().map(pattern -> profile.root() + "." + pattern).toArray(String[]::new);
    }
}
