package dev.ngb.platform.access.adapter.auth.keycloak;

import dev.ngb.platform.access.application.auth.port.IdentityProviderPort;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates and removes Keycloak users through the realm admin client. Keycloak stays the only authenticator.
 *
 * <p>Creating a username that Keycloak already holds adopts that identity: the username is
 * {@code <tenant-code>:<email>}, deterministic for one person in one tenant, so a retry after a failed compensation
 * means the same user. Without the adoption a temporary Keycloak failure would lock that email out for good, because
 * every later attempt would hit the duplicate-username conflict.
 */
public class KeycloakIdentityProvider implements IdentityProviderPort {

    private final RestClient http;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final JsonMapper json = JsonMapper.builder().build();

    public KeycloakIdentityProvider(String baseUrl, String realm, String clientId, String clientSecret) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public String createUser(String username, String email, String displayName, String temporaryPassword) {
        ResponseEntity<Void> response;
        try {
            response = http.post()
                    .uri("/admin/realms/{realm}/users", realm)
                    .header("Authorization", "Bearer " + adminToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "username", username,
                            "email", email,
                            "firstName", displayName,
                            "enabled", true,
                            "emailVerified", true,
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
            http.delete()
                    .uri("/admin/realms/{realm}/users/{id}", realm, keycloakSubject)
                    .header("Authorization", "Bearer " + adminToken())
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
        JsonNode users = http.get()
                .uri(uri -> uri.path("/admin/realms/{realm}/users")
                        .queryParam("username", username)
                        .queryParam("exact", true)
                        .build(realm, username))
                .header("Authorization", "Bearer " + adminToken())
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

    private String adminToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        var body = http.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        String token = json.readTree(body).get("access_token").asString();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Keycloak did not return an admin access token");
        }
        return token;
    }
}
