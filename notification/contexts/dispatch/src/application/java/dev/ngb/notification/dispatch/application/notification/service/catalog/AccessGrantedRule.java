package dev.ngb.notification.dispatch.application.notification.service.catalog;

import dev.ngb.notification.dispatch.application.notification.service.Change;
import dev.ngb.notification.dispatch.application.notification.service.NotificationLinks;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import dev.ngb.notification.platform.api.replica.TenantView;
import java.util.Map;

/**
 * A user that had no grant row can now sign in.
 *
 * <p>It fires at both scopes: a Platform administrator whose access is granted is told so exactly like a tenant user,
 * and their rows live under {@link dev.ngb.core.tenancy.RequestScope#PLATFORM_TENANT_ID}, so the same RLS, tables and
 * dispatch paths serve them.
 */
public class AccessGrantedRule implements NotificationRule {

    @Override
    public NotificationType type() {
        return NotificationType.ACCESS_GRANTED;
    }

    @Override
    public boolean matches(Change change) {
        return change.previousUser() == null
                && change.currentUser() != null
                && change.currentUser().active();
    }

    @Override
    public Map<String, Object> variables(Change change, TenantView tenant) {
        return Map.of(
                "tenantName", tenant.name(),
                "displayName", change.currentUser().displayName(),
                "link", NotificationLinks.INBOX);
    }
}
