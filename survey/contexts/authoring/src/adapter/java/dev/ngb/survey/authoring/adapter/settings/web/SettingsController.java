package dev.ngb.survey.authoring.adapter.settings.web;

import dev.ngb.core.security.RequiresPermission;
import dev.ngb.survey.authoring.application.settings.command.UpdateSurveySettingsUseCase;

import dev.ngb.survey.authoring.application.settings.query.SurveySettingsQueries;
import dev.ngb.survey.shared.SurveyPermissions;
import java.time.Duration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant's survey defaults: the abandonment window, the upload limit, the public rate limit and the time zone.
 *
 * <p>A read answers the configured defaults before the tenant ever changed one, so a settings screen always has
 * something to show. `settings:manage` is the one permission a Tenant System credential never holds, because a
 * machine that changes its own limits could lift them.
 */
@RestController
@RequestMapping("/api/v1/settings")
class SettingsController {

    private final SurveySettingsQueries queries;
    private final UpdateSurveySettingsUseCase update;

    SettingsController(SurveySettingsQueries queries, UpdateSurveySettingsUseCase update) {
        this.queries = queries;
        this.update = update;
    }

    @GetMapping
    @RequiresPermission(SurveyPermissions.SETTINGS_MANAGE)
    SettingsResponse get() {
        return SettingsResponse.of(queries.get());
    }

    @PatchMapping
    @RequiresPermission(SurveyPermissions.SETTINGS_MANAGE)
    SettingsResponse change(@RequestBody ChangeRequest request) {
        return SettingsResponse.of(update.execute(new UpdateSurveySettingsUseCase.Command(
                request.defaultLocale(),
                request.abandonAfter(),
                request.uploadMaxBytes(),
                request.publicRatePerMinute(),
                request.timezone())));
    }

    /** A partial change: a null field keeps the current value. */
    record ChangeRequest(
            String defaultLocale, Duration abandonAfter, Long uploadMaxBytes, Integer publicRatePerMinute,
            String timezone) {}

    /** The settings as the API returns them, with the version a conditional change compares. */
    record SettingsResponse(
            String defaultLocale,
            Duration abandonAfter,
            Long uploadMaxBytes,
            Integer publicRatePerMinute,
            String timezone,
            long version) {

        static SettingsResponse of(dev.ngb.survey.authoring.application.settings.query.SurveySettingsReadRepository.SettingsView view) {
            return new SettingsResponse(
                    view.defaultLocale(),
                    view.abandonAfter(),
                    view.uploadMaxBytes(),
                    view.publicRatePerMinute(),
                    view.timezone(),
                    view.version());
        }

        static SettingsResponse of(dev.ngb.survey.authoring.application.settings.port.TenantSurveySettings settings) {
            return new SettingsResponse(
                    settings.defaultLocale(),
                    settings.abandonAfter(),
                    settings.uploadMaxBytes(),
                    settings.publicRatePerMinute(),
                    settings.timezone(),
                    settings.version());
        }
    }
}
