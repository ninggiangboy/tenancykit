package dev.ngb.booking.gate.application.exception;

/** A refusal from the public edge, carrying the status and the code the browser may branch on. */
public class GateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final Integer retryAfterSeconds;

    public GateException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public GateException(int status, String code, String message, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
