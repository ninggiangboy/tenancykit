package dev.ngb.booking.catalog.application.offering.command;

import dev.ngb.booking.catalog.application.offering.port.OfferingRepository;
import dev.ngb.booking.catalog.domain.offering.Offering;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import java.util.UUID;

/**
 * {@code DELETE /offerings/{id}}: an offering is never removed, because bookings point at it, so it becomes
 * {@code ARCHIVED}. Archiving an archived offering changes nothing and bumps no version, so a retried call answers what
 * the first one left behind.
 */
public class ArchiveOfferingUseCase {

    private final UnitOfWork unitOfWork;
    private final OfferingRepository offerings;
    private final Clock clock;

    public ArchiveOfferingUseCase(UnitOfWork unitOfWork, OfferingRepository offerings, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.offerings = offerings;
        this.clock = clock;
    }

    public void execute(UUID offeringId, ExpectedVersion expected) {
        unitOfWork.transactional(() -> {
            Offering offering = offerings
                    .lockById(offeringId)
                    .orElseThrow(() -> new NotFoundException("No offering exists here with that id."));
            expected.check(offering.version());
            if (offering.archive(clock.now())) {
                offerings.archive(offering);
            }
        });
    }
}
