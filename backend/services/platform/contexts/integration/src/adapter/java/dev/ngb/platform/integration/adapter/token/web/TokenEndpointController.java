package dev.ngb.platform.integration.adapter.token.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.PublicEndpoint;
import dev.ngb.platform.integration.application.auth.exception.RateLimitExceededException;
import dev.ngb.platform.integration.application.token.command.IssueTokenUseCase;
import dev.ngb.platform.integration.application.token.port.AudienceCatalog;
import dev.ngb.platform.integration.application.token.port.TokenIssuer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The client-credentials grant. Client authentication is HTTP Basic; a wrong secret answers 401 with no detail, so
 * the endpoint never says which half was wrong.
 */
@Tag(
        name = "Access tokens",
        description =
                "The OAuth client-credentials grant a Tenant System or service client exchanges its credential for a "
                + "token.")
@RestController
@Callers(value = {Caller.TENANT_SYSTEM, Caller.INTERNAL}, prefix = Callers.Prefix.NONE)
class TokenEndpointController {

    private static final String RETRY_AFTER = "1";

    private final IssueTokenUseCase tokens;
    private final AudienceCatalog audiences;

    TokenEndpointController(IssueTokenUseCase tokens, AudienceCatalog audiences) {
        this.tokens = tokens;
        this.audiences = audiences;
    }

    @Operation(
            summary = "Get an access token with client credentials",
            description =
                    "The OAuth client-credentials grant for Tenant Systems and service clients. Authenticate with HTTP "
                    + "Basic using the credential's id and secret, send `grant_type=client_credentials` and the `audience` "
                    + "of the app to call as a form, and receive a short-lived `Bearer` token (`access_token`, "
                    + "`token_type`, `expires_in`). An unsupported grant or audience answers `400`; a missing or wrong "
                    + "credential answers `401` with no detail on which half was wrong; too many attempts answer `429` with "
                    + "`Retry-After`. Public: no token is needed.")
    @PostMapping(path = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @PublicEndpoint
    ResponseEntity<Map<String, Object>> token(
            HttpServletRequest httpRequest,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("grant_type") String grantType,
            @RequestParam("audience") String audience,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        if (!"client_credentials".equals(grantType) || !audiences.offers(audience)) {
            return ResponseEntity.badRequest().build();
        }
        String[] credentials = basicCredentials(authorization).orElse(null);
        if (credentials == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            Optional<TokenIssuer.Issued> issued =
                    tokens.issue(credentials[0], credentials[1], audience, requestId, httpRequest.getRemoteAddr());
            return issued
                    .<ResponseEntity<Map<String, Object>>>map(token -> ResponseEntity.ok(Map.of(
                            "access_token", token.token(),
                            "token_type", "Bearer",
                            "expires_in", token.expiresIn())))
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        } catch (RateLimitExceededException limited) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", RETRY_AFTER).build();
        }
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

    /** A token outlives one request and no more; kept here so the contract test and the endpoint agree. */
    static Duration lifetime() {
        return Duration.ofMinutes(10);
    }
}
