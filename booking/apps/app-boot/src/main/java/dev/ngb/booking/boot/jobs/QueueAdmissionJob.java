package dev.ngb.booking.boot.jobs;

import dev.ngb.booking.boot.config.BookingSettings;
import dev.ngb.booking.gate.application.queue.port.QueueTicketRepository;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Admits waiting tickets in issue order at each target's rate, and expires admissions past their window.
 *
 * <p>One batch update per target and one watermark advance in the same transaction; a restart therefore neither skips
 * nor repeats a ticket. The elapsed time is capped so a long pause does not admit an unbounded burst at once.
 */
@Profile("worker")
@Component
public class QueueAdmissionJob {

    private static final Logger LOG = LoggerFactory.getLogger(QueueAdmissionJob.class);
    private static final Duration MAX_ELAPSED = Duration.ofSeconds(5);

    private final TenantSweep sweep;
    private final QueueTicketRepository tickets;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final BookingSettings settings;

    QueueAdmissionJob(
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

    @Scheduled(fixedDelayString = "${booking.relay.poll-delay:PT2S}")
    public void run() {
        if (!settings.jobs().enabled()) {
            return;
        }
        sweep.forEachActiveTenant(tenant -> {
            List<String> targets = unitOfWork.transactional(tickets::targetsWithWaiting);
            for (String target : targets) {
                int admitted = unitOfWork.transactional(() -> {
                    Instant now = clock.now();
                    QueueTicketRepository.State state = tickets.state(target).orElse(null);
                    if (state == null) {
                        return 0;
                    }
                    long elapsedSeconds = Math.max(
                            1,
                            Math.min(
                                    MAX_ELAPSED.toSeconds(),
                                    Duration.between(state.updatedAt(), now).toSeconds()));
                    int limit = (int) Math.max(1, state.admitPerSecond() * elapsedSeconds);
                    int count = tickets.admit(target, limit, now);
                    tickets.expireAdmitted(target, now.minusSeconds(state.admitWindowSeconds()), now);
                    return count;
                });
                if (admitted > 0) {
                    LOG.info("tenant {}: admitted {} queue ticket(s) for {}", tenant, admitted, target);
                }
            }
        });
    }
}
