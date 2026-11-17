package dev.ngb.customer.customers.application.customer.exception;

/**
 * Another export of this tenant is still running, so this one is refused rather than started.
 *
 * <p>It answers {@code 429}: the caller waits for the one in flight instead of asking for a second copy of the same
 * rows, which is what keeps one tenant's export from being a way to read the whole customer list many times at once.
 */
public class ExportInProgressException extends CustomerProblem {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "EXPORT_IN_PROGRESS";

    public ExportInProgressException(String message) {
        super(CODE, message);
    }
}
