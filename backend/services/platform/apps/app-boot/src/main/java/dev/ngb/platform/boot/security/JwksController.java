package dev.ngb.platform.boot.security;

import dev.ngb.core.security.PublicEndpoint;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public halves of the signing keys, so every app can verify a suite token. */
@RestController
class JwksController {

    private final SuiteTokenIssuer issuer;

    JwksController(SuiteTokenIssuer issuer) {
        this.issuer = issuer;
    }

    @GetMapping("/.well-known/jwks.json")
    @PublicEndpoint
    Map<String, Object> jwks() {
        return issuer.jwks();
    }
}
