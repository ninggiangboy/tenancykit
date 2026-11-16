package dev.ngb.crm.customers.application.field.exception;

/** No definition with that key exists in this tenant. */
public class CustomFieldNotFoundException extends FieldProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "CUSTOM_FIELD_NOT_FOUND";

    public CustomFieldNotFoundException() {
        super(CODE, "No custom field exists here with that key.");
    }
}
