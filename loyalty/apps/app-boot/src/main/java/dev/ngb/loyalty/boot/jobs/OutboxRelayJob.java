package dev.ngb.loyalty.boot.jobs;

import dev.ngb.core.messaging.outbox.OutboxRelay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One in-process relay round per poll, for every active tenant.
 *
 * <p>The outbox rows carry the facts a member's points, tier or vouchers changed; a use case writes them inside its own
 * transaction and the relay hands them to the webhook listener, which queues the deliveries. The job does nothing when
 * {@code loyalty.jobs.enabled} is false: the rows stay {@code PENDING} and are delivered when it is switched back on.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "loyalty.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayJob {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelayJob.class);

    private final TenantSweep sweep;
    private final OutboxRelay relay;

    OutboxRelayJob(TenantSweep sweep, OutboxRelay relay) {
        this.sweep = sweep;
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${loyalty.relay.poll-delay:PT2S}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            OutboxRelay.Result result = relay.relayDue();
            if (result.delivered() > 0 || result.failed() > 0 || result.dead() > 0) {
                // Counts only: never a member, never an amount.
                LOG.info(
                        "tenant {}: relayed {} event(s), {} retrying, {} dead",
                        tenantId, result.delivered(), result.failed(), result.dead());
            }
        });
    }
}
