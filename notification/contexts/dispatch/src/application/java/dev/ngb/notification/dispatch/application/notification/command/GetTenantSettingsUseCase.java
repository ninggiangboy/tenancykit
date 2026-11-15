package dev.ngb.notification.dispatch.application.notification.command;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.notification.dispatch.application.notification.port.NotificationSettingsRepository;
import dev.ngb.notification.dispatch.application.notification.service.NotificationSettingsView;

/** The tenant's settings; an absent row reads as every type enabled in {@code en}. */
public class GetTenantSettingsUseCase {

    private final UnitOfWork unitOfWork;
    private final NotificationSettingsRepository settings;
    private final TenantContext tenantContext;

    public GetTenantSettingsUseCase(
            UnitOfWork unitOfWork, NotificationSettingsRepository settings, TenantContext tenantContext) {
        this.unitOfWork = unitOfWork;
        this.settings = settings;
        this.tenantContext = tenantContext;
    }

    public NotificationSettingsView current() {
        return unitOfWork.transactional(() -> settings.find(tenantContext.requireTenant()));
    }
}
