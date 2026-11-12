package dev.ngb.platform.access.adapter.auth.keycloak;

import dev.ngb.platform.access.application.auth.port.IdentityProviderPort;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates and removes Keycloak users through the realm admin client. Keycloak stays the only authenticator.
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
        var response = http.post()
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
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return the created user's location");
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Override
    public void deleteUser(String keycloakSubject) {
        http.delete()
                .uri("/admin/realms/{realm}/users/{id}", realm, keycloakSubject)
                .header("Authorization", "Bearer " + adminToken())
                .retrieve()
                .toBodilessEntity();
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
