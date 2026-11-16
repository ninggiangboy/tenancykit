package dev.ngb.survey.boot.jobs;

import dev.ngb.survey.boot.config.SurveySettings;
import dev.ngb.survey.collection.application.visit.command.AbandonIdleVisitsUseCase;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Marks the visits that stopped as abandoned, which is the funnel's fourth number.
 *
 * <p>It runs once per tenant through {@link TenantSweep}, because row-level security means a job sees one tenant at a
 * time. The threshold is the tenant setting's abandonment window, so a Tenant that wants a shorter one sets it once.
 */
@Profile("worker")
@Component
class AbandonedVisitSweep {

    private final TenantSweep sweep;
    private final AbandonIdleVisitsUseCase abandon;
    private final SurveySettings settings;

    AbandonedVisitSweep(
            TenantSweep sweep, AbandonIdleVisitsUseCase abandon, SurveySettings settings) {
        this.sweep = sweep;
        this.abandon = abandon;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${survey.housekeeping.interval:PT1H}")
    void abandon() {
        if (!settings.housekeeping().enabled()) {
            return;
        }
        sweep.forEachActiveTenant(tenant -> abandon.execute(
                settings.housekeeping().abandonedVisitAfter(), settings.housekeeping().batchSize()));
    }
}
