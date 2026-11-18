package dev.ngb.workflow.boot.jobs;

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
 * <p>{@link OutboxRelay} hands each due outbox row to the webhook listener inside its own transaction and marks it
 * delivered; nothing leaves workflow's process here, because workflow publishes no broker message. The job does nothing when
 * {@code workflow.jobs.enabled} is false: the rows stay {@code PENDING} and go out when it is switched back on, which is
 * what a test and an api-only runtime rely on.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "workflow.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayJob {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelayJob.class);

    private final TenantSweep sweep;
    private final OutboxRelay relay;

    OutboxRelayJob(TenantSweep sweep, OutboxRelay relay) {
        this.sweep = sweep;
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${workflow.relay.poll-delay:PT2S}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            OutboxRelay.Result result = relay.relayDue();
            if (result.delivered() > 0 || result.failed() > 0 || result.dead() > 0) {
                // Counts only: never a customer, never an order, never a payload.
                LOG.info(
                        "tenant {}: delivered {} event(s) to the consumers, {} retrying, {} dead",
                        tenantId, result.delivered(), result.failed(), result.dead());
            }
        });
    }
}
