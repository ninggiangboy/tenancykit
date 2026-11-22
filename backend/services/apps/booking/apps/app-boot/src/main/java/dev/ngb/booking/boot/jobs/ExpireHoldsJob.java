package dev.ngb.booking.boot.jobs;

import dev.ngb.booking.booking.application.booking.command.ExpireHoldsUseCase;
import dev.ngb.booking.boot.config.BookingSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reclaims every tenant's lapsed holds on a fixed delay, so a hold nobody confirmed releases its claim and stops
 * holding a place.
 *
 * <p>It runs in the worker runtime only, and each tenant inside its own scope through {@link TenantSweep}, because
 * row-level security means a job can see one tenant at a time. The claim path frees a lapsed hold lazily too, so this
 * job is what makes the release timely rather than the only thing that makes it happen.
 */
@Profile("worker")
@Component
public class ExpireHoldsJob {

    private static final Logger LOG = LoggerFactory.getLogger(ExpireHoldsJob.class);

    private final TenantSweep tenants;
    private final ExpireHoldsUseCase expirations;
    private final BookingSettings settings;

    ExpireHoldsJob(TenantSweep tenants, ExpireHoldsUseCase expirations, BookingSettings settings) {
        this.tenants = tenants;
        this.expirations = expirations;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${booking.jobs.expire-holds-interval-ms:60000}")
    public void run() {
        if (!settings.jobs().enabled()) {
            return;
        }
        tenants.forEachActiveTenant(tenant -> {
            int expired = expirations.execute();
            if (expired > 0) {
                LOG.info("expired {} lapsed holds for tenant {}", expired, tenant);
            }
        });
    }
}
