package dev.ngb.billing.customer.application.customer.service;

import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.id.TenantSystemId;

/**
 * The one rule for which Tenant System a Customer command acts for: a Tenant System always acts for itself and may
 * not name another one, while a Tenant User names the system it administers, or none to act on an unmapped customer.
 */
public final class TenantSystems {

    private TenantSystems() {}

    /**
     * @param caller the acting Tenant System, or null for a Tenant User
     * @param requested the system named in the request, or null when none was
     * @return the system the command acts for, or null when a Tenant User named none
     * @throws AccessDeniedException when an acting Tenant System names another one
     */
    public static TenantSystemId actingFor(TenantSystemId caller, String requested) {
        if (caller != null) {
            if (requested != null && !TenantSystemId.of(requested).equals(caller)) {
                throw new AccessDeniedException("a tenant system can only act for itself");
            }
            return caller;
        }
        return requested == null ? null : TenantSystemId.of(requested);
    }
}
