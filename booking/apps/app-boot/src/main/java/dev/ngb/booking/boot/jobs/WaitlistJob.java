package dev.ngb.booking.boot.jobs;

import dev.ngb.booking.booking.application.waitlist.command.OfferWaitlistUseCase;
import dev.ngb.booking.boot.config.BookingSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One matcher pass per poll, for every active tenant: wake-ups first, and a periodic scan so a place freed by a
 * resize or by a direct expiry still reaches the waiting entries even if no wake-up was written for it.
 *
 * <p>Every pass runs in its own transaction per target; the entry and the hold are both locked, so two workers never
 * offer the same pair.
 */
@Profile("worker")
@Component
public class WaitlistJob {

    private static final Logger LOG = LoggerFactory.getLogger(WaitlistJob.class);

    private final TenantSweep sweep;
    private final OfferWaitlistUseCase offers;
    private final BookingSettings settings;

    WaitlistJob(TenantSweep sweep, OfferWaitlistUseCase offers, BookingSettings settings) {
        this.sweep = sweep;
        this.offers = offers;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${booking.relay.poll-delay:PT2S}")
    public void run() {
        if (!settings.jobs().enabled()) {
            return;
        }
        sweep.forEachActiveTenant(tenantId -> {
            int offered = offers.execute();
            if (offered > 0) {
                LOG.info("tenant {}: offered {} waitlist entry(ies) a hold", tenantId, offered);
            }
        });
    }
}
