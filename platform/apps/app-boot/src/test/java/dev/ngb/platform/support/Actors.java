package dev.ngb.platform.support;

import dev.ngb.core.security.Actor;
import dev.ngb.core.security.ActorType;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.tenancy.RequestScope;
import java.util.Set;
import java.util.UUID;

/** Test actors, resolved from the same fixed values the seeded system flows use. */
public final class Actors {

    private Actors() {
    }

    public static final Actor PLATFORM = new Actor(
            ActorType.SERVICE, new UUID(0, 0), RequestScope.PLATFORM_TENANT_ID, PermissionScope.PLATFORM, "platform-test", Set.of());
}
