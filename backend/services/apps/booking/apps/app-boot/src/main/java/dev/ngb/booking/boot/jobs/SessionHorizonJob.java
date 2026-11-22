package dev.ngb.booking.boot.jobs;

import dev.ngb.booking.boot.config.BookingSettings;
import dev.ngb.booking.catalog.application.session.command.MaterializeHorizonUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rolls every active session series forward once a day, so the sessions a Tenant sells always exist as rows inside the
 * horizon.
 *
 * <p>It runs in the worker runtime only, and it runs each tenant inside its own scope through {@link TenantSweep},
 * because row-level security means a job can see one tenant at a time. A pass inserts nothing for an occurrence that is
 * already there, so running it twice in a day, or a day late, changes nothing.
 */
@Profile("worker")
@Component
public class SessionHorizonJob {

    private static final Logger LOG = LoggerFactory.getLogger(SessionHorizonJob.class);

    private final TenantSweep tenants;
    private final MaterializeHorizonUseCase materialize;
    private final BookingSettings settings;

    SessionHorizonJob(TenantSweep tenants, MaterializeHorizonUseCase materialize, BookingSettings settings) {
        this.tenants = tenants;
        this.materialize = materialize;
        this.settings = settings;
    }

    @Scheduled(cron = "0 15 3 * * *")
    public void run() {
        if (!settings.jobs().enabled()) {
            return;
        }
        tenants.forEachActiveTenant(tenant -> {
            MaterializeHorizonUseCase.Result result = materialize.execute();
            LOG.info(
                    "session horizon for tenant {}: {} series, {} sessions inserted",
                    tenant,
                    result.series(),
                    result.sessions());
        });
    }
}
