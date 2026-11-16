package dev.ngb.crm.activity.application.activity.exception;

/**
 * An activity request that cannot be applied. The code is the concrete failure a caller sees: the problem body of a
 * failed call carries it, and a batch item reports it instead of failing the whole batch.
 *
 * <p>A message written here is for the caller and never repeats a payload or a reference, so a refusal cannot echo
 * what it refused.
 */
public abstract class ActivityProblem extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    protected ActivityProblem(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
