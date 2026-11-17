package dev.ngb.customer.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.ngb.core.arch.ArchitectureRules.NamedRule;
import dev.ngb.customer.support.RepositoryPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs every architecture rule against the production code, the shared core and the test source set. */
class ArchitectureTest {

    static final String ROOT = "dev.ngb.customer";

    /** Every rule has a subject in this phase, so no rule needs an empty-subject escape hatch. */
    private static final Set<String> RULES_WITHOUT_SUBJECTS = Set.of();

    /** The modules customer's own sources live in; the scan reads their layered source sets, never a test. */
    private static final List<String> MODULES =
            List.of("apps/app-boot", "contexts/platform", "contexts/customers", "contexts/orders", "contexts/activity",
                    "contexts/profile", "contexts/webhook", "libs/shared-core");

    private static JavaClasses production;
    private static JavaClasses core;
    private static JavaClasses tests;

    @BeforeAll
    static void importProduction() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
        core = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ArchitectureRules.CORE);
        tests = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
                .importPackages(ROOT);
        // Guards against an import that silently matches nothing and makes every rule pass.
        assertThat(production.contain(ROOT + ".Application")).isTrue();
        assertThat(core.contain("dev.ngb.core.runtime.UnitOfWork")).isTrue();
        assertThat(core.contain("dev.ngb.core.spring.runtime.SpringUnitOfWork")).isTrue();
        assertThat(tests.contain(ROOT + ".architecture.ArchitectureMetaTest")).isTrue();
    }

    /**
     * A scheduled class fires in a process that may also serve HTTP, and a job class that skips the profile is
     * registered everywhere. customer has two runtimes, so both are declared positively.
     */
    @Test
    void everyScheduledClassRunsInTheWorkerRuntime() {
        assertThat(production.stream()
                .filter(ArchitectureTest::schedulesSomething)
                .filter(c -> !serves(c, "worker"))
                .map(JavaClass::getName)
                .toList())
                .as("a scheduled class outside the worker runtime fires in every co-located runtime")
                .isEmpty();
    }

    /** Covers what schedules nothing itself: the sweep and the housekeeping the jobs fan out through belong to the worker too. */
    @Test
    void everyClassInTheJobsPackageRunsInTheWorkerRuntime() {
        assertThat(production.stream()
                .filter(c -> c.getPackageName().startsWith(ROOT + ".boot.jobs"))
                .filter(c -> !c.getName().contains("$"))
                .filter(c -> !serves(c, "worker"))
                .map(JavaClass::getName)
                .toList())
                .as("a class in the jobs package outside the worker runtime is registered in every co-located runtime")
                .isEmpty();
    }

    @Test
    void theJobRuleHasSubjects() {
        assertThat(scheduledClasses()).as("a rule that matches no class passes vacuously").isGreaterThanOrEqualTo(1);
    }

    /**
     * A test that sits in a layer package proves the class of that package; the test source set is imported separately,
     * because the production import deliberately leaves the tests out.
     */
    @Test
    void testsMirrorProductionPackages() {
        ArchitectureRules.testPackages(ROOT, packagesOf(production)).rule().check(tests);
    }

    @Test
    void theTestPackageRuleHasSubjects() {
        long testClasses = tests.stream().filter(c -> c.getSimpleName().endsWith("Test")).count();
        assertThat(testClasses).as("a rule that matches no class passes vacuously").isGreaterThanOrEqualTo(1);
    }

    /**
     * No source, resource or migration of customer names another app of the suite or an exchange it does not own. A name is a
     * literal in a string or a comment rather than a type, so ArchUnit cannot see it; the delivered tree has to be clean
     * of them, which is what keeps the copy honest and customer's own exchange from ever existing.
     */
    @Test
    void noProductionSourceNamesAnotherAppOrAForeignExchange() {
        List<String> foreignApps = List.of("loyalty", "mailroom", "survey", "attribution", "notification", "mailer");
        List<String> foreignExchanges = List.of(
                "customer.events", "loyalty.events", "billing.events", "survey.events", "mailer.events", "mailer.commands");
        List<String> offenders = List.of();
        for (String module : MODULES) {
            Path sources = RepositoryPaths.crmRoot().resolve(module);
            if (!Files.isDirectory(sources)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(sources)) {
                List<Path> files = walk
                        .filter(Files::isRegularFile)
                        .filter(file -> !file.toString().contains("/src/test/"))
                        .filter(file -> !file.toString().contains("/build/"))
                        .toList();
                for (Path file : files) {
                    String text;
                    try {
                        text = Files.readString(file).toLowerCase(Locale.ROOT);
                    } catch (IOException notText) {
                        continue;
                    }
                    for (String app : foreignApps) {
                        if (text.contains(app)) {
                            offenders = append(offenders, file + " names the " + app + " app");
                        }
                    }
                    for (String exchange : foreignExchanges) {
                        if (text.contains(exchange)) {
                            offenders = append(offenders, file + " names the exchange " + exchange);
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(offenders).as("no customer file may name another app or a foreign exchange").isEmpty();
    }

    private static List<String> append(List<String> offenders, String entry) {
        List<String> next = new java.util.ArrayList<>(offenders);
        next.add(entry);
        return next;
    }

    private static Set<String> packagesOf(JavaClasses classes) {
        return classes.stream().map(JavaClass::getPackageName).collect(Collectors.toUnmodifiableSet());
    }

    private static long scheduledClasses() {
        return production.stream().filter(ArchitectureTest::schedulesSomething).count();
    }

    private static boolean schedulesSomething(JavaClass type) {
        return type.getMethods().stream().anyMatch(method -> method.isAnnotatedWith(Scheduled.class));
    }

    private static boolean serves(JavaClass type, String runtime) {
        return type.isAnnotatedWith(Profile.class)
                && List.of(type.getAnnotationOfType(Profile.class).value()).contains(runtime);
    }

    static Stream<NamedRule> rules() {
        return ArchitectureRules.all(ROOT).stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void productionCodeObeysRule(NamedRule rule) {
        if (RULES_WITHOUT_SUBJECTS.contains(rule.id())) {
            rule.rule().allowEmptyShould(true).check(production);
            return;
        }
        rule.rule().check(production);
    }

    static Stream<NamedRule> coreRules() {
        return ArchitectureRules.core(ArchitectureRules.CORE).stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("coreRules")
    void coreObeysRule(NamedRule rule) {
        rule.rule().check(core);
    }
}
