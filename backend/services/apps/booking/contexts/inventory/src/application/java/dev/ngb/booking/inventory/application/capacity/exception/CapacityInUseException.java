package dev.ngb.booking.inventory.application.capacity.exception;

/**
 * A pool cannot shrink below the places a booking already holds or has booked: {@code CAPACITY_BELOW_COMMITTED}.
 *
 * <p>The engine answers a claim that does not fit as an outcome; this is the resize a caller asked for and that the
 * places refuse, because taking it would oversell the pool.
 */
public class CapacityInUseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "CAPACITY_BELOW_COMMITTED";

    public CapacityInUseException() {
        super("The pool cannot shrink below the places that are already held or booked.");
    }

    public String code() {
        return CODE;
    }
}
