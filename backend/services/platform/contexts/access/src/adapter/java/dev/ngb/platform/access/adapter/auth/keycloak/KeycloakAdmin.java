package dev.ngb.platform.access.adapter.auth.keycloak;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Keycloak Admin API as one object: the base URL, the realm and a client-credentials token for the realm's admin
 * client. The user provider and the console client share it so the token logic exists once.
 */
public final class KeycloakAdmin {

    private final RestClient http;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final JsonMapper json = JsonMapper.builder().build();

    public KeycloakAdmin(String baseUrl, String realm, String clientId, String clientSecret) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public RestClient http() {
        return http;
    }

    public String realm() {
        return realm;
    }

    public String token() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        String body = http.post()
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

    public Map<String, Object> fetchClient(String consoleClientId) {
        var clients = http.get()
                .uri(uri -> uri.path("/admin/realms/{realm}/clients")
                        .queryParam("clientId", consoleClientId)
                        .build(realm))
                .header("Authorization", "Bearer " + token())
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<java.util.List<Map<String, Object>>>() {});
        if (clients == null || clients.isEmpty()) {
            throw new IllegalStateException("Keycloak has no client " + consoleClientId + " in realm " + realm);
        }
        if (clients.size() > 1) {
            throw new IllegalStateException(
                    "Keycloak returned " + clients.size() + " clients for " + consoleClientId);
        }
        return new java.util.LinkedHashMap<>(clients.getFirst());
    }
}
