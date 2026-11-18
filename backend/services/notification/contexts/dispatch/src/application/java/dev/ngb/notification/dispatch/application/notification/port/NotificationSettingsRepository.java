package dev.ngb.notification.dispatch.application.notification.port;

import dev.ngb.core.id.TenantId;
import dev.ngb.notification.dispatch.application.notification.service.NotificationSettingsView;

/** The tenant's settings; an absent row means every type is enabled with locale {@code en}. */
public interface NotificationSettingsRepository {

    NotificationSettingsView find(TenantId tenantId);

    void save(TenantId tenantId, NotificationSettingsView settings);
}
