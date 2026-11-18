package dev.ngb.booking.booking.application.booking.exception;

/** A body that is well formed but cannot be held as sent, such as a session another offering owns: {@code 422}. */
public class InvalidBookingException extends BookingProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "VALIDATION_FAILED";

    public InvalidBookingException(String message) {
        super(CODE, message);
    }
}
