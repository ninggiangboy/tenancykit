package dev.ngb.notification.dispatch.adapter.notification.web;

import dev.ngb.core.security.Authenticated;
import dev.ngb.notification.dispatch.application.notification.command.GetPreferenceUseCase;
import dev.ngb.notification.dispatch.application.notification.command.SavePreferenceUseCase;
import dev.ngb.notification.dispatch.application.notification.service.PreferenceView;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own locale and muted types; the user always comes from the token. */
@RestController
@RequestMapping("/api/v1/notifications/preferences")
class NotificationPreferenceController {

    private final GetPreferenceUseCase get;
    private final SavePreferenceUseCase save;

    NotificationPreferenceController(GetPreferenceUseCase get, SavePreferenceUseCase save) {
        this.get = get;
        this.save = save;
    }

    @GetMapping
    @Authenticated
    PreferenceResponse current() {
        return PreferenceResponse.of(get.current());
    }

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
