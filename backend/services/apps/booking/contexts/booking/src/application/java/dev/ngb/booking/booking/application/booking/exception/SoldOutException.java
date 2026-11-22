package dev.ngb.booking.booking.application.booking.exception;

/** The pool does not have as many free places as the claim asks for: {@code 409}. */
public class SoldOutException extends BookingProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "sold-out";

    public SoldOutException(String message) {
        super(CODE, message);
    }
}
