package dev.ngb.survey.boot.jobs;

import dev.ngb.survey.boot.config.SurveySettings;
import dev.ngb.survey.collection.application.upload.command.PurgePendingUploadsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes the pending uploads a respondent abandoned, and their objects.
 *
 * <p>It runs once per tenant through {@link TenantSweep}, because row-level security means a job can only see one
 * tenant at a time. The job is bounded: each sweep removes at most {@code batchSize} rows, so a large backlog is
 * several runs rather than one that holds a connection for minutes.
 */
@Profile("worker")
@Component
class OrphanedUploadSweep {

    private static final Logger LOG = LoggerFactory.getLogger(OrphanedUploadSweep.class);

    private final TenantSweep sweep;
    private final PurgePendingUploadsUseCase purge;
    private final SurveySettings settings;

    OrphanedUploadSweep(TenantSweep sweep, PurgePendingUploadsUseCase purge, SurveySettings settings) {
        this.sweep = sweep;
        this.purge = purge;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${survey.housekeeping.interval:PT1H}")
    void purge() {
        if (!settings.housekeeping().enabled()) {
            return;
        }
        sweep.forEachActiveTenant(tenant -> {
            int deleted = purge.execute(
                    settings.housekeeping().orphanedUploadRetention(), settings.housekeeping().batchSize());
            if (deleted > 0) {
                LOG.info("deleted {} orphaned uploads for a tenant", deleted);
            }
        });
    }
}
