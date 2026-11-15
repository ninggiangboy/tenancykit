package dev.ngb.notification.dispatch.application.notification.command;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.notification.dispatch.application.notification.port.NotificationPreferenceRepository;
import dev.ngb.notification.dispatch.application.notification.service.PreferenceView;
import dev.ngb.notification.dispatch.domain.notification.NotificationType;
import dev.ngb.notification.dispatch.domain.template.LocalePolicy;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Saves the caller's locale and muted types; the user always comes from the token, never from the body. */
public class SavePreferenceUseCase {

    private final UnitOfWork unitOfWork;
    private final NotificationPreferenceRepository preferences;
    private final TenantContext tenantContext;
    private final CurrentActor currentActor;

    public SavePreferenceUseCase(
            UnitOfWork unitOfWork,
            NotificationPreferenceRepository preferences,
            TenantContext tenantContext,
            CurrentActor currentActor) {
        this.unitOfWork = unitOfWork;
        this.preferences = preferences;
        this.tenantContext = tenantContext;
        this.currentActor = currentActor;
    }

    public PreferenceView save(String locale, Set<NotificationType> mutedTypes) {
        String checkedLocale = locale == null || locale.isBlank() ? null : locale.trim().toLowerCase(java.util.Locale.ROOT);
        if (checkedLocale != null && !LocalePolicy.SUPPORTED.contains(checkedLocale)) {
            throw new BadRequestException("the locale must be one of " + LocalePolicy.SUPPORTED);
        }
        PreferenceView preference = new PreferenceView(checkedLocale, mutedTypes == null ? Set.of() : mutedTypes);
        return unitOfWork.transactional(() -> {
            preferences.save(tenantContext.requireTenant(), currentActor.get().id(), preference);
            return preference;
        });
    }

    /** Parses type names from a request body, refusing a name this build does not know. */
    public static Set<NotificationType> parseTypes(Set<String> names) {
        if (names == null) {
            return Set.of();
        }
        return names.stream()
                .map(name -> Arrays.stream(NotificationType.values())
                        .filter(type -> type.name().equals(name))
                        .findFirst()
                        .orElseThrow(() -> new BadRequestException("unknown notification type: " + name)))
                .collect(Collectors.toUnmodifiableSet());
    }
}
