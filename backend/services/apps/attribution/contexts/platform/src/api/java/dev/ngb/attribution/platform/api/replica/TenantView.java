package dev.ngb.attribution.platform.api.replica;

import dev.ngb.core.id.TenantId;

/**
 * One tenant as Platform publishes it.
 *
 * @param status {@code ACTIVE} or {@code DEACTIVATED}, exactly the status on the replica row
 */
public record TenantView(TenantId id, String code, String name, String status) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
