package dev.ngb.loyalty.boot.jobs;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.boot.infrastructure.lease.JobLease;
import dev.ngb.loyalty.points.application.expiry.command.NoticeExpiringPointsUseCase;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Announces the lots that came into the program's notice window, one tenant slice at a time, daily by default.
 *
 * <p>It changes nothing a member holds: a lot is announced once and the Tenant decides whether and how to tell the
 * member. A tenant whose notice window is zero is skipped by the run itself, so the job needs no knowledge of the
 * program. The lease keeps two instances off the same slice even when both tick at the same moment.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "loyalty.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ExpiryNoticeJob {

    private static final Logger LOG = LoggerFactory.getLogger(ExpiryNoticeJob.class);

    private static final Duration LEASE_TTL = Duration.ofMinutes(10);

    private static final String LEASE = "expiry-notice";

    private final TenantSweep sweep;
    private final JobLease lease;
    private final NoticeExpiringPointsUseCase notice;

    ExpiryNoticeJob(TenantSweep sweep, JobLease lease, NoticeExpiringPointsUseCase notice) {
        this.sweep = sweep;
        this.lease = lease;
        this.notice = notice;
    }

    @Scheduled(fixedDelayString = "${loyalty.expiry.notice-interval:P1D}")
    public void run() {
        sweep.forEachActiveTenant(this::noticeTenant);
    }

    private void noticeTenant(TenantId tenantId) {
        String slice = LEASE + "/" + tenantId.value();
        if (!lease.tryAcquire(slice, LEASE_TTL)) {
            return;
        }
        try {
            NoticeExpiringPointsUseCase.Result result = notice.runOnce();
            if (result.lots() > 0) {
                LOG.info(
                        "tenant {}: announced {} lot(s) holding {} point(s) across {} member(s)",
                        tenantId, result.lots(), result.points(), result.members());
            }
        } finally {
            lease.release(slice);
        }
    }
}
