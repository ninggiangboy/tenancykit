package dev.ngb.notification.dispatch.application.notification.service.catalog;

import dev.ngb.notification.dispatch.application.notification.service.Change;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import dev.ngb.notification.platform.api.replica.TenantView;
import dev.ngb.notification.shared.TemplateKey;
import java.util.Map;

/**
 * One transition rule. It is a pure function of two snapshots: no repository call inside {@link #matches(Change)}, so
 * the truth table is testable without a database.
 */
public interface NotificationRule {

    NotificationType type();

    boolean matches(Change change);

    /** The message variables for this transition; only the keys the type's templates reference. */
    Map<String, Object> variables(Change change, TenantView tenant);

    default TemplateKey templateKey() {
        return new TemplateKey(type().templateKey());
    }
}
