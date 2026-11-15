package dev.ngb.notification.dispatch.application.notification.service.catalog;

import dev.ngb.notification.dispatch.application.notification.service.Change;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import dev.ngb.notification.platform.api.replica.TenantView;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The fixed catalog of transition rules.
 *
 * <p>A configurable rules engine was rejected: it is a workflow engine with no second real case. The first matching
 * rule wins, and {@link #resolve} refuses a transition two rules both claim rather than picking one silently.
 */
public class NotificationCatalog {

    /** A rule that matched, with the variables its templates need. */
    public record ResolvedNotification(NotificationType type, Map<String, Object> variables) {

        public ResolvedNotification {
            variables = Map.copyOf(variables);
        }
    }

    private final List<NotificationRule> rules;

    public NotificationCatalog(List<NotificationRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public Optional<ResolvedNotification> resolve(Change change, TenantView tenant) {
        List<NotificationRule> matches = rules.stream().filter(rule -> rule.matches(change)).toList();
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "two notification rules matched one transition: "
                            + matches.stream().map(rule -> rule.type().name()).toList());
        }
        return matches.stream()
                .findFirst()
                .map(rule -> new ResolvedNotification(rule.type(), rule.variables(change, tenant)));
    }

    /** The rule order, for the test that documents it. */
    public List<NotificationType> order() {
        return rules.stream().map(NotificationRule::type).toList();
    }
}
