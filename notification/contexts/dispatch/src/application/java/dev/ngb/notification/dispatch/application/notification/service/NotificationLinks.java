package dev.ngb.notification.dispatch.application.notification.service;

/** The one link a message carries. Relative until a frontend exists; the notification API returns it unchanged. */
public final class NotificationLinks {

    public static final String INBOX = "/notification/notifications";

    private NotificationLinks() {}
}
