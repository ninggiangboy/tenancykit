package dev.ngb.booking.booking.application.waitlist.exception;

import dev.ngb.booking.booking.application.booking.exception.BookingProblem;

/** The target's waitlist is at the tenant's cap: {@code 409 waitlist-full}. */
public class WaitlistFullException extends BookingProblem {

    private static final long serialVersionUID = 1L;

    public WaitlistFullException(String message) {
        super("waitlist-full", message);
    }
}
