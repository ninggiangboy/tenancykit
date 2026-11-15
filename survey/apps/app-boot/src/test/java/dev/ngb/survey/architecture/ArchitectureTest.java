package dev.ngb.survey.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.ngb.core.arch.ArchitectureRules.NamedRule;
import java.util.List;
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

    static final String ROOT = "dev.ngb.survey";

    /**
     * The skeleton has no domain, query or job class yet: the feature phases add them. Each of these rules is asserted
     * again from the phase that creates its first subject, so an empty subject here is a record of what does not exist
     * yet, not a rule that was weakened.
     */
    private static final Set<String> RULES_WITHOUT_SUBJECTS = Set.of(
            "layer-direction",
            "query-side-uses-no-domain-aggregates",
            "domain-events-live-in-an-events-holder");

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
     * registered everywhere. Email has two runtimes, so both are declared positively.
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

    /** Covers what schedules nothing itself: the sweep the jobs fan out through is owned by the worker too. */
    @Test
    void everyClassInTheJobsPackageRunsInTheWorkerRuntime() {
        assertThat(production.stream()
                .filter(c -> c.getPackageName().startsWith(ROOT + ".boot.jobs"))
                .filter(c -> !serves(c, "worker"))
                .map(JavaClass::getName)
                .toList())
                .as("a class in the jobs package outside the worker runtime is registered in every co-located runtime")
                .isEmpty();
    }

    @Test
    void theJobRuleHasSubjects() {
        // Phase 3 wires no job yet: the replica rebuild and the listener are not @Scheduled. The count is asserted once a
        // job exists, so the rule is not silently vacuous then.
        assertThat(scheduledClasses()).as("no scheduled class exists in the skeleton").isZero();
    }

    /**
     * A test that sits in a layer package proves the class of that package; the test source set is imported
     * separately, because the production import deliberately leaves the tests out.
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
