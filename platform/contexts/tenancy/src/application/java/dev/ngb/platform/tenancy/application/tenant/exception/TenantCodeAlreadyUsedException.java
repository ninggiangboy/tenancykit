package dev.ngb.platform.tenancy.application.tenant.exception;

/** The Tenant code is already taken. */
public class TenantCodeAlreadyUsedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TenantCodeAlreadyUsedException(String code) {
        super("tenant code is already used: " + code);
    }
}
