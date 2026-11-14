package dev.ngb.platform.access.application.auth.port;

/** Keycloak keeps authenticating; Platform creates and removes the identities it manages. */
public interface IdentityProviderPort {

    /**
     * Creating a username that already exists returns the existing subject instead of failing, because the username is
     * deterministic per (tenant, email) and a retry after a failed compensation must converge.
     *
     * @param username the Keycloak username, {@code <tenant-code>:<email>}
     * @return the Keycloak user id, which becomes the token subject
     */
    String createUser(String username, String email, String displayName, String temporaryPassword);

    /** Removes the identity; an identity that is already absent is not an error. */
    void deleteUser(String keycloakSubject);
}
