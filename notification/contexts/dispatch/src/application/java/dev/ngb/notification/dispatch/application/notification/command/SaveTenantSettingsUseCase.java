package dev.ngb.notification.dispatch.application.notification.command;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.notification.dispatch.application.notification.port.NotificationSettingsRepository;
import dev.ngb.notification.dispatch.application.notification.service.NotificationSettingsView;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import dev.ngb.notification.dispatch.domain.template.LocalePolicy;
import java.util.Set;

/** Saves a tenant's default locale and enabled types, refusing anything this build does not know. */
public class SaveTenantSettingsUseCase {

    private final UnitOfWork unitOfWork;
    private final NotificationSettingsRepository settings;
    private final TenantContext tenantContext;

    public SaveTenantSettingsUseCase(
            UnitOfWork unitOfWork, NotificationSettingsRepository settings, TenantContext tenantContext) {
        this.unitOfWork = unitOfWork;
        this.settings = settings;
        this.tenantContext = tenantContext;
    }

    public NotificationSettingsView save(String defaultLocale, Set<NotificationType> enabledTypes) {
        if (defaultLocale == null || !LocalePolicy.SUPPORTED.contains(defaultLocale)) {
            throw new BadRequestException("the default locale must be one of " + LocalePolicy.SUPPORTED);
        }
        NotificationSettingsView view = new NotificationSettingsView(
                defaultLocale, enabledTypes == null ? Set.of() : enabledTypes);
        return unitOfWork.transactional(() -> {
            settings.save(tenantContext.requireTenant(), view);
            return view;
        });
    }
}
