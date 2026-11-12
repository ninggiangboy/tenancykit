package dev.ngb.platform.tenancy.application.tenant.exception;

/** The hostname already resolves somewhere. */
public class HostnameAlreadyMappedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HostnameAlreadyMappedException(String hostname) {
        super("hostname is already mapped: " + hostname);
    }
}
