package dev.ngb.booking.catalog.application.offering.query;

import dev.ngb.booking.catalog.domain.offering.OfferingKind;
import dev.ngb.booking.catalog.domain.offering.OfferingStatus;
import dev.ngb.booking.catalog.domain.offering.QueueMode;
import java.util.List;
import java.util.UUID;

/**
 * One offering as a read returns it, with its requirements and cancellation thresholds. The version is what a caller
 * sends back in {@code If-Match} to patch it, and {@code durationMax} is null when the duration is fixed.
 */
public record OfferingView(
        UUID id,
        String code,
        String name,
        OfferingKind kind,
        OfferingStatus status,
        int durationMin,
        Integer durationMax,
        int durationStep,
        int slotStep,
        int bufferBefore,
        int bufferAfter,
        int leadTimeSeconds,
        int horizonDays,
        Integer holdTtlSeconds,
        int maxQty,
        boolean publicShowsRemaining,
        QueueMode queueMode,
        int admitPerSecond,
        int admitWindowSeconds,
        long version,
        List<RequirementView> requirements,
        List<CancellationWindowView> cancellationWindows) {}
