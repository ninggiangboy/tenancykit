package dev.ngb.customer.activity.application.activity.exception;

/**
 * The customer the call names was erased, so the anonymous marker it left behind may not be given a new activity.
 *
 * <p>It answers {@code 409}: the reference is taken and cannot be reused, and the caller's remedy is a new customer
 * with a new reference.
 */
public class ActivityCustomerErasedException extends ActivityProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "CUSTOMER_ERASED";

    public ActivityCustomerErasedException(String message) {
        super(CODE, message);
    }
}
