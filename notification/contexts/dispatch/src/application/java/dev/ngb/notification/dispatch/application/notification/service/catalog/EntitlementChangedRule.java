package dev.ngb.notification.dispatch.application.notification.service.catalog;

import dev.ngb.notification.dispatch.application.notification.service.Change;
import dev.ngb.notification.dispatch.application.notification.service.NotificationLinks;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import dev.ngb.notification.platform.api.replica.TenantView;
import java.util.Map;
import java.util.Objects;

/** An app was enabled or disabled for a tenant, including the first row for that app. */
public class EntitlementChangedRule implements NotificationRule {

    @Override
    public NotificationType type() {
        return NotificationType.ENTITLEMENT_CHANGED;
    }

    @Override
    public boolean matches(Change change) {
        return change.currentEntitlement() != null
                && (change.previousEntitlement() == null
                        || !Objects.equals(change.previousEntitlement(), change.currentEntitlement()));
    }

    @Override
    public Map<String, Object> variables(Change change, TenantView tenant) {
        return Map.of(
                "tenantName", tenant.name(),
                "appCode", change.appCode(),
                "enabled", Boolean.TRUE.equals(change.currentEntitlement()) ? "enabled" : "disabled",
                "link", NotificationLinks.INBOX);
    }
}
