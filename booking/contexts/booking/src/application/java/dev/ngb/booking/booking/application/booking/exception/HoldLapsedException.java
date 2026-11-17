package dev.ngb.booking.booking.application.booking.exception;

/** The hold is gone: its time ran out past the grace window, or the worker already expired it: {@code 409}. */
public class HoldLapsedException extends BookingProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "hold-lapsed";

    public HoldLapsedException(String message) {
        super(CODE, message);
    }
}
