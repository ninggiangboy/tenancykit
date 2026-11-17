package dev.ngb.customer.customers.application.consent.exception;

/** The topic or the record does not follow the rules: the key's shape, the label's length, the status, the instant. */
public class InvalidConsentException extends ConsentProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "VALIDATION_FAILED";

    public InvalidConsentException(String message) {
        super(CODE, message);
    }
}
