package dev.ngb.notification.dispatch.application.notification.service.catalog;

import dev.ngb.notification.dispatch.application.notification.service.Change;
import dev.ngb.notification.dispatch.application.notification.service.NotificationLinks;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import dev.ngb.notification.platform.api.replica.TenantView;
import java.util.Map;

/**
 * An active user became disabled. They still get the in-app row, so they can read it if they are re-enabled.
 *
 * <p>It fires at both scopes: a Platform administrator who is disabled is told so the same way, and their row lives
 * under the Platform tenant.
 */
public class AccessDisabledRule implements NotificationRule {

    @Override
    public NotificationType type() {
        return NotificationType.ACCESS_DISABLED;
    }

    @Override
    public boolean matches(Change change) {
        return change.previousUser() != null
                && change.currentUser() != null
                && change.previousUser().active()
                && !change.currentUser().active();
    }

    @Override
    public Map<String, Object> variables(Change change, TenantView tenant) {
        return Map.of(
                "tenantName", tenant.name(),
                "displayName", change.currentUser().displayName(),
                "link", NotificationLinks.INBOX);
    }
}
