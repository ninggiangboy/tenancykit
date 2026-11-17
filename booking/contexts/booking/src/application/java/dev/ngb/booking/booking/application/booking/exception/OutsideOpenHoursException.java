package dev.ngb.booking.booking.application.booking.exception;

/** The requested range is not inside the resource's open hours: {@code 422}. */
public class OutsideOpenHoursException extends BookingProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "outside-open-hours";

    public OutsideOpenHoursException(String message) {
        super(CODE, message);
    }
}
