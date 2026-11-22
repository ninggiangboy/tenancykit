package dev.ngb.core.security;

import java.util.Optional;

/**
 * Who may call an endpoint. The name is a caller, not an audience: an audience is the JWT {@code aud} claim and the
 * UI remotes' audiences, which answer different questions.
 *
 * <p>The wire value is what the OpenAPI label and the generated documentation carry, and it is stable: a route's
 * caller is one of these seven and nothing else.
 */
public enum Caller {
    /** A person signed in to the Platform console with a platform-scope Keycloak token. */
    PLATFORM_USER("platform-user"),
    /** A person signed in to a Tenant's console with a tenant-scope Keycloak token. */
    TENANT_USER("tenant-user"),
    /** A Tenant's own system, holding a Platform-issued suite token. */
    TENANT_SYSTEM("tenant-system"),
    /** A customer in a Billing portal, holding the portal cookie. */
    CUSTOMER_SESSION("customer-session"),
    /** An anonymous browser or a signed public link. */
    PUBLIC("public"),
    /** A signed inbound webhook from a third party or a Tenant's app. */
    PROVIDER("provider"),
    /** Another service of the suite, holding a service-client suite token. */
    INTERNAL("internal");

    private final String wire;

    Caller(String wire) {
        this.wire = wire;
    }

    /** The kebab-case value the label and the documentation carry. */
    public String wire() {
        return wire;
    }

    /** Resolves a wire value, so a document or a test can go back to the enum. */
    public static Optional<Caller> fromWire(String wire) {
        for (Caller caller : values()) {
            if (caller.wire.equals(wire)) {
                return Optional.of(caller);
            }
        }
        return Optional.empty();
    }
}
