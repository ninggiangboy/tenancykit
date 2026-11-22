package dev.ngb.notification.dispatch.adapter.notification.web;

import dev.ngb.core.security.Authenticated;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.notification.dispatch.application.notification.command.GetPreferenceUseCase;
import dev.ngb.notification.dispatch.application.notification.command.SavePreferenceUseCase;
import dev.ngb.notification.dispatch.application.notification.service.PreferenceView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own locale and muted types; the user always comes from the token. */
@Tag(
        name = "Notification preferences",
        description =
                "The caller's own locale and muted notification types.")
@RestController
@RequestMapping("/api/v1/notifications/preferences")
@Callers({Caller.PLATFORM_USER, Caller.TENANT_USER})
class NotificationPreferenceController {

    private final GetPreferenceUseCase get;
    private final SavePreferenceUseCase save;

    NotificationPreferenceController(GetPreferenceUseCase get, SavePreferenceUseCase save) {
        this.get = get;
        this.save = save;
    }

    @Operation(
            summary = "Get the caller's notification preferences",
            description =
                    "Returns the caller's own locale and the notification types they have muted. The user comes from the token. Any authenticated caller may read their own preferences.")
    @GetMapping
    @Authenticated
    PreferenceResponse current() {
        return PreferenceResponse.of(get.current());
    }

    @Operation(
            summary = "Replace the caller's notification preferences",
            description =
                    "Stores the caller's locale and the full set of muted notification types, replacing what was stored. A blank locale clears the caller's locale preference. A locale the suite does not support, or an unknown notification type, answers `400`. Answers the stored preferences.")
    @PutMapping
    @Authenticated
    PreferenceResponse save(@RequestBody PreferenceRequest request) {
        PreferenceView saved = save.save(request.locale(), SavePreferenceUseCase.parseTypes(request.mutedTypes()));
        return PreferenceResponse.of(saved);
    }

    record PreferenceRequest(String locale, Set<String> mutedTypes) {}

    record PreferenceResponse(String locale, Set<String> mutedTypes) {

        static PreferenceResponse of(PreferenceView view) {
            return new PreferenceResponse(
                    view.locale(), view.mutedTypes().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()));
        }
    }
}
