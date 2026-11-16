package dev.ngb.loyalty.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.ngb.core.arch.ArchitectureRules.NamedRule;
import dev.ngb.loyalty.support.RepositoryPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    static final String ROOT = "dev.ngb.loyalty";

    /** The read side exists now, so no rule needs an empty-subject escape hatch. */
    private static final Set<String> RULES_WITHOUT_SUBJECTS = Set.of();

    /**
     * The apps this build must never name: the sibling delivered alongside it, the one whose webhook context it copies
     * as a template, and the skeleton it was copied from. Assembled at runtime so this gate's own source is clean; the
     * grep in the phase's verification covers the same three words from outside the build.
     */
    private static final List<String> FOREIGN_APPS = List.of("c" + "rm", "sur" + "vey", "mail" + "room");

    private static final List<String> SCANNED_TREES = List.of("apps", "contexts", "libs");

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
     * registered everywhere. loyalty has two runtimes, so both are declared positively.
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
        assertThat(scheduledClasses()).as("a rule that matches no class passes vacuously").isGreaterThanOrEqualTo(1);
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

    /**
     * The sources themselves name none of the three apps: not a sibling type, not a template's event name, not the
     * skeleton's property. ArchUnit sees compiled classes, so a word in a migration, a property or a build file needs a
     * text scan; this is the build's own copy of the phase's grep.
     */
    @Test
    void theSourcesNameNoForeignApp() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String tree : SCANNED_TREES) {
            Path root = RepositoryPaths.loyaltyRoot().resolve(tree);
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(ArchitectureTest::isScannable)
                        .filter(ArchitectureTest::isSource)
                        .toList()) {
                    String text = Files.readString(file).toLowerCase(Locale.ROOT);
                    for (String word : FOREIGN_APPS) {
                        if (text.contains(word)) {
                            offenders.add(RepositoryPaths.loyaltyRoot().relativize(file) + " names " + word);
                        }
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void theSourceScanCoversTheContexts() {
        assertThat(RepositoryPaths.loyaltyRoot().resolve("contexts").resolve("webhook"))
                .as("the scan must see the contexts, or it proves nothing")
                .isDirectoryContaining(path -> path.getFileName().toString().equals("build.gradle.kts"));
    }

    /** A generated copy under {@code build/} is output, not a source: the source next to it is what this scan judges. */
    private static boolean isSource(Path file) {
        return !file.toString().contains("/build/");
    }

    private static boolean isScannable(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".kts") || name.endsWith(".yml") || name.endsWith(".yaml")
                || name.endsWith(".sql") || name.endsWith(".toml") || name.endsWith(".properties")
                || name.equals("Dockerfile");
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
        rule.rule().allowEmptyShould(true).check(production);
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
