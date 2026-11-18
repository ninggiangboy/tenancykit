package dev.ngb.customer.customers.application.customer.exception;

/**
 * The customer's personal data was erased, so the row is an anonymous marker now and holds nothing a caller may
 * rewrite. The reference the Tenant chose is free again and starts a new customer.
 */
public class CustomerErasedException extends CustomerProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "CUSTOMER_ERASED";

    public CustomerErasedException() {
        super(CODE, "This customer's personal data was erased; send the reference again to create a new customer.");
    }
}
