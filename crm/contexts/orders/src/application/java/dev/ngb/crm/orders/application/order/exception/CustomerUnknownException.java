package dev.ngb.crm.orders.application.order.exception;

/**
 * The customer a call names does not exist here, and the call did not ask for one to be created.
 *
 * <p>It is a refusal rather than a silent creation because a mistyped reference would otherwise become a second
 * customer of the same person, which nothing in crm may merge.
 */
public class CustomerUnknownException extends OrderProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "CUSTOMER_UNKNOWN";

    public CustomerUnknownException(String message) {
        super(CODE, message);
    }
}
