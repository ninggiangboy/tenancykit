package dev.ngb.booking.catalog.application.block.service;

import dev.ngb.booking.catalog.application.catalog.exception.CatalogValidationException;
import dev.ngb.booking.catalog.application.recurrence.service.RecurrenceValidator;
import dev.ngb.booking.catalog.application.resource.query.ResourceReadRepository;
import dev.ngb.booking.catalog.application.resource.query.ResourceView;
import dev.ngb.core.error.NotFoundException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The rules a block body must satisfy: the resource it closes must be one this tenant holds, the range it closes must
 * end after it starts, and a repeating block's lines must be ones the expander reads.
 *
 * <p>The lines are validated in the resource's own zone and against the first occurrence the caller gave, because that
 * is the zone the recurrence will be read in and the instant its {@code DTSTART} is.
 */
public final class BlockRules {

    private final ResourceReadRepository resources;
    private final RecurrenceValidator recurrence;

    public BlockRules(ResourceReadRepository resources, RecurrenceValidator recurrence) {
        this.resources = resources;
        this.recurrence = recurrence;
    }

    /** @return the zone of the resource the block closes */
    public String zoneOf(UUID resourceId) {
        ResourceView resource = resources
                .findById(resourceId)
                .orElseThrow(() -> new NotFoundException("No resource exists here with that id."));
        return resource.zone();
    }

    /**
     * @return the recurrence lines of a repeating block, empty for a one-off block
     * @throws CatalogValidationException when the range does not end after it starts
     */
    public List<String> recurrenceLines(List<String> raw, String zone, Instant startsAt, Instant endsAt) {
        if (startsAt == null || endsAt == null) {
            throw new CatalogValidationException("a block closes a range, so startsAt and endsAt are both required");
        }
        if (!startsAt.isBefore(endsAt)) {
            throw new CatalogValidationException("a block ends after it starts");
        }
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(raw.size());
        for (String line : raw) {
            if (line == null || line.isBlank()) {
                throw new CatalogValidationException("a recurrence line is not blank");
            }
            lines.add(line.strip());
        }
        recurrence.check(zone, LocalDateTime.ofInstant(startsAt, ZoneId.of(zone)).toString(), lines);
        return List.copyOf(lines);
    }

    /** @return the reason, trimmed, which is between 1 and 200 characters */
    public static String reason(String raw) {
        String reason = raw == null ? null : raw.strip();
        if (reason == null || reason.isEmpty() || reason.length() > 200) {
            throw new CatalogValidationException("reason must be between 1 and 200 characters");
        }
        return reason;
    }
}
