package dev.ngb.platform.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs every architecture rule against the production code and the shared core. */
class ArchitectureTest {

    static final String ROOT = "dev.ngb.platform";

    private static JavaClasses production;
    private static JavaClasses core;

    @BeforeAll
    static void importProduction() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
        core = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ArchitectureRules.CORE);
        // Guards against an import that silently matches nothing and makes every rule pass.
        assertThat(production.contain("dev.ngb.platform.Application")).isTrue();
        assertThat(production.contain("dev.ngb.platform.tenancy.domain.tenant.Tenant")).isTrue();
        assertThat(core.contain("dev.ngb.core.runtime.UnitOfWork")).isTrue();
        assertThat(core.contain("dev.ngb.core.security.Actor")).isTrue();
        assertThat(core.contain("dev.ngb.core.messaging.broker.BrokerRelay")).isTrue();
    }

    static Stream<ArchitectureRules.NamedRule> rules() {
        return ArchitectureRules.all(ROOT).stream();
    }

    static Stream<ArchitectureRules.NamedRule> coreRules() {
        return ArchitectureRules.core(ArchitectureRules.CORE).stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void obeysRule(ArchitectureRules.NamedRule rule) {
        rule.rule().check(production);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("coreRules")
    void coreObeysRule(ArchitectureRules.NamedRule rule) {
        rule.rule().check(core);
    }
}
