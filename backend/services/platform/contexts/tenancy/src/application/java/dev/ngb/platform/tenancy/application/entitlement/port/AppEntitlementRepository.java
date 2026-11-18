package dev.ngb.platform.tenancy.application.entitlement.port;

import dev.ngb.core.id.TenantId;
import dev.ngb.platform.tenancy.domain.entitlement.AppEntitlement;
import java.util.Optional;

/** Write side of app entitlements. */
public interface AppEntitlementRepository {

    Optional<AppEntitlement> find(TenantId tenantId, String appCode);

    /** Reads the row under a row lock, so the version bump that follows cannot race another one. */
    Optional<AppEntitlement> lock(TenantId tenantId, String appCode);

    /**
     * Inserts the first version of a row unless another writer already inserted it.
     *
     * <p>This is how the first enable of an app creates its row without a blind upsert: two concurrent first enables
     * both call it, exactly one inserts at version 1, and the other sees it inserted nothing, locks the row the other
     * one inserted and bumps it to 2.
     *
     * @return true when this call inserted the row, false when it already existed
     */
    boolean insertIfAbsent(AppEntitlement entitlement);

    /** Writes the row, but only when its stored version is older than the one on the aggregate. */
    void update(AppEntitlement entitlement);
}
