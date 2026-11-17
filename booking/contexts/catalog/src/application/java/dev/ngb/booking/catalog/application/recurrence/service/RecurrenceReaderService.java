package dev.ngb.booking.catalog.application.recurrence.service;

import dev.ngb.booking.catalog.api.recurrence.RecurrenceException;
import dev.ngb.booking.catalog.api.recurrence.RecurrenceReader;
import dev.ngb.booking.catalog.application.recurrence.exception.InvalidRecurrenceException;
import java.time.Instant;
import java.util.List;

/**
 * Serves another context the catalog's recurrence expansion, translating a rejection into the api's own exception so no
 * catalog application type crosses the context boundary.
 */
public class RecurrenceReaderService implements RecurrenceReader {

    private final RecurrenceValidator recurrence;

    public RecurrenceReaderService(RecurrenceValidator recurrence) {
        this.recurrence = recurrence;
    }

    @Override
    public List<Instant> expand(String zone, String dtstart, List<String> rule, Instant from, Instant to) {
        try {
            return recurrence.expand(zone, dtstart, rule, from, to);
        } catch (InvalidRecurrenceException invalid) {
            throw new RecurrenceException(invalid.getMessage());
        }
    }
}
