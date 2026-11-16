package dev.ngb.survey.boot.jobs;

import dev.ngb.survey.insight.application.export.command.RunExportUseCase;
import dev.ngb.survey.insight.application.export.port.ExportRepository;
import dev.ngb.survey.insight.domain.export.Export;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Produces the CSVs a Tenant queued.
 *
 * <p>Unlike the sweep jobs, this one is not per tenant: an export carries its own tenant, and the claim's conditional
 * update is what stops two workers from producing the same file. It claims one export at a time so a large survey's
 * export does not starve the others.
 */
@Profile("worker")
@Component
class ExportJob {

    private static final Logger LOG = LoggerFactory.getLogger(ExportJob.class);

    private final ExportRepository exports;
    private final RunExportUseCase run;

    ExportJob(ExportRepository exports, RunExportUseCase run) {
        this.exports = exports;
        this.run = run;
    }

    @Scheduled(fixedDelayString = "${survey.exports.poll-delay:PT5S}")
    void runQueued() {
        List<Export> queued = exports.queued(1);
        for (Export export : queued) {
            run.execute(export.id());
        }
    }
}
