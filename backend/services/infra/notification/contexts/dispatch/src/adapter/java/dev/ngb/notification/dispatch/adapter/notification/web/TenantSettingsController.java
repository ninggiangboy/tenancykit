package dev.ngb.notification.dispatch.adapter.notification.web;

import dev.ngb.core.security.RequiresPermission;
import dev.ngb.notification.dispatch.application.notification.command.GetTenantSettingsUseCase;
import dev.ngb.notification.dispatch.application.notification.command.SavePreferenceUseCase;
import dev.ngb.notification.dispatch.application.notification.command.SaveTenantSettingsUseCase;
import dev.ngb.notification.dispatch.application.notification.service.NotificationSettingsView;
import dev.ngb.notification.shared.NotificationPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The tenant's notification settings, behind {@code notification:config:manage}. */
@Tag(
        name = "Notification settings",
        description =
                "A tenant's default locale and the notification types it has enabled.")
@RestController
@RequestMapping("/api/v1/settings")
class TenantSettingsController {

    private final GetTenantSettingsUseCase get;
    private final SaveTenantSettingsUseCase save;

    TenantSettingsController(GetTenantSettingsUseCase get, SaveTenantSettingsUseCase save) {
        this.get = get;
        this.save = save;
    }

    @Operation(
            summary = "Get the tenant's notification settings",
            description =
                    "Returns the tenant's default locale and the notification types it has enabled. The tenant is taken from the request host. Requires `notification:config:manage`.")
    @GetMapping
    @RequiresPermission(NotificationPermissions.CONFIG_MANAGE)
    SettingsResponse current() {
        return SettingsResponse.of(get.current());
    }

    @Operation(
            summary = "Replace the tenant's notification settings",
            description =
                    "Stores the tenant's default locale and the full set of enabled notification types, replacing what was stored; a type missing from `enabledTypes` is no longer enabled. A locale the suite does not support, or a notification type it does not know, answers `400`. Answers the stored settings. Requires `notification:config:manage`.")
    @PutMapping
    @RequiresPermission(NotificationPermissions.CONFIG_MANAGE)
    SettingsResponse save(@RequestBody SettingsRequest request) {
        return SettingsResponse.of(
                save.save(request.defaultLocale(), SavePreferenceUseCase.parseTypes(request.enabledTypes())));
    }

    record SettingsRequest(String defaultLocale, Set<String> enabledTypes) {}

    record SettingsResponse(String defaultLocale, Set<String> enabledTypes) {

        static SettingsResponse of(NotificationSettingsView view) {
            return new SettingsResponse(
                    view.defaultLocale(),
                    view.enabledTypes().stream().map(Enum::name).collect(Collectors.toSet()));
        }
    }
}
