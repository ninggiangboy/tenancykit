package dev.ngb.booking.inventory.adapter.availability.web;

import dev.ngb.booking.inventory.application.availability.query.AvailabilityQueries;
import dev.ngb.booking.inventory.application.availability.query.AvailabilitySlot;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The free start instants of an interval offering in a window, computed at query time. Each start carries the resource
 * chosen for every requirement, so a caller can offer a time and the resources it takes in one answer.
 */
@Profile("api")
@RestController
@RequestMapping
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class AvailabilityController {

    /** One free start; {@code resourceIds} holds one resource per requirement, in requirement order. */
    public record AvailabilitySlotResponse(Instant start, Instant end, List<UUID> resourceIds) {

        static AvailabilitySlotResponse of(AvailabilitySlot slot) {
            return new AvailabilitySlotResponse(slot.start(), slot.end(), slot.resourceIds());
        }
    }

    public record AvailabilityPage(List<AvailabilitySlotResponse> data) {}

    private final AvailabilityQueries availability;

    public AvailabilityController(AvailabilityQueries availability) {
        this.availability = availability;
    }

    @GetMapping("/offerings/{offeringId}/availability")
    @RequiresPermission(BookingPermissions.OFFERING_READ)
    public AvailabilityPage getAvailability(
            @PathVariable UUID offeringId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) Integer durationMinutes,
            @RequestParam(required = false) UUID resource,
            @RequestParam(required = false) Integer partySize) {
        return new AvailabilityPage(availability
                .availability(offeringId, from, to, durationMinutes, resource, partySize)
                .stream()
                .map(AvailabilitySlotResponse::of)
                .toList());
    }
}
