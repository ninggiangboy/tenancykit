package dev.ngb.notification.dispatch.application.notification.command;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.notification.dispatch.application.notification.port.NotificationPreferenceRepository;
import dev.ngb.notification.dispatch.application.notification.service.PreferenceView;

/** The caller's own preference; an absent row is an empty preference. */
public class GetPreferenceUseCase {

    private final UnitOfWork unitOfWork;
    private final NotificationPreferenceRepository preferences;
    private final TenantContext tenantContext;
    private final CurrentActor currentActor;

    public GetPreferenceUseCase(
            UnitOfWork unitOfWork,
            NotificationPreferenceRepository preferences,
            TenantContext tenantContext,
            CurrentActor currentActor) {
        this.unitOfWork = unitOfWork;
        this.preferences = preferences;
        this.tenantContext = tenantContext;
        this.currentActor = currentActor;
    }

    public PreferenceView current() {
        return unitOfWork.transactional(() -> preferences.find(
                tenantContext.requireTenant(), currentActor.get().id()));
    }
}
