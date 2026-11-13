package dev.ngb.platform.boot.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
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
    private final String audience;
    private final String userIssuer;
    private volatile JwtDecoder users;

    public PlatformJwtDecoder(SuiteTokenIssuer issuer, String userJwkSetUri, String userIssuer, String audience) {
        this.platformIssuer = issuer.issuer();
        this.audience = audience;
        this.userIssuer = userIssuer;
        this.platform = withAudience(issuer.decoder(), null);
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
                    decoder = withAudience(NimbusJwtDecoder.withJwkSetUri(userJwkSetUri).build(), userIssuer);
                    users = decoder;
                }
            }
        }
        return decoder;
    }

    /** Every token, whoever signed it, must name Platform as its audience; a realm token must also carry the realm's issuer. */
    private JwtDecoder withAudience(NimbusJwtDecoder decoder, String requiredIssuer) {
        OAuth2TokenValidator<Jwt> audienceCheck = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "the token is not for " + audience, null));
        OAuth2TokenValidator<Jwt> base = requiredIssuer == null || requiredIssuer.isBlank()
                ? JwtValidators.createDefault()
                : JwtValidators.createDefaultWithIssuer(requiredIssuer);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(base, audienceCheck));
        return decoder;
    }
}
