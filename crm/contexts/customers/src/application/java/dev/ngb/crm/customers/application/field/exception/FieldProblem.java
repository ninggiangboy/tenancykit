package dev.ngb.crm.customers.application.field.exception;

import dev.ngb.crm.customers.application.customer.exception.CustomerProblem;

/**
 * A field-definition or field-value request that cannot be applied. The code is the concrete failure a caller sees, and
 * a message written here never repeats a value the caller sent.
 */
public abstract class FieldProblem extends CustomerProblem {

    private static final long serialVersionUID = 1L;

    protected FieldProblem(String code, String message) {
        super(code, message);
    }
}
