package dev.ngb.booking.catalog.application.offering.command;

import dev.ngb.booking.catalog.application.offering.port.OfferingRepository;
import dev.ngb.booking.catalog.application.offering.service.OfferingRules;
import dev.ngb.booking.catalog.domain.offering.Offering;
import dev.ngb.booking.catalog.domain.offering.OfferingStatus;
import dev.ngb.booking.catalog.domain.offering.QueueMode;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import java.util.UUID;

/**
 * {@code PATCH /offerings/{id}}: only the properties the caller sent change, and only if the offering is still the
 * version the caller read. A null property is left as it is, which is why clearing one is not something this call does.
 */
public class PatchOfferingUseCase {

    /** A change request: a null property is left as it is. */
    public record Change(
            String name,
            OfferingStatus status,
            Integer durationMin,
            Integer durationMax,
            Integer durationStep,
            Integer slotStep,
            Integer bufferBefore,
            Integer bufferAfter,
            Integer leadTimeSeconds,
            Integer horizonDays,
            Integer holdTtlSeconds,
            Integer maxQty,
            Boolean publicShowsRemaining,
            QueueMode queueMode,
            Integer admitPerSecond,
            Integer admitWindowSeconds) {}

    private final UnitOfWork unitOfWork;
    private final OfferingRepository offerings;
    private final Clock clock;

    public PatchOfferingUseCase(UnitOfWork unitOfWork, OfferingRepository offerings, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.offerings = offerings;
        this.clock = clock;
    }

    public Offering execute(UUID offeringId, Change change, ExpectedVersion expected) {
        return unitOfWork.transactional(() -> {
            Offering offering = offerings
                    .lockById(offeringId)
                    .orElseThrow(() -> new NotFoundException("No offering exists here with that id."));
            expected.check(offering.version());
            int durationMin = change.durationMin() == null
                    ? offering.durationMin()
                    : OfferingRules.requiredPositive(change.durationMin(), "durationMin");
            Integer durationMax = OfferingRules.durationMax(
                    change.durationMax() == null ? offering.durationMax() : change.durationMax(), durationMin);
            boolean changed = offering.change(
                    change.status() == null ? offering.status() : change.status(),
                    change.name() == null ? offering.name() : OfferingRules.name(change.name()),
                    durationMin,
                    durationMax,
                    change.durationStep() == null
                            ? offering.durationStep()
                            : OfferingRules.positive(change.durationStep(), 1, "durationStep"),
                    change.slotStep() == null
                            ? offering.slotStep()
                            : OfferingRules.requiredPositive(change.slotStep(), "slotStep"),
                    change.bufferBefore() == null
                            ? offering.bufferBefore()
                            : OfferingRules.nonNegative(change.bufferBefore(), 0, "bufferBefore"),
                    change.bufferAfter() == null
                            ? offering.bufferAfter()
                            : OfferingRules.nonNegative(change.bufferAfter(), 0, "bufferAfter"),
                    change.leadTimeSeconds() == null
                            ? offering.leadTimeSeconds()
                            : OfferingRules.nonNegative(change.leadTimeSeconds(), 0, "leadTimeSeconds"),
                    change.horizonDays() == null
                            ? offering.horizonDays()
                            : OfferingRules.requiredPositive(change.horizonDays(), "horizonDays"),
                    change.holdTtlSeconds() == null
                            ? offering.holdTtlSeconds()
                            : OfferingRules.nullablePositive(change.holdTtlSeconds(), "holdTtlSeconds"),
                    change.maxQty() == null
                            ? offering.maxQty()
                            : OfferingRules.positive(change.maxQty(), 1, "maxQty"),
                    change.publicShowsRemaining() == null
                            ? offering.publicShowsRemaining()
                            : change.publicShowsRemaining(),
                    change.queueMode() == null ? offering.queueMode() : change.queueMode(),
                    change.admitPerSecond() == null
                            ? offering.admitPerSecond()
                            : OfferingRules.positive(change.admitPerSecond(), 50, "admitPerSecond"),
                    change.admitWindowSeconds() == null
                            ? offering.admitWindowSeconds()
                            : OfferingRules.positive(change.admitWindowSeconds(), 120, "admitWindowSeconds"),
                    clock.now());
            if (changed) {
                offerings.update(offering);
            }
            return offering;
        });
    }
}
