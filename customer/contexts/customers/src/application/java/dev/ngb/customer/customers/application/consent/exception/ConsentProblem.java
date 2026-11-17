package dev.ngb.customer.customers.application.consent.exception;

import dev.ngb.customer.customers.application.customer.exception.CustomerProblem;

/**
 * A consent-topic or consent-record request that cannot be applied. The code is the concrete failure a caller sees, and
 * a message written here never repeats a value the caller sent.
 */
public abstract class ConsentProblem extends CustomerProblem {

    private static final long serialVersionUID = 1L;

    protected ConsentProblem(String code, String message) {
        super(code, message);
    }
}
