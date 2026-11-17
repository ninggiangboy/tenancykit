package dev.ngb.customer.customers.application.field.exception;

/** A definition with that key already exists in this tenant: the key identifies the field and is immutable. */
public class FieldKeyExistsException extends FieldProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "FIELD_KEY_EXISTS";

    public FieldKeyExistsException() {
        super(CODE, "A custom field with that key already exists here.");
    }
}
