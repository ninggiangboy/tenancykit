package dev.ngb.core.suite.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;

/**
 * Links a verified token to a replica actor. Signature, issuer, audience and expiry were already checked by the
 * decoder; this adds the replica rules and gives every outcome a status: an identity that is not active here, or a
 * token whose claims cannot name one at all, is 401, and a Tenant System token naming another tenant than the host
 * resolved to is 403. No failure of the token itself ever reaches the error handler as a 500.
 */
public class SuiteActorConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final SuiteActorResolver resolver;

    public SuiteActorConverter(SuiteActorResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            return resolver.resolve(jwt)
                    .<AbstractAuthenticationToken>map(SuiteAuthentication::new)
                    .orElseThrow(() -> unauthorized("the actor is not active here"));
        } catch (SuiteActorResolver.TenantMismatchException mismatch) {
            throw new OAuth2AuthenticationException(new BearerTokenError(
                    BearerTokenErrorCodes.INSUFFICIENT_SCOPE,
                    HttpStatus.FORBIDDEN,
                    "the token belongs to another tenant",
                    null));
        } catch (IllegalArgumentException malformed) {
            throw unauthorized("the token names no usable actor");
        }
    }

    private static OAuth2AuthenticationException unauthorized(String detail) {
        return new OAuth2AuthenticationException(new BearerTokenError(
                BearerTokenErrorCodes.INVALID_TOKEN, HttpStatus.UNAUTHORIZED, detail, null));
    }
}
