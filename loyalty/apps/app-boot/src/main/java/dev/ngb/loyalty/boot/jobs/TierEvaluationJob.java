package dev.ngb.loyalty.boot.jobs;

import dev.ngb.loyalty.boot.config.LoyaltySettings;
import dev.ngb.loyalty.tiers.application.evaluation.service.TierEvaluationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily tier evaluation, for every active tenant.
 *
 * <p>It is the only place a member moves down a tier: an earn raises one immediately, and a window that slid past a
 * member's orders or a tier list that changed is what this pass corrects. It first takes a changed tier list to every
 * member and then decides the members whose review is due, so a restart mid-way resumes from the list's mark rather than
 * starting the pass over.
 *
 * <p>The rows carry the fact; the outbox relay hands it to the webhook context, which queues one delivery per endpoint
 * subscribed to {@code tier.changed}. The job does nothing while {@code loyalty.jobs.enabled} is false.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "loyalty.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class TierEvaluationJob {

    private static final Logger LOG = LoggerFactory.getLogger(TierEvaluationJob.class);

    private final TenantSweep sweep;
    private final TierEvaluationService evaluation;
    private final LoyaltySettings settings;

    TierEvaluationJob(TenantSweep sweep, TierEvaluationService evaluation, LoyaltySettings settings) {
        this.sweep = sweep;
        this.evaluation = evaluation;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${loyalty.tier-evaluation.interval:P1D}")
    public void run() {
        int batch = settings.tierEvaluation().batchSize();
        sweep.forEachActiveTenant(tenantId -> {
            TierEvaluationService.Report report = evaluation.reviewDue();
            if (report.changed() > 0) {
                // Counts only: never a member, never an amount.
                LOG.info(
                        "tenant {}: {} member(s) reviewed in batches of {}, {} tier(s) changed",
                        tenantId, report.reviewed(), batch, report.changed());
            }
        });
    }
}
