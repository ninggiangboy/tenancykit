package dev.ngb.survey.authoring.application.settings.command;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.survey.authoring.application.settings.port.SurveySettingsRepository;
import dev.ngb.survey.authoring.application.settings.port.TenantSurveySettings;
import java.time.Duration;
import java.util.UUID;

/**
 * Changes the tenant's survey defaults: the locale a new version starts from, the abandonment window, the upload limit
 * and the public rate limit.
 *
 * <p>A tenant's settings are one row, so this updates or inserts it whole. A null field keeps the current value, which
 * is what makes a partial change from a settings screen safe.
 */
public class UpdateSurveySettingsUseCase {

    /** The change request; a null field keeps the current value. */
    public record Command(
            String defaultLocale, Duration abandonAfter, Long uploadMaxBytes, Integer publicRatePerMinute, String timezone) {}

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final SurveySettingsRepository settings;

    public UpdateSurveySettingsUseCase(
            UnitOfWork unitOfWork, TenantContext tenantContext, SurveySettingsRepository settings) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.settings = settings;
    }

    public TenantSurveySettings execute(Command command) {
        return unitOfWork.transactional(() -> {
            UUID tenantId = tenantContext.requireTenant().value();
            TenantSurveySettings current =
                    settings.find(tenantId).orElseGet(() -> TenantSurveySettings.defaults(tenantId));
            TenantSurveySettings changed = current.changed(
                    command.defaultLocale(),
                    command.abandonAfter(),
                    command.uploadMaxBytes(),
                    command.publicRatePerMinute(),
                    command.timezone());
            settings.save(changed);
            return changed;
        });
    }
}
