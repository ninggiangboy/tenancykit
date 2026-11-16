package dev.ngb.booking.catalog.application.offering.command;

import dev.ngb.booking.catalog.application.catalog.exception.DuplicateCodeException;
import dev.ngb.booking.catalog.application.offering.port.OfferingRepository;
import dev.ngb.booking.catalog.application.offering.service.OfferingRules;
import dev.ngb.booking.catalog.domain.offering.Offering;
import dev.ngb.booking.catalog.domain.offering.OfferingId;
import dev.ngb.booking.catalog.domain.offering.OfferingKind;
import dev.ngb.booking.catalog.domain.offering.QueueMode;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;

/**
 * Creates an offering in {@code DRAFT}. The code is the Tenant's own and unique per tenant, and an {@code INTERVAL}
 * offering is allowed to carry no requirements yet — they arrive through their own endpoint, which is also where a
 * {@code CAPACITY} offering is refused them.
 */
public class CreateOfferingUseCase {

    /** An offering as the Tenant sent it; every optional number falls back to the documented default. */
    public record Command(
            String code,
            String name,
            OfferingKind kind,
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
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateOfferingUseCase(
            UnitOfWork unitOfWork,
            OfferingRepository offerings,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.offerings = offerings;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Offering execute(Command command) {
        String code = OfferingRules.code(command.code());
        String name = OfferingRules.name(command.name());
        OfferingKind kind = OfferingRules.kind(command.kind());
        int durationMin = OfferingRules.requiredPositive(command.durationMin(), "durationMin");
        Integer durationMax = OfferingRules.durationMax(command.durationMax(), durationMin);
        int durationStep = OfferingRules.positive(command.durationStep(), 1, "durationStep");
        int slotStep = OfferingRules.requiredPositive(command.slotStep(), "slotStep");
        int bufferBefore = OfferingRules.nonNegative(command.bufferBefore(), 0, "bufferBefore");
        int bufferAfter = OfferingRules.nonNegative(command.bufferAfter(), 0, "bufferAfter");
        int leadTimeSeconds = OfferingRules.nonNegative(command.leadTimeSeconds(), 0, "leadTimeSeconds");
        int horizonDays = OfferingRules.requiredPositive(command.horizonDays(), "horizonDays");
        Integer holdTtlSeconds = OfferingRules.nullablePositive(command.holdTtlSeconds(), "holdTtlSeconds");
        int maxQty = OfferingRules.positive(command.maxQty(), 1, "maxQty");
        boolean publicShowsRemaining = OfferingRules.flag(command.publicShowsRemaining(), false);
        QueueMode queueMode = OfferingRules.queueMode(command.queueMode(), QueueMode.NONE);
        int admitPerSecond = OfferingRules.positive(command.admitPerSecond(), 50, "admitPerSecond");
        int admitWindowSeconds = OfferingRules.positive(command.admitWindowSeconds(), 120, "admitWindowSeconds");
        return unitOfWork.transactional(() -> {
            if (offerings.existsByCode(code)) {
                throw new DuplicateCodeException(code);
            }
            Offering offering = Offering.create(
                    new OfferingId(ids.newId()),
                    tenantContext.requireTenant(),
                    code,
                    name,
                    kind,
                    durationMin,
                    durationMax,
                    durationStep,
                    slotStep,
                    bufferBefore,
                    bufferAfter,
                    leadTimeSeconds,
                    horizonDays,
                    holdTtlSeconds,
                    maxQty,
                    publicShowsRemaining,
                    queueMode,
                    admitPerSecond,
                    admitWindowSeconds,
                    clock.now());
            offerings.insert(offering);
            return offering;
        });
    }
}
