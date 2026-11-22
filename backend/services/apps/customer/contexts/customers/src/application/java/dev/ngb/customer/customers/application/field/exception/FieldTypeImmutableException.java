package dev.ngb.customer.customers.application.field.exception;

/**
 * The request tries to change the type of an existing definition. The values already stored carry the old type, so a
 * type change is a new field, never an edit of this one.
 */
public class FieldTypeImmutableException extends FieldProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "FIELD_TYPE_IMMUTABLE";

    public FieldTypeImmutableException() {
        super(CODE, "A custom field's type is immutable; define a new field for another type.");
    }
}
