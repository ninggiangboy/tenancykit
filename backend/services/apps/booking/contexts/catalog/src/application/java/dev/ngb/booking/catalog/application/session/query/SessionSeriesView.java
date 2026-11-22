package dev.ngb.booking.catalog.application.session.query;

import java.util.List;
import java.util.UUID;

/**
 * One session series as a read returns it. The local start is the wall-clock the rule is read from and {@code rule} is
 * the lines it repeats, so a caller sees exactly what it stored.
 */
public record SessionSeriesView(
        UUID id,
        UUID offeringId,
        String zone,
        String dtstart,
        List<String> rule,
        int durationMinutes,
        int capacity,
        List<PoolView> pools,
        long version) {

    public SessionSeriesView {
        rule = rule == null ? List.of() : List.copyOf(rule);
        pools = pools == null ? List.of() : List.copyOf(pools);
    }
}
