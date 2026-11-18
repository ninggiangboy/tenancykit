package dev.ngb.core.error;

/** The request is malformed or violates a domain rule; the caller can fix it and retry. */
public class BadRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BadRequestException(String message) {
        super(message);
    }
}
