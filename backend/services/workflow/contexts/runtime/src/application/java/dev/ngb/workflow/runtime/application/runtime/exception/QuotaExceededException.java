package dev.ngb.workflow.runtime.application.runtime.exception;

/** A Tenant asked for more than its plan allows; the API answers 429 with the reason. */
public class QuotaExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public QuotaExceededException(String reason) {
        super(reason);
    }
}
