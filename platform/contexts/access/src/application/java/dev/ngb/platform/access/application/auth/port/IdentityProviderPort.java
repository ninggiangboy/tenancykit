package dev.ngb.platform.access.application.auth.port;

/** Keycloak keeps authenticating; Platform creates and removes the identities it manages. */
public interface IdentityProviderPort {

    /**
     * @param username the Keycloak username, {@code <tenant-code>:<email>}
     * @return the Keycloak user id, which becomes the token subject
     */
    String createUser(String username, String email, String displayName, String temporaryPassword);

    void deleteUser(String keycloakSubject);
}
