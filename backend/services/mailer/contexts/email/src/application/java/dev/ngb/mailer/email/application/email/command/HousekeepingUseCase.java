package dev.ngb.mailer.email.application.email.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.mailer.email.application.email.port.DeliveryClaimRepository;
import dev.ngb.mailer.email.application.email.service.HousekeepingSettings;
import java.time.Instant;

/**
 * Bounded deletes that keep the retention claim true: bodies past their TTL and inbox rows past theirs.
 *
 * <p>Every delete is capped and uses {@code ctid ... limit}, so a round never holds a long lock on a large table and a
 * backlog drains over several rounds instead of one unbounded statement.
 */
public class HousekeepingUseCase {

    public record Purged(int bodies, int inbox) {}

    private final UnitOfWork unitOfWork;
    private final DeliveryClaimRepository claims;
    private final HousekeepingSettings settings;
    private final Clock clock;

    public HousekeepingUseCase(
            UnitOfWork unitOfWork, DeliveryClaimRepository claims, HousekeepingSettings settings, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.claims = claims;
        this.settings = settings;
        this.clock = clock;
    }

    public Purged runOnce() {
        return unitOfWork.transactional(() -> {
            Instant now = clock.now();
            int bodies = claims.purgeBodies(now.minus(settings.bodyRetention()), settings.batchSize());
            int inbox = claims.purgeInbox(now.minus(settings.inboxRetention()), settings.batchSize());
            return new Purged(bodies, inbox);
        });
    }
}
