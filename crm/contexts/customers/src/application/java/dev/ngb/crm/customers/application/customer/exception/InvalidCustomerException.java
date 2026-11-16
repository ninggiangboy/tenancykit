package dev.ngb.crm.customers.application.customer.exception;

/** The body is not a customer: an attribute is empty, too long or not an address, or the reference is unusable. */
public class InvalidCustomerException extends CustomerProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "VALIDATION_FAILED";

    public InvalidCustomerException(String message) {
        super(CODE, message);
    }
}
