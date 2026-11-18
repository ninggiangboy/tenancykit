package dev.ngb.notification.dispatch.application.notification.port;

import dev.ngb.notification.dispatch.domain.notification.Notification;

/** Stores the in-app row. The unique key is the second dedupe layer, so a repeated transition is a no-op. */
public interface NotificationRepository {

    /** @return true when the row was inserted, false when this transition was already stored */
    boolean insertIfAbsent(Notification notification);
}
