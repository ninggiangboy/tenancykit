package dev.ngb.platform.boot.security;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Chooses the key set from the token's issuer: Platform verifies its own suite tokens, and everything else is a
 * Keycloak user token verified against the realm's JWKS. The realm decoder is built on first use, so Platform starts
 * before Keycloak is reachable, and it is absent when no realm is configured.
 */
public class PlatformJwtDecoder implements JwtDecoder {

    private final String platformIssuer;
    private final JwtDecoder platform;
    private final String userJwkSetUri;
    private volatile JwtDecoder users;

    public PlatformJwtDecoder(SuiteTokenIssuer issuer, String userJwkSetUri) {
        this.platformIssuer = issuer.issuer();
        this.platform = issuer.decoder();
        this.userJwkSetUri = userJwkSetUri;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        String issuer;
        try {
            issuer = SuiteTokenIssuer.issuerOf(token);
        } catch (java.text.ParseException e) {
            throw new BadJwtException("the token is malformed", e);
        }
        if (platformIssuer.equals(issuer)) {
            return platform.decode(token);
        }
        return userDecoder().decode(token);
    }

    private JwtDecoder userDecoder() {
        if (userJwkSetUri == null || userJwkSetUri.isBlank()) {
            throw new BadJwtException("only suite tokens are accepted: no user realm is configured");
        }
        JwtDecoder decoder = users;
        if (decoder == null) {
            synchronized (this) {
                decoder = users;
                if (decoder == null) {
                    decoder = NimbusJwtDecoder.withJwkSetUri(userJwkSetUri).build();
                    users = decoder;
                }
            }
        }
        return decoder;
    }
}
