package dev.ngb.platform.boot.security;

import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.platform.boot.config.PlatformProperties.ServiceClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues a suite token for a service client. Client authentication is HTTP Basic; a wrong secret answers 401 with no
 * detail, so the endpoint says nothing about which half was wrong.
 */
@RestController
class TokenController {

    private static final Set<String> AUDIENCES = Set.of("platform", "billing", "notification");

    private final ServiceClients clients;
    private final SuiteTokenIssuer issuer;

    TokenController(ServiceClients clients, SuiteTokenIssuer issuer) {
        this.clients = clients;
        this.issuer = issuer;
    }

    @PostMapping(path = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @PublicEndpoint
    ResponseEntity<Map<String, Object>> token(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("grant_type") String grantType,
            @RequestParam("audience") String audience) {
        if (!"client_credentials".equals(grantType) || !AUDIENCES.contains(audience)) {
            return ResponseEntity.badRequest().build();
        }
        Optional<ServiceClient> client = basicCredentials(authorization)
                .flatMap(credentials -> clients.authenticate(credentials[0], credentials[1]));
        if (client.isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        List<String> scopes = client.get().scopes().stream()
                .filter(code -> code.startsWith(audience + ":"))
                .toList();
        SuiteTokenIssuer.Issued issued = issuer.issue(client.get().id(), scopes, audience);
        return ResponseEntity.ok(Map.of(
                "access_token", issued.token(),
                "token_type", "Bearer",
                "expires_in", issued.expiresIn()));
    }

    private static Optional<String[]> basicCredentials(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return Optional.empty();
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).strip()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon <= 0) {
                return Optional.empty();
            }
            return Optional.of(new String[] {decoded.substring(0, colon), decoded.substring(colon + 1)});
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
