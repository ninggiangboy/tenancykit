package dev.ngb.mailer.boot.jobs;

import dev.ngb.core.messaging.broker.BrokerRelay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One broker relay round per poll, for every tenant mailer has work for.
 *
 * <p>The delivery result is written to the outbox in the outcome transaction. This job is the only thing that publishes
 * it, and only the worker runs it, so an api-shaped runtime cannot put a result on the broker. It does nothing when
 * {@code mailer.jobs.enabled} is false: the rows stay {@code PENDING} and go out when it is switched back on.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "mailer.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayJob {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelayJob.class);

    private final TenantSweep sweep;
    private final BrokerRelay relay;

    OutboxRelayJob(TenantSweep sweep, BrokerRelay relay) {
        this.sweep = sweep;
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${mailer.delivery.poll-delay:PT2S}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            BrokerRelay.Result result = relay.relayDue();
            if (result.published() > 0 || result.failed() > 0 || result.dead() > 0) {
                // Counts only: never a recipient, never a subject, never a body.
                LOG.info(
                        "tenant {}: published {} result(s), {} retrying, {} dead",
                        tenantId, result.published(), result.failed(), result.dead());
            }
        });
    }
}
