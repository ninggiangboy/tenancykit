package dev.ngb.core.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ngb.core.arch.ArchitectureRules.NamedRule;
import dev.ngb.core.arch.ArchitectureRules.Profile;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the generic rule ids once, so the three services cannot drift apart: each service's {@code ArchitectureMetaTest}
 * compares its own resolved list against this set plus its documented extras, and this test fails if a rule is added,
 * removed or renamed here.
 */
class ArchitectureRulesTest {

    private static final List<String> GENERIC_RULE_IDS = List.of(
            "layer-direction",
            "inner-layer-purity",
            "no-cross-context-import",
            "persistence-types-in-adapter",
            "api-exposes-no-internals",
            "no-jpa",
            "no-dependency-on-another-service",
            "role-package-not-layer-root",
            "use-cases-and-queries-in-command-or-query",
            "ports-in-port-or-query",
            "application-classes-in-a-role-package",
            "command-package-holds-only-use-cases",
            "port-package-holds-no-implementations",
            "use-case-opens-a-unit-of-work",
            "adapter-classes-in-feature-then-technology",
            "api-implementations-are-services",
            "web-does-not-depend-on-persistence",
            "web-package-holds-no-persistence",
            "generated-records-in-adapter-persistence",
            "context-uses-only-own-tables",
            "repository-pair-shares-a-package",
            "write-side-does-not-depend-on-query",
            "read-side-does-not-depend-on-write",
            "query-side-uses-no-domain-aggregates",
            "write-ports-serve-no-reads",
            "domain-events-live-in-an-events-holder");

    private static final List<String> CORE_RULE_IDS = List.of(
            "core-feign-is-self-contained",
            "core-is-self-contained",
            "core-messaging-is-self-contained",
            "core-spring-is-self-contained");

    @Test
    void theGenericRuleSetIsExactlyTheExpectedOne() {
        assertEquals(GENERIC_RULE_IDS, ruleIds(ArchitectureRules.all(profile("dev.ngb.billing", "context", "sharedkernel"))));
    }

    /** The three services differ in their profile only; the ids they run are the same. */
    @Test
    void everyServiceRunsTheSameGenericRuleIds() {
        List<String> billing = ruleIds(ArchitectureRules.all(profile("dev.ngb.billing", "context", "sharedkernel")));
        List<String> platform = ruleIds(ArchitectureRules.all(profile("dev.ngb.platform", "context", "sharedkernel")));
        List<String> tenant = ruleIds(ArchitectureRules.all(profile("dev.ngb.tenant", "module", "shared")));

        assertEquals(billing, platform);
        assertEquals(billing, tenant);
    }

    @Test
    void theCoreRuleSetIsExactlyTheExpectedOne() {
        assertEquals(CORE_RULE_IDS, ruleIds(ArchitectureRules.core("dev.ngb.core")));
    }

    @Test
    void theTestPackageRuleIsNamedOnce() {
        String id = ArchitectureRules
                .testsMirrorProductionPackages(profile("dev.ngb.billing", "context", "sharedkernel"), Set.of())
                .id();

        assertEquals("tests-mirror-production-packages", id);
    }

    private static List<String> ruleIds(List<NamedRule> rules) {
        return rules.stream().map(NamedRule::id).toList();
    }

    private static Profile profile(String root, String component, String kernel) {
        return new Profile(
                root,
                component,
                kernel,
                root + "_jooq",
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of("*.adapter..persistence..", "boot.."),
                Set.of(root + "_jooq.."),
                Set.of(),
                Set.of());
    }
}
