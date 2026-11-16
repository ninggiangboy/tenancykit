package dev.ngb.crm.customers.application.customer.exception;

/**
 * A change without {@code If-Match} says nothing about the state it was decided on, so it would silently overwrite
 * another caller's. The endpoint asks for the version instead of guessing.
 */
public class IfMatchRequiredException extends CustomerProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "PRECONDITION_REQUIRED";

    public IfMatchRequiredException() {
        super(CODE, "If-Match is required: send the version this change is based on.");
    }
}
