package dev.ngb.notification.dispatch.domain.notification;

/**
 * The five things this app notifies a Platform tenant user about.
 *
 * <p>The catalog is fixed rather than configurable: a rules engine would be a workflow engine with no second real
 * case. A tenant decides only whether a type is enabled, never what it means. The template key is part of the type so
 * a default template and an override row can never disagree about which type they describe.
 */
public enum NotificationType {

    /** A user that had no grant row can now sign in to the tenant. */
    ACCESS_GRANTED("access-granted"),

    /** A user's permission set actually changed. */
    ACCESS_CHANGED("access-changed"),

    /** A user's account was disabled. */
    ACCESS_DISABLED("access-disabled"),

    /** A tenant's entitlement for one app was enabled or disabled. */
    ENTITLEMENT_CHANGED("entitlement-changed"),

    /** A tenant was deactivated. */
    TENANT_DEACTIVATED("tenant-deactivated");

    private final String templateKey;

    NotificationType(String templateKey) {
        this.templateKey = templateKey;
    }

    /** The classpath directory and override key of this type's templates. */
    public String templateKey() {
        return templateKey;
    }

    /** The type a template key names, or empty when no type owns it. */
    public static java.util.Optional<NotificationType> fromTemplateKey(String key) {
        for (NotificationType type : values()) {
            if (type.templateKey.equals(key)) {
                return java.util.Optional.of(type);
            }
        }
        return java.util.Optional.empty();
    }
}
