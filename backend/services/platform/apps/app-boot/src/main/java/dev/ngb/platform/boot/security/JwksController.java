package dev.ngb.platform.boot.security;

import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.PublicEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public halves of the signing keys, so every app can verify a suite token. */
@Tag(
        name = "Signing keys",
        description =
                "The public halves of the keys Platform signs suite tokens with, so every app can verify a token.")
@RestController
@Callers(value = Caller.PUBLIC, prefix = Callers.Prefix.NONE)
class JwksController {

    private final SuiteTokenIssuer issuer;

    JwksController(SuiteTokenIssuer issuer) {
        this.issuer = issuer;
    }

    @Operation(
            summary = "Get the suite's public signing keys",
            description =
                    "Publishes the public halves of the keys Platform signs suite tokens with, as a JSON Web Key Set, so "
                    + "every app can verify a token without calling Platform. Public: no token is needed.")
    @GetMapping("/.well-known/jwks.json")
    @PublicEndpoint
    Map<String, Object> jwks() {
        return issuer.jwks();
    }
}
