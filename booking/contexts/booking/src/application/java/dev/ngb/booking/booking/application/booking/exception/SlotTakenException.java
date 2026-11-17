package dev.ngb.booking.booking.application.booking.exception;

/** Another booking already claimed the range, or the places in the pool ran out: {@code 409}. */
public class SlotTakenException extends BookingProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "slot-taken";

    public SlotTakenException(String message) {
        super(CODE, message);
    }
}
