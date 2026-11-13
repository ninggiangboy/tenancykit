package dev.ngb.platform.architecture;

import dev.ngb.core.arch.ArchitectureRules.NamedRule;
import dev.ngb.core.arch.ArchitectureRules.Profile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Platform's profile of the shared architecture rules. Platform runs the same rule ids as Billing and the sample
 * tenant system and adds none of its own: what used to be a smaller set with no role, port, repository or read/write
 * rules is now the shared set, so a rule cannot be missing here.
 *
 * <p>{@code ResolveHostUseCase} is temporarily allowlisted for {@code write-side-does-not-depend-on-query}: it is
 * the request filter's hostname resolver and reads through the tenancy read side. Follow-up 2026-01-31 moves it onto
 * a port of its own (or the read side into the context's api package).
 */
final class ArchitectureRules {

    static final String CORE = dev.ngb.core.arch.ArchitectureRules.CORE;

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
        return dev.ngb.core.arch.ArchitectureRules.all(profile(root));
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
                "sharedkernel",
                "dev.ngb.platform_jooq",
                TABLE_OWNER,
                Set.of(),
                Set.of("dev.ngb.billing..", "dev.ngb.tenant.."),
                Set.of("*.adapter..", "boot.."),
                Set.of("org.jooq..", "dev.ngb.platform_jooq.."),
                Set.of(),
                Set.of("ResolveHostUseCase"));
    }
}
