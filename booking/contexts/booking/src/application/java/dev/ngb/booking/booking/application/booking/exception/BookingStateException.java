package dev.ngb.booking.booking.application.booking.exception;

/** The booking is not in a state that allows this transition: {@code 409}. */
public class BookingStateException extends BookingProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "booking-state";

    public BookingStateException(String message) {
        super(CODE, message);
    }
}
