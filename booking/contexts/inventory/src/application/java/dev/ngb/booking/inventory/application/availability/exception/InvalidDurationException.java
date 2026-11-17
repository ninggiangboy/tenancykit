package dev.ngb.booking.inventory.application.availability.exception;

/** A requested duration that is outside the offering's range or not on its step. */
public class InvalidDurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "INVALID_DURATION";

    public InvalidDurationException(String message) {
        super(message);
    }

    public String code() {
        return CODE;
    }
}
