package dev.ngb.notification.shared;

import java.util.Set;

/** The notification app's permission codes; a test asserts this set equals {@code contracts/permissions/notification.yaml}. */
public final class NotificationPermissions {

    public static final String TEMPLATE_READ = "notification:template:read";
    public static final String TEMPLATE_MANAGE = "notification:template:manage";
    public static final String CONFIG_MANAGE = "notification:config:manage";
    public static final String DISPATCH_READ = "notification:dispatch:read";

    public static final Set<String> CODES = Set.of(TEMPLATE_READ, TEMPLATE_MANAGE, CONFIG_MANAGE, DISPATCH_READ);

    private NotificationPermissions() {}
}
