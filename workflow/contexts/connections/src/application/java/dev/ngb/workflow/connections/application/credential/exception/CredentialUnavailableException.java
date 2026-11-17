package dev.ngb.workflow.connections.application.credential.exception;

/** The Tenant has not registered a Platform credential, or it was marked invalid after a 401. */
public class CredentialUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CredentialUnavailableException(String reason) {
        super(reason);
    }
}
