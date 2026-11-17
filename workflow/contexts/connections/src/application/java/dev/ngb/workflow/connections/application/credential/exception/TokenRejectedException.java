package dev.ngb.workflow.connections.application.credential.exception;

/** Platform answered 401: the client id and secret are not accepted, so the credential is now INVALID. */
public class TokenRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TokenRejectedException() {
        super("the token endpoint refused the tenant's credential");
    }
}
