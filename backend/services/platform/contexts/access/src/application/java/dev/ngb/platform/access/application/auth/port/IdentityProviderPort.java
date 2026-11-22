package dev.ngb.platform.access.application.auth.port;

import dev.ngb.core.id.TenantId;

/** Keycloak keeps authenticating; Platform creates and removes the identities it manages. */
public interface IdentityProviderPort {

    /**
     * Creating a username that already exists returns the existing subject instead of failing, because the username is
     * deterministic per (tenant, email) and a retry after a failed compensation must converge.
     *
     * @param username the Keycloak username, {@code <tenant-code>:<email>}
     * @param tenantId the tenant the user belongs to; Keycloak keeps it on the identity so the mail it sends (a password
     *     reset) is delivered under that tenant
     * @return the Keycloak user id, which becomes the token subject
     */
    String createUser(String username, String email, String displayName, String temporaryPassword, TenantId tenantId);

    /** Removes the identity; an identity that is already absent is not an error. */
    void deleteUser(String keycloakSubject);
}
