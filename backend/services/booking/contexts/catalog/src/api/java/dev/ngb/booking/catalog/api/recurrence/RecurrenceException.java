package dev.ngb.booking.catalog.api.recurrence;

/** A recurrence the catalog will not expand, with the reason the caller reports as {@code invalid-recurrence}. */
public class RecurrenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RecurrenceException(String message) {
        super(message);
    }
}
