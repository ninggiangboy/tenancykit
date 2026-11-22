package dev.ngb.mailroom.boot.jobs;

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
 * <p>The outbox rows carry {@code email.send} and are written by the sending use cases inside their own transaction.
 * The job does nothing when {@code email.jobs.enabled} is false: the rows stay {@code PENDING} and go out when it is
 * switched back on.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "mailroom.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayJob {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelayJob.class);

    private final TenantSweep sweep;
    private final BrokerRelay relay;

    OutboxRelayJob(TenantSweep sweep, BrokerRelay relay) {
        this.sweep = sweep;
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${email.relay.poll-delay:PT2S}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            BrokerRelay.Result result = relay.relayDue();
            if (result.published() > 0 || result.failed() > 0 || result.dead() > 0) {
                // Counts only: never a recipient, never a subject, never a body.
                LOG.info(
                        "tenant {}: published {} command(s), {} retrying, {} dead",
                        tenantId, result.published(), result.failed(), result.dead());
            }
        });
    }
}
