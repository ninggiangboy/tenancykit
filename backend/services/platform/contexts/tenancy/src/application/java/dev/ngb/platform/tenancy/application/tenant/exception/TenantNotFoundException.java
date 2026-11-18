package dev.ngb.platform.tenancy.application.tenant.exception;

/** The Tenant does not exist. */
public class TenantNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TenantNotFoundException(String id) {
        super("no tenant " + id);
    }
}
