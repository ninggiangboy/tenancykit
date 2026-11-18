package dev.ngb.notification.dispatch.application.notification.service;

import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One user's preference.
 *
 * @param locale the user's language, or null to fall through to the tenant default
 * @param mutedTypes types the user does not want emailed; the in-app row is still stored, because the two channels
 *     have different capability and a muted category is still readable in the app
 */
public record PreferenceView(String locale, Set<NotificationType> mutedTypes) {

    public PreferenceView {
        mutedTypes = Set.copyOf(mutedTypes);
    }

    public static PreferenceView none() {
        return new PreferenceView(null, Set.of());
    }

    public boolean muted(NotificationType type) {
        return mutedTypes.contains(type);
    }

    public String[] mutedTypeNames() {
        return mutedTypes.stream().map(Enum::name).sorted().toArray(String[]::new);
    }

    public static Set<NotificationType> parseTypes(String[] names) {
        if (names == null) {
            return Set.of();
        }
        return Arrays.stream(names)
                .flatMap(name -> Arrays.stream(NotificationType.values()).filter(type -> type.name().equals(name)))
                .collect(Collectors.toUnmodifiableSet());
    }
}
