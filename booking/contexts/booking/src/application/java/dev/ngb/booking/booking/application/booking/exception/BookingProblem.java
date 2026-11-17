package dev.ngb.booking.booking.application.booking.exception;

/**
 * A booking failure with the stable code its problem body carries, so a Tenant System can branch on it without reading
 * the message.
 */
public abstract class BookingProblem extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    protected BookingProblem(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
