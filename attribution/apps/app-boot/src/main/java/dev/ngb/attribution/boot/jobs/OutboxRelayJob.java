package dev.ngb.attribution.boot.jobs;

import dev.ngb.core.messaging.broker.BrokerRelay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One broker relay round per poll, for every active tenant.
 *
 * <p>No publisher exists in v1, so a round normally publishes nothing; the job is present because the baseline copies
 * the relay and a later phase must not have to add the plumbing to make an event leave. It does nothing when
 * {@code attribution.jobs.enabled} is false.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "attribution.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayJob {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelayJob.class);

    private final TenantSweep sweep;
    private final BrokerRelay relay;

    OutboxRelayJob(TenantSweep sweep, BrokerRelay relay) {
        this.sweep = sweep;
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${attribution.relay.poll-delay:PT2S}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            BrokerRelay.Result result = relay.relayDue();
            if (result.published() > 0 || result.failed() > 0 || result.dead() > 0) {
                LOG.info(
                        "tenant {}: published {} event(s), {} retrying, {} dead",
                        tenantId, result.published(), result.failed(), result.dead());
            }
        });
    }
}
