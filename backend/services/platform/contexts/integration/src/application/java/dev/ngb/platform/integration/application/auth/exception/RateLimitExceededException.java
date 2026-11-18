package dev.ngb.platform.integration.application.auth.exception;

/** A client id exceeded the token endpoint's rate limit. */
public class RateLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RateLimitExceededException() {
        super("too many token requests");
    }
}
