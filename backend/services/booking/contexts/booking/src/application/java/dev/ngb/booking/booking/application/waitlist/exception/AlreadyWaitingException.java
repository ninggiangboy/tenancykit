package dev.ngb.booking.booking.application.waitlist.exception;

import dev.ngb.booking.booking.application.booking.exception.BookingProblem;

/** This customer already waits for this target: {@code 409 ALREADY_WAITING}. */
public class AlreadyWaitingException extends BookingProblem {

    private static final long serialVersionUID = 1L;

    public AlreadyWaitingException() {
        super("ALREADY_WAITING", "This customer already waits for this target.");
    }
}
