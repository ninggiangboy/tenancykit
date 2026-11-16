package dev.ngb.crm.orders.application.order.exception;

/**
 * An order or return request that cannot be applied. The code is the concrete failure a caller sees: the problem body
 * of a failed call carries it, and a batch item reports it instead of failing the whole batch.
 *
 * <p>A message written here is for the caller and repeats no value it refused, so a refusal cannot echo a customer's
 * reference or what was bought.
 */
public abstract class OrderProblem extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    protected OrderProblem(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
