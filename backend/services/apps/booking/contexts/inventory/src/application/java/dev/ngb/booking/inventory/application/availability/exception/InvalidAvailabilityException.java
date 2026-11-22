package dev.ngb.booking.inventory.application.availability.exception;

import java.util.Objects;

/** An availability request this context will not answer, carrying the stable code its problem body reports. */
public class InvalidAvailabilityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public InvalidAvailabilityException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
