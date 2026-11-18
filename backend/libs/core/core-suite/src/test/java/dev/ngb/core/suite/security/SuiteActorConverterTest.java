package dev.ngb.core.suite.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.security.Actor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * A verified token becomes an authentication or one of exactly two statuses: 401 for an identity that is not an actor
 * here, 403 for one that names another tenant. A claim the resolver cannot read is a 401, never an unhandled 500.
 */
class SuiteActorConverterTest {

    private static final SuiteActorResolver NO_ACTOR = resolverReturning(Optional.empty());

    private static final SuiteActorResolver OTHER_TENANT = resolverThrowing(new SuiteActorResolver.TenantMismatchException());

    private static final SuiteActorResolver MALFORMED = resolverThrowing(new IllegalArgumentException("not a uuid"));

    @Test
    void anIdentityThatIsNotAnActorIs401() {
        assertThatThrownBy(() -> new SuiteActorConverter(NO_ACTOR).convert(token()))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(error -> assertThat(statusOf(error)).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void anIdentityFromAnotherTenantIs403() {
        assertThatThrownBy(() -> new SuiteActorConverter(OTHER_TENANT).convert(token()))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(error -> assertThat(statusOf(error)).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void aClaimTheResolverCannotReadIs401() {
        assertThatThrownBy(() -> new SuiteActorConverter(MALFORMED).convert(token()))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(error -> assertThat(statusOf(error)).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private static HttpStatus statusOf(Throwable error) {
        return ((BearerTokenError) ((OAuth2AuthenticationException) error).getError()).getHttpStatus();
    }

    private static SuiteActorResolver resolverReturning(Optional<Actor> actor) {
        return new SuiteActorResolver(null, null, null) {
            @Override
            public Optional<Actor> resolve(Jwt jwt) {
                return actor;
            }
        };
    }

    private static SuiteActorResolver resolverThrowing(RuntimeException failure) {
        return new SuiteActorResolver(null, null, null) {
            @Override
            public Optional<Actor> resolve(Jwt jwt) {
                throw failure;
            }
        };
    }

    private static Jwt token() {
        return Jwt.withTokenValue("user-token")
                .header("alg", "ES256")
                .subject("kc-subject-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .audience(List.of("billing"))
                .build();
    }
}
