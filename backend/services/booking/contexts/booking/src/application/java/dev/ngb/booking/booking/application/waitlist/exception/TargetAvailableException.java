package dev.ngb.booking.booking.application.waitlist.exception;

import dev.ngb.booking.booking.application.booking.exception.BookingProblem;

/** The target has a free place, so a waitlist entry would never be offered: {@code 422 TARGET_AVAILABLE}. */
public class TargetAvailableException extends BookingProblem {

    private static final long serialVersionUID = 1L;

    public TargetAvailableException(String message) {
        super("TARGET_AVAILABLE", message);
    }
}
