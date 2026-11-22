package dev.ngb.notification.dispatch.application.notification.service.catalog;

import dev.ngb.notification.dispatch.application.notification.service.Change;
import dev.ngb.notification.dispatch.application.notification.service.NotificationLinks;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import dev.ngb.notification.platform.api.replica.TenantView;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A user's permission set actually changed.
 *
 * <p>Permission recomputation is the noisy path: one role edit emits {@code user-access.changed} for every affected
 * user, and a recompute with identical permissions emits one too. The set difference is therefore the condition, not
 * the arrival of an event; it is also what makes the message worth sending. Arrays are compared as sorted sets, so an
 * unchanged set never fires.
 *
 * <p>It fires at both scopes: a Platform administrator's own permissions changing is exactly the case this type
 * describes, and their grant row lives under the Platform tenant.
 */
public class AccessChangedRule implements NotificationRule {

    @Override
    public NotificationType type() {
        return NotificationType.ACCESS_CHANGED;
    }

    @Override
    public boolean matches(Change change) {
        return change.previousUser() != null
                && change.currentUser() != null
                && change.currentUser().active()
                && !change.previousUser().permissions().equals(change.currentUser().permissions());
    }

    @Override
    public Map<String, Object> variables(Change change, TenantView tenant) {
        Set<String> previous = change.previousUser().permissions();
        Set<String> current = change.currentUser().permissions();
        return Map.of(
                "tenantName", tenant.name(),
                "displayName", change.currentUser().displayName(),
                "addedPermissions", joined(difference(current, previous)),
                "removedPermissions", joined(difference(previous, current)),
                "link", NotificationLinks.INBOX);
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static String joined(Set<String> codes) {
        return codes.isEmpty() ? "-" : codes.stream().collect(Collectors.joining(", "));
    }
}
