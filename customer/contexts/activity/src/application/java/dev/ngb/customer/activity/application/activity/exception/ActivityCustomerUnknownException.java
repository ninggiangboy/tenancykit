package dev.ngb.customer.activity.application.activity.exception;

/**
 * The customer an activity names does not exist here.
 *
 * <p>An activity is a statement about a customer this service already holds; it never creates one, because a
 * mistyped reference would otherwise become a second customer of the same person.
 */
public class ActivityCustomerUnknownException extends ActivityProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "CUSTOMER_UNKNOWN";

    public ActivityCustomerUnknownException(String message) {
        super(CODE, message);
    }
}
