package dev.ngb.attribution.boot.jobs;

import dev.ngb.attribution.attribution.application.conversion.command.SettleConversionsUseCase;
import dev.ngb.attribution.attribution.application.conversion.port.ConversionJobs;
import dev.ngb.attribution.attribution.domain.conversion.Conversion;
import dev.ngb.core.runtime.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Settles the conversions that are old enough to have a settled chain.
 *
 * <p>A conversion waits a settle delay before it is credited, so a page view or a binding that names the same visitor
 * from a click that arrived seconds later is still inside the chain. Each tenant's pending conversions are claimed and
 * settled in that tenant's own scope; a conversion that fails stays pending and the next run retries it.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "attribution.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class CreditJob {

    private static final Logger LOG = LoggerFactory.getLogger(CreditJob.class);

    private final TenantSweep sweep;
    private final ConversionJobs jobs;
    private final SettleConversionsUseCase settle;
    private final Clock clock;
    private final Duration settleDelay;
    private final int batchSize;

    CreditJob(
            TenantSweep sweep,
            ConversionJobs jobs,
            SettleConversionsUseCase settle,
            Clock clock,
            @Value("${attribution.credits.settle-delay:PT5M}") Duration settleDelay,
            @Value("${attribution.credits.batch-size:200}") int batchSize) {
        this.sweep = sweep;
        this.jobs = jobs;
        this.settle = settle;
        this.clock = clock;
        this.settleDelay = settleDelay;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${attribution.credits.interval:PT1M}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            List<Conversion> pending =
                    jobs.claimPending(SettleConversionsUseCase.settleBefore(clock.now(), settleDelay), batchSize);
            if (pending.isEmpty()) {
                return;
            }
            SettleConversionsUseCase.Result result = settle.execute(pending);
            LOG.info(
                    "tenant {}: settled {} conversion(s), {} unattributed, {} retrying",
                    tenantId, result.settled(), result.unattributed(), result.failed());
        });
    }
}
