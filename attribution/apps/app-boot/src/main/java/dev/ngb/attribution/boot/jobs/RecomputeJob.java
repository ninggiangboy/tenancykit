package dev.ngb.attribution.boot.jobs;

import dev.ngb.attribution.attribution.application.conversion.command.RecomputeCampaignUseCase;
import dev.ngb.attribution.attribution.application.conversion.port.RecomputeQueue;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-credits a campaign's conversions after its model or window changed.
 *
 * <p>One campaign's run at a time: the caller hands the campaigns that asked for a recompute, and each is paged by id
 * so an interrupted pass resumes from its cursor on the next tick. Credits are replaced atomically per conversion, so a
 * report never sees two models' credits beside each other.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "attribution.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class RecomputeJob {

    private static final Logger LOG = LoggerFactory.getLogger(RecomputeJob.class);

    private final RecomputeQueue queue;
    private final RecomputeCampaignUseCase recompute;

    RecomputeJob(RecomputeQueue queue, RecomputeCampaignUseCase recompute) {
        this.queue = queue;
        this.recompute = recompute;
    }

    @Scheduled(fixedDelayString = "${attribution.credits.recompute-interval:PT30S}")
    public void run() {
        RecomputeQueue.Run run = queue.next();
        if (run == null) {
            return;
        }
        try {
            RecomputeCampaignUseCase.Result result = recompute.execute(run.campaignId(), run.cursor());
            if (result.done()) {
                queue.finish(run);
                LOG.info("recompute for campaign {} is done after {} conversion(s)", run.campaignId(), result.recomputed());
            } else {
                queue.advance(run, result.cursor());
            }
        } catch (RuntimeException failure) {
            // The run keeps its cursor, so a transient failure resumes rather than restarting from the beginning.
            queue.fail(run, failure);
            LOG.warn("recompute for campaign {} failed and will resume", run.campaignId(), failure);
        }
    }
}
