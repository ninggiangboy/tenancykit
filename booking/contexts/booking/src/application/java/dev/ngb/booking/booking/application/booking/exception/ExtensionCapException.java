package dev.ngb.booking.booking.application.booking.exception;

/** The hold's total extension would pass the configured cap: {@code 422}. */
public class ExtensionCapException extends BookingProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "EXTENSION_CAP";

    public ExtensionCapException(String message) {
        super(CODE, message);
    }
}
