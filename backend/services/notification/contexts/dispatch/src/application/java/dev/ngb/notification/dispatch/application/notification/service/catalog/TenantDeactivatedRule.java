package dev.ngb.notification.dispatch.application.notification.service.catalog;

import dev.ngb.notification.dispatch.application.notification.service.Change;
import dev.ngb.notification.dispatch.application.notification.service.NotificationLinks;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import dev.ngb.notification.platform.api.replica.TenantView;
import java.util.Map;

/** A tenant went from {@code ACTIVE} to {@code DEACTIVATED}; every active tenant-scope user hears about it. */
public class TenantDeactivatedRule implements NotificationRule {

    @Override
    public NotificationType type() {
        return NotificationType.TENANT_DEACTIVATED;
    }

    @Override
    public boolean matches(Change change) {
        return change.previousTenant() != null
                && change.currentTenant() != null
                && change.previousTenant().active()
                && !change.currentTenant().active();
    }

    @Override
    public Map<String, Object> variables(Change change, TenantView tenant) {
        return Map.of(
                "tenantName", tenant.name(),
                "tenantCode", tenant.code(),
                "link", NotificationLinks.INBOX);
    }
}
