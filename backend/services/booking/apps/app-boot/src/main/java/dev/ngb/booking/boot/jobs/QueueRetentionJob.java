package dev.ngb.booking.boot.jobs;

import dev.ngb.booking.boot.config.BookingSettings;
import dev.ngb.booking.gate.application.queue.port.QueueTicketRepository;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes tickets older than a day; a ticket holds only the customer reference and its admission state. */
@Profile("worker")
@Component
public class QueueRetentionJob {

    private static final Logger LOG = LoggerFactory.getLogger(QueueRetentionJob.class);

    private final TenantSweep sweep;
    private final QueueTicketRepository tickets;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final BookingSettings settings;

    QueueRetentionJob(
            TenantSweep sweep,
            QueueTicketRepository tickets,
            UnitOfWork unitOfWork,
            Clock clock,
            BookingSettings settings) {
        this.sweep = sweep;
        this.tickets = tickets;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.settings = settings;
    }

    @Scheduled(cron = "${booking.queue.retention-cron:0 30 3 * * *}")
    public void run() {
        if (!settings.jobs().enabled()) {
            return;
        }
        sweep.forEachActiveTenant(tenant -> {
            int purged = unitOfWork.transactional(
                    () -> tickets.purgeBefore(clock.now().minusSeconds(86_400)));
            if (purged > 0) {
                LOG.info("tenant {}: deleted {} queue ticket(s) older than a day", tenant, purged);
            }
        });
    }
}
