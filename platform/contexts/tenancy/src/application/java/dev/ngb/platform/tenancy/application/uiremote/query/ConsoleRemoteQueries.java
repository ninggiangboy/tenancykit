package dev.ngb.platform.tenancy.application.uiremote.query;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.platform.tenancy.application.uiremote.query.UiRemoteReadRepository;
import dev.ngb.platform.tenancy.domain.uiremote.RemoteAudience;
import java.util.List;

/**
 * The feed one shell reads on load. The audience follows the caller's scope: a Platform user gets the remotes built for
 * the Platform Shell, a Tenant user the ones built for the Tenant Shell. A tenant caller additionally sees only the
 * remotes whose app the tenant has enabled — except the platform console itself, which every tenant's admins use.
 */
public class ConsoleRemoteQueries {

    private final UnitOfWork unitOfWork;
    private final UiRemoteReadRepository remotes;

    public ConsoleRemoteQueries(UnitOfWork unitOfWork, UiRemoteReadRepository remotes) {
        this.unitOfWork = unitOfWork;
        this.remotes = remotes;
    }

    public List<ConsoleRemoteView> feed(Actor actor) {
        boolean tenant = actor.scope() == PermissionScope.TENANT;
        RemoteAudience audience = tenant ? RemoteAudience.TENANT : RemoteAudience.PLATFORM;
        return unitOfWork.transactional(() -> remotes.feed(audience, actor.tenantId().value(), tenant));
    }
}
