package dev.ngb.core.replica.security;

import java.text.ParseException;
import java.util.List;
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
 * Chooses the key set from the token's issuer: a Keycloak realm token is verified against the realm's JWKS, a
 * Platform suite token against Platform's JWKS. Both require the audience {@code suite.app-code} and a valid
 * {@code exp}/{@code iat}, so a token minted for another app is refused here, before any replica lookup. Any other
 * issuer is refused.
 */
public class SuiteJwtDecoders implements JwtDecoder {

    /** The minimum the decoder needs to know about one issuer. */
    public record Issuer(String uri, String jwkSetUri) {}

    private final String platformIssuer;
    private final String appCode;
    private final JwtDecoder platform;
    private final String userJwkSetUri;
    private volatile JwtDecoder users;

    public SuiteJwtDecoders(Issuer platform, Issuer user, String appCode) {
        this.platformIssuer = platform.uri();
        this.appCode = appCode;
        this.platform = decoder(platform.jwkSetUri());
        this.userJwkSetUri = user == null ? null : user.jwkSetUri();
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        String issuer;
        try {
            issuer = issuerOf(token);
        } catch (ParseException e) {
            throw new BadJwtException("the token is malformed", e);
        }
        if (platformIssuer.equals(issuer)) {
            return platform.decode(token);
        }
        return userDecoder().decode(token);
    }

    /** Whether the issuer is Platform's, read before verification so the right key set is chosen. */
    public boolean isPlatformIssuer(String issuer) {
        return platformIssuer.equals(issuer);
    }

    public String appCode() {
        return appCode;
    }

    private JwtDecoder userDecoder() {
        if (userJwkSetUri == null || userJwkSetUri.isBlank()) {
            throw new BadJwtException("only platform tokens are accepted: no user realm is configured");
        }
        JwtDecoder decoder = users;
        if (decoder == null) {
            synchronized (this) {
                decoder = users;
                if (decoder == null) {
                    decoder = decoder(userJwkSetUri);
                    users = decoder;
                }
            }
        }
        return decoder;
    }

    private JwtDecoder decoder(String jwkSetUri) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(
                new DelegatingTokenValidator(List.of(JwtValidators.createDefault(), audience(appCode))));
        return decoder;
    }

    /** Applies several validators to one token; the first failure is the reason. */
    private record DelegatingTokenValidator(List<OAuth2TokenValidator<Jwt>> validators)
            implements OAuth2TokenValidator<Jwt> {

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            for (OAuth2TokenValidator<Jwt> validator : validators) {
                OAuth2TokenValidatorResult result = validator.validate(token);
                if (result.hasErrors()) {
                    return result;
                }
            }
            return OAuth2TokenValidatorResult.success();
        }
    }

    /** Requires {@code aud} to contain exactly the app's code. */
    private static OAuth2TokenValidator<Jwt> audience(String appCode) {
        return jwt -> {
            List<String> aud = jwt.getAudience();
            return aud != null && aud.contains(appCode)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                            OAuth2ErrorCodes.INVALID_TOKEN, "the token is not for " + appCode, null));
        };
    }

    /** The issuer of a token, read before verification. */
    public static String issuerOf(String token) throws ParseException {
        return com.nimbusds.jwt.SignedJWT.parse(token).getJWTClaimsSet().getIssuer();
    }
}
