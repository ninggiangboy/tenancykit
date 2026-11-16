package dev.ngb.loyalty.boot.jobs;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.boot.infrastructure.lease.JobLease;
import dev.ngb.loyalty.points.application.expiry.command.ExpirePointsUseCase;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes off expired lots, one tenant slice at a time, hourly by default.
 *
 * <p>The schedule never decides what a member can spend: an expired lot already counts for nothing, so a late or
 * repeated run corrects the ledger and nothing else. A lease keeps two instances off the same slice; even if it expires
 * mid-run, the per-member locks mean the second pass finds nothing left to write off.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "loyalty.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class PointExpiryJob {

    private static final Logger LOG = LoggerFactory.getLogger(PointExpiryJob.class);

    /** Long enough for one bounded batch of members, short enough that a dead worker's slice is picked up. */
    private static final Duration LEASE_TTL = Duration.ofMinutes(10);

    private static final String LEASE = "point-expiry";

    private final TenantSweep sweep;
    private final JobLease lease;
    private final ExpirePointsUseCase expire;

    PointExpiryJob(TenantSweep sweep, JobLease lease, ExpirePointsUseCase expire) {
        this.sweep = sweep;
        this.lease = lease;
        this.expire = expire;
    }

    @Scheduled(fixedDelayString = "${loyalty.expiry.interval:PT1H}")
    public void run() {
        sweep.forEachActiveTenant(this::expireTenant);
    }

    private void expireTenant(TenantId tenantId) {
        String slice = LEASE + "/" + tenantId.value();
        if (!lease.tryAcquire(slice, LEASE_TTL)) {
            return;
        }
        try {
            ExpirePointsUseCase.Result result = expire.runOnce();
            if (result.lots() > 0) {
                // Counts only: never a member, never an amount per person.
                LOG.info(
                        "tenant {}: wrote off {} expired lot(s) holding {} point(s) across {} member(s)",
                        tenantId, result.lots(), result.points(), result.members());
            }
        } finally {
            lease.release(slice);
        }
    }
}
