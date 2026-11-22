package dev.ngb.platform.access.adapter.auth.keycloak;

import dev.ngb.core.id.TenantId;
import dev.ngb.platform.access.application.auth.port.IdentityProviderPort;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * Creates and removes Keycloak users through the realm admin client. Keycloak stays the only authenticator.
 *
 * <p>Creating a username that Keycloak already holds adopts that identity: the username is
 * {@code <tenant-code>:<email>}, deterministic for one person in one tenant, so a retry after a failed compensation
 * means the same user. Without the adoption a temporary Keycloak failure would lock that email out for good, because
 * every later attempt would hit the duplicate-username conflict.
 */
public class KeycloakIdentityProvider implements IdentityProviderPort {

    private final KeycloakAdmin admin;

    public KeycloakIdentityProvider(String baseUrl, String realm, String clientId, String clientSecret) {
        this(new KeycloakAdmin(baseUrl, realm, clientId, clientSecret));
    }

    /** The user attribute Keycloak's mail sender reads to scope a mail to the user's tenant. */
    static final String TENANT_ID_ATTRIBUTE = "tenant_id";

    public KeycloakIdentityProvider(KeycloakAdmin admin) {
        this.admin = admin;
    }

    @Override
    public String createUser(
            String username, String email, String displayName, String temporaryPassword, TenantId tenantId) {
        ResponseEntity<Void> response;
        try {
            response = admin.http()
                    .post()
                    .uri("/admin/realms/{realm}/users", admin.realm())
                    .header("Authorization", "Bearer " + admin.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "username", username,
                            "email", email,
                            "firstName", displayName,
                            "enabled", true,
                            "emailVerified", true,
                            "attributes", Map.of(TENANT_ID_ATTRIBUTE, List.of(tenantId.value().toString())),
                            "credentials", List.of(Map.of(
                                    "type", "password", "value", temporaryPassword, "temporary", true))))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 409) {
                return subjectOf(username);
            }
            throw e;
        }
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return the created user's location");
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Override
    public void deleteUser(String keycloakSubject) {
        try {
            admin.http()
                    .delete()
                    .uri("/admin/realms/{realm}/users/{id}", admin.realm(), keycloakSubject)
                    .header("Authorization", "Bearer " + admin.token())
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            // An identity that is already gone satisfies "ensure absent", so a retried compensation succeeds.
            if (e.getStatusCode().value() != 404) {
                throw e;
            }
        }
    }

    /**
     * Equals the username of an identity Keycloak refused as a duplicate, so a retry after a failed compensation
     * converges on the identity it already created instead of failing forever.
     */
    private String subjectOf(String username) {
        JsonNode users = admin.http()
                .get()
                .uri(uri -> uri.path("/admin/realms/{realm}/users")
                        .queryParam("username", username)
                        .queryParam("exact", true)
                        .build(admin.realm(), username))
                .header("Authorization", "Bearer " + admin.token())
                .retrieve()
                .body(JsonNode.class);
        if (users == null || !users.isArray() || users.isEmpty()) {
            throw new IllegalStateException(
                    "Keycloak reports the username " + username + " as taken but returns no user for it");
        }
        if (users.size() > 1) {
            throw new IllegalStateException(
                    "Keycloak returned " + users.size() + " users for the exact username " + username);
        }
        JsonNode id = users.get(0).get("id");
        if (id == null || id.asString().isBlank()) {
            throw new IllegalStateException("Keycloak returned a user without an id for the username " + username);
        }
        return id.asString();
    }
}
