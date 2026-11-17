package dev.ngb.booking.boot.jobs;

import dev.ngb.booking.boot.config.BookingSettings;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One in-process relay round per poll, for every active tenant.
 *
 * <p>{@link OutboxRelay} hands each due outbox row to the webhook listener inside its own transaction and marks it
 * delivered; nothing leaves booking's process here, because booking publishes no broker message. The job does nothing
 * when {@code booking.jobs.enabled} is false: the rows stay {@code PENDING} and go out when it is switched back on.
 */
@Profile("worker")
@Component
public class OutboxRelayJob {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelayJob.class);

    private final TenantSweep sweep;
    private final OutboxRelay relay;
    private final BookingSettings settings;

    OutboxRelayJob(TenantSweep sweep, OutboxRelay relay, BookingSettings settings) {
        this.sweep = sweep;
        this.relay = relay;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${booking.relay.poll-delay:PT2S}")
    public void run() {
        if (!settings.jobs().enabled()) {
            return;
        }
        sweep.forEachActiveTenant(tenantId -> {
            OutboxRelay.Result result = relay.relayDue();
            if (result.delivered() > 0 || result.failed() > 0 || result.dead() > 0) {
                // Counts only: never a customer, never a booking payload.
                LOG.info(
                        "tenant {}: delivered {} event(s) to the consumers, {} retrying, {} dead",
                        tenantId, result.delivered(), result.failed(), result.dead());
            }
        });
    }
}
