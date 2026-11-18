package dev.ngb.tenant.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.ngb.core.arch.ArchitectureRules.NamedRule;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs every architecture rule against the production code, the shared core and the test source set. */
class ArchitectureTest {

    static final String ROOT = "dev.ngb.tenant";

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
        assertThat(production.contain(ROOT + ".identity.domain.user.User")).isTrue();
        assertThat(core.contain("dev.ngb.core.runtime.UnitOfWork")).isTrue();
        assertThat(core.contain("dev.ngb.core.spring.runtime.SpringUnitOfWork")).isTrue();
        assertThat(tests.contain(ROOT + ".architecture.ArchitectureMetaTest")).isTrue();
    }

    /** The unit-of-work rule has something to guard; the allowlist on the rule class names the exceptions. */
    @Test
    void theUnitOfWorkRuleHasSubjects() {
        long useCases = production.stream()
                .filter(c -> c.getPackageName().contains(".application.") && c.getSimpleName().endsWith("UseCase") && !c.isInterface())
                .count();
        assertThat(useCases).as("a rule that matches no class passes vacuously").isGreaterThanOrEqualTo(10);
    }

    /** A test that sits in a layer package proves the class of that package; the test import is separate. */
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

    static Stream<NamedRule> rules() {
        return ArchitectureRules.all(ROOT).stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void productionCodeObeysRule(NamedRule rule) {
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
