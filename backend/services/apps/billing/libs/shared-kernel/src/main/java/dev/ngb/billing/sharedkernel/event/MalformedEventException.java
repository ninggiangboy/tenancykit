package dev.ngb.billing.sharedkernel.event;

/**
 * A payload that is not the shape its event type promises. A typed decoder raises it instead of reading the
 * payload field by field, so a consumer has one error to catch and one decision to make (see the
 * malformed-event policy where the payload is decoded).
 */
public class MalformedEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MalformedEventException(String detail) {
        super(detail);
    }

    public MalformedEventException(String detail, Throwable cause) {
        super(detail, cause);
    }
}
