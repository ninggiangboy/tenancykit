package dev.ngb.booking.catalog.application.recurrence.exception;

import dev.ngb.booking.catalog.application.catalog.exception.CatalogProblem;

/** A recurrence this service will not expand: {@code 422 invalid-recurrence}, naming the offending part. */
public class InvalidRecurrenceException extends CatalogProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "invalid-recurrence";

    public InvalidRecurrenceException(String message) {
        super(CODE, message);
    }
}
