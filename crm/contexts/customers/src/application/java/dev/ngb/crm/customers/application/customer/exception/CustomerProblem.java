package dev.ngb.crm.customers.application.customer.exception;

/**
 * A customer request that cannot be applied. The code is the concrete failure a caller sees: the problem body of a
 * failed call carries it, and a batch item reports it instead of failing the whole batch.
 *
 * <p>A message written here is for the caller and never repeats an attribute value, so a refusal cannot leak the
 * personal data it refused.
 */
public abstract class CustomerProblem extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    protected CustomerProblem(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
