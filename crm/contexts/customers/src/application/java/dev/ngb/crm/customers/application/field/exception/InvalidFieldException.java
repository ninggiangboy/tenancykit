package dev.ngb.crm.customers.application.field.exception;

/**
 * The definition or the value does not follow the rules: the key's shape, the label's length, the choices of a
 * {@code CHOICE}, the type a value must have, the serialized size of a customer's values, or the cap on active
 * definitions.
 */
public class InvalidFieldException extends FieldProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "VALIDATION_FAILED";

    public InvalidFieldException(String message) {
        super(CODE, message);
    }
}
