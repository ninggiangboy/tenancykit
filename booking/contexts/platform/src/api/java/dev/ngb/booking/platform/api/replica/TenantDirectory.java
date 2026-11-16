package dev.ngb.booking.platform.api.replica;

import dev.ngb.core.id.TenantId;
import java.util.List;
import java.util.Optional;

/**
 * The replica's tenant read seam.
 *
 * <p>Every method is answered from the replica this service owns; nothing here calls Platform.
 */
public interface TenantDirectory {

    /** Every {@code ACTIVE} tenant except the reserved platform pseudo-tenant. */
    List<TenantId> activeTenantIds();

    /**
     * Every tenant a background job must visit: the active tenants plus the reserved platform pseudo-tenant.
     *
     * <p>The platform tenant is included because a Platform user has an inbox and is notified like anyone else, so its
     * rows live under {@link dev.ngb.core.tenancy.RequestScope#PLATFORM_TENANT_ID}. A sweep that skipped it would leave
     * that user's email command in the outbox and never purge their retained rows. The tenant-wide rules still resolve
     * their audience from tenant users only, so including this id here does not notify a tenant about Platform state.
     */
    List<TenantId> sweptTenantIds();

    /** One tenant, or empty when the replica has not seen it yet. */
    Optional<TenantView> tenant(TenantId tenantId);

    /**
     * The tenant's entitlement for one app, or empty when the replica has no row. The transition rules compare it
     * with the event's payload before the upsert, because a state-transfer event carries only the new state.
     */
    Optional<Boolean> entitlementEnabled(TenantId tenantId, String appCode);
}
