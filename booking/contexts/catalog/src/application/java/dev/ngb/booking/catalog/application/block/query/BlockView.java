package dev.ngb.booking.catalog.application.block.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One block as a read returns it. {@code rule} is empty for a one-off block, and the two instants are the range it
 * closes or the first occurrence of the rule it repeats.
 */
public record BlockView(
        UUID id,
        UUID resourceId,
        String reason,
        Instant startsAt,
        Instant endsAt,
        List<String> rule) {

    public BlockView {
        rule = rule == null ? List.of() : List.copyOf(rule);
    }
}
