package dev.ngb.platform.tenancy.application.tenant.port;

/**
 * Keycloak must accept the console's sign-in on a tenant host, and a new tenant domain is what makes that host real.
 * The call happens after the domain commits; a failure is logged and left to the reconciler, never failing the domain
 * request itself.
 */
public interface ConsoleHostPort {

    void register(String hostname);
}
