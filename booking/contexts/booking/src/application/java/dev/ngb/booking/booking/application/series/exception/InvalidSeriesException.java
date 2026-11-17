package dev.ngb.booking.booking.application.series.exception;

import dev.ngb.booking.booking.application.booking.exception.BookingProblem;

/** A recurrence the series will not expand, or one that names more occurrences than a series may hold: {@code 422}. */
public class InvalidSeriesException extends BookingProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "invalid-recurrence";

    public InvalidSeriesException(String message) {
        super(CODE, message);
    }
}
