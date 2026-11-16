package dev.ngb.booking.catalog.application.offering.command;

import dev.ngb.booking.catalog.application.offering.port.OfferingRepository;
import dev.ngb.booking.catalog.application.offering.service.OfferingRules;
import dev.ngb.booking.catalog.domain.offering.CancellationWindow;
import dev.ngb.booking.catalog.domain.offering.Offering;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/**
 * {@code PUT /offerings/{id}/cancellation-windows}: the whole set of thresholds is replaced at once. Each carries the
 * Tenant's own code and how long before the start it applies; no two thresholds share a minute. booking stores no
 * amount and charges nothing.
 */
public class ReplaceCancellationWindowsUseCase {

    public record Command(List<CancellationWindow> windows) {}

    private final UnitOfWork unitOfWork;
    private final OfferingRepository offerings;
    private final Clock clock;

    public ReplaceCancellationWindowsUseCase(UnitOfWork unitOfWork, OfferingRepository offerings, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.offerings = offerings;
        this.clock = clock;
    }

    /** @return the stored thresholds, longest notice first */
    public List<CancellationWindow> execute(UUID offeringId, Command command) {
        List<CancellationWindow> supplied = command.windows() == null ? List.of() : command.windows();
        return unitOfWork.transactional(() -> {
            Offering offering = offerings
                    .lockById(offeringId)
                    .orElseThrow(() -> new NotFoundException("No offering exists here with that id."));
            List<CancellationWindow> windows = OfferingRules.cancellationWindows(supplied);
            if (offering.replaceCancellationWindows(windows, clock.now())) {
                offerings.update(offering);
            }
            return offering.cancellationWindows();
        });
    }
}
