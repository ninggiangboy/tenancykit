package dev.ngb.platform.tenancy.application.entitlement.command;

import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.tenancy.application.entitlement.port.AppEntitlementRepository;
import dev.ngb.platform.tenancy.domain.entitlement.AppEntitlement;

/**
 * The one way to obtain the entitlement row a disposition must change, under its lock.
 *
 * <p>A missing row is created at version 1 with {@code insert ... on conflict do nothing}. That insert is how two
 * concurrent first dispositions serialise: exactly one inserts, and the other inserts nothing, then locks the row the
 * winner inserted and applies its change on top, which bumps that row to version 2.
 *
 * <p>The {@code If-Match} check runs here because this is the first point that holds the lock and knows whether the
 * row exists: against its version when it does, and as an absent-row check when it does not.
 */
final class EntitlementRows {

    /** The locked row, and whether this call inserted its first version. */
    record Locked(AppEntitlement entitlement, boolean inserted) {}

    private EntitlementRows() {}

    static Locked lockOrInsert(AppEntitlementRepository entitlements, AppEntitlement firstVersion, ExpectedVersion expected) {
        var existing = entitlements.lock(firstVersion.tenantId(), firstVersion.appCode());
        if (existing.isPresent()) {
            expected.check(existing.get().version());
            return new Locked(existing.get(), false);
        }
        expected.checkAbsent();
        if (entitlements.insertIfAbsent(firstVersion)) {
            return new Locked(firstVersion, true);
        }
        AppEntitlement raced = entitlements.lock(firstVersion.tenantId(), firstVersion.appCode()).orElseThrow();
        expected.check(raced.version());
        return new Locked(raced, false);
    }
}
