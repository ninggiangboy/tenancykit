package dev.ngb.core.error;

/** A service this one depends on could not be reached, or kept failing after its retries. */
public class UpstreamUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UpstreamUnavailableException(String message) {
        super(message);
    }

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
