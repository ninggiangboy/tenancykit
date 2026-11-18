package dev.ngb.core.security;

import dev.ngb.core.id.TenantSystemId;
import java.util.UUID;

/** The authenticated {@link Actor} of the current request. */
public interface CurrentActor {

    /** @throws IllegalStateException when the request is not authenticated */
    Actor get();

    /**
     * The Tenant System behind the request, or null when a Tenant User is acting directly. Reads of another
     * context's data are narrowed to the records that system owns.
     */
    default UUID caller() {
        Actor actor = get();
        return actor.type() == ActorType.TENANT_SYSTEM ? actor.id() : null;
    }

    /** The same Tenant System as an id type, or null for a Tenant User. */
    default TenantSystemId callerId() {
        UUID caller = caller();
        return caller == null ? null : new TenantSystemId(caller);
    }
}
