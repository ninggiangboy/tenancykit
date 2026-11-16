package dev.ngb.booking.catalog.application.offering.command;

import dev.ngb.booking.catalog.application.offering.port.OfferingRepository;
import dev.ngb.booking.catalog.application.offering.service.OfferingRules;
import dev.ngb.booking.catalog.domain.offering.Offering;
import dev.ngb.booking.catalog.domain.offering.Requirement;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/**
 * {@code PUT /offerings/{id}/requirements}: the whole set is replaced at once. Every requirement names one resource or
 * one group, no resource is named twice and every named resource must still be active; a {@code CAPACITY} offering
 * carries none, because its places come with sessions.
 */
public class ReplaceRequirementsUseCase {

    public record Command(List<Requirement> requirements) {}

    private final UnitOfWork unitOfWork;
    private final OfferingRepository offerings;
    private final Clock clock;

    public ReplaceRequirementsUseCase(UnitOfWork unitOfWork, OfferingRepository offerings, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.offerings = offerings;
        this.clock = clock;
    }

    /** @return the stored requirements, ordered by slot code */
    public List<Requirement> execute(UUID offeringId, Command command) {
        List<Requirement> supplied = command.requirements() == null ? List.of() : command.requirements();
        return unitOfWork.transactional(() -> {
            Offering offering = offerings
                    .lockById(offeringId)
                    .orElseThrow(() -> new NotFoundException("No offering exists here with that id."));
            List<Requirement> requirements = OfferingRules.requirements(offering.kind(), supplied, offerings);
            if (offering.replaceRequirements(requirements, clock.now())) {
                offerings.update(offering);
            }
            return offering.requirements();
        });
    }
}
