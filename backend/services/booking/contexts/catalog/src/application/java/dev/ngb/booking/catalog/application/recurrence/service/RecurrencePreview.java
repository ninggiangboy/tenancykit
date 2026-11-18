package dev.ngb.booking.catalog.application.recurrence.service;

import java.time.Instant;
import java.util.List;

/** Expands a rule for a caller that wants to check it before a series is stored. */
public class RecurrencePreview {

    private final RecurrenceValidator recurrence;

    public RecurrencePreview(RecurrenceValidator recurrence) {
        this.recurrence = recurrence;
    }

    public List<Instant> expand(String zone, String dtstart, List<String> rule, Instant from, Instant to) {
        return recurrence.expand(zone, dtstart, rule, from, to);
    }
}
