package dev.ngb.platform.boot.jobs;

import dev.ngb.platform.integration.adapter.token.web.PostgresRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes rate-limit buckets that have been idle past their TTL. Only one instance needs to do this; a full batch is
 * followed immediately by another, so a backlog drains without a long pause.
 */
@Component
public class RateLimitCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitCleanupJob.class);

    private final PostgresRateLimiter limiter;
    private final int batchSize;

    RateLimitCleanupJob(
            PostgresRateLimiter limiter, @Value("${platform.security.token-rate-limit.cleanup-batch:500}") int batchSize) {
        this.limiter = limiter;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${platform.security.token-rate-limit.cleanup-delay:PT5M}")
    public void removeExpired() {
        int removed;
        do {
            removed = limiter.removeExpired(batchSize);
        } while (removed >= batchSize);
        if (removed > 0) {
            LOG.debug("removed {} expired rate-limit buckets", removed);
        }
    }
}
