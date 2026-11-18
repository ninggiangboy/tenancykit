package dev.ngb.notification.dispatch.application.notification.service;

import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a tenant allows.
 *
 * <p>An absent settings row is not "nothing enabled": a tenant that never configured anything gets all five types in
 * {@code en}. A type the tenant disabled produces no notification and no row at all.
 */
public record NotificationSettingsView(String defaultLocale, Set<NotificationType> enabledTypes) {

    public NotificationSettingsView {
        enabledTypes = Set.copyOf(enabledTypes);
    }

    public static NotificationSettingsView defaults() {
        return new NotificationSettingsView("en", Set.of(NotificationType.values()));
    }

    public boolean enabled(NotificationType type) {
        return enabledTypes.contains(type);
    }

    /** The stored type names, in declaration order, so the array written back is stable. */
    public String[] enabledTypeNames() {
        return Arrays.stream(NotificationType.values())
                .filter(enabledTypes::contains)
                .map(Enum::name)
                .toArray(String[]::new);
    }

    /** Parses stored names, ignoring one this build no longer knows instead of failing the whole read. */
    public static Set<NotificationType> parseTypes(String[] names) {
        if (names == null) {
            return Set.of();
        }
        return Arrays.stream(names)
                .flatMap(name -> Arrays.stream(NotificationType.values()).filter(type -> type.name().equals(name)))
                .collect(Collectors.toUnmodifiableSet());
    }
}
