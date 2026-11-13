package dev.ngb.platform.boot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.ActorType;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.spring.tenancy.ScopedTenantContext;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.access.api.user.MeQueries;
import dev.ngb.platform.access.api.user.UserAccessView;
import dev.ngb.platform.access.api.user.UserReadRepository;
import dev.ngb.platform.access.api.user.UserView;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.BearerTokenError;

/** The actor comes from Platform's own users for a Keycloak token, and from the claims for a suite token. */
class JwtActorConverterTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String SUBJECT = UUID.randomUUID().toString();

    private final TenantContext tenantContext = new ScopedTenantContext();

    @Test
    void anActiveUserOfTheHostTenantGetsTheirComputedPermissions() {
        Actor actor = convert(converter("ACTIVE", TENANT), userToken(), TENANT);

        assertThat(actor.type()).isEqualTo(ActorType.USER);
        assertThat(actor.tenantId()).isEqualTo(new TenantId(TENANT));
        assertThat(actor.scope()).isEqualTo(PermissionScope.TENANT);
        assertThat(actor.has("platform:user:read")).isTrue();
    }

    @Test
    void theTokensOwnScopeClaimGrantsAUserNothing() {
        Jwt token = Jwt.withTokenValue("t").header("alg", "none").subject(SUBJECT).claim("scope", "platform:tenant:create").build();

        Actor actor = convert(converter("ACTIVE", TENANT), token, TENANT);

        assertThat(actor.has("platform:tenant:create")).isFalse();
    }

    @Test
    void aDisabledUserIsUnauthorized() {
        assertThatThrownBy(() -> convert(converter("DISABLED", TENANT), userToken(), TENANT))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(401));
    }

    @Test
    void aUserOfAnotherTenantIsUnauthorizedOnThisHost() {
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> convert(converter("ACTIVE", TENANT), userToken(), other))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(401));
    }

    @Test
    void aTenantSystemTokenForAnotherTenantIsForbidden() {
        Jwt token = Jwt.withTokenValue("t").header("alg", "none")
                .subject("tenant-system/" + UUID.randomUUID())
                .claim("tenant_id", TENANT.toString())
                .claim("scope", "billing:invoice:read")
                .build();

        assertThatThrownBy(() -> convert(converter("ACTIVE", TENANT), token, UUID.randomUUID()))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(403));
    }

    @Test
    void aTenantSystemTokenForTheHostTenantCarriesItsScopeCodes() {
        UUID system = UUID.randomUUID();
        Jwt token = Jwt.withTokenValue("t").header("alg", "none")
                .subject("tenant-system/" + system)
                .claim("tenant_id", TENANT.toString())
                .claim("tenant_system_id", system.toString())
                .claim("scope", "billing:invoice:read")
                .build();

        Actor actor = convert(converter("ACTIVE", TENANT), token, TENANT);

        assertThat(actor.type()).isEqualTo(ActorType.TENANT_SYSTEM);
        assertThat(actor.id()).isEqualTo(system);
        assertThat(actor.permissions()).containsExactly("billing:invoice:read");
    }

    private Actor convert(JwtActorConverter converter, Jwt token, UUID hostTenant) {
        return tenantContext.callAs(
                RequestScope.tenant(new TenantId(hostTenant)),
                () -> ((ActorAuthentication) converter.convert(token)).actor());
    }

    private JwtActorConverter converter(String status, UUID userTenant) {
        UserView user = new UserView(UUID.randomUUID(), userTenant, "TENANT", SUBJECT, "a@b.c", "Ann", status, 1);
        UserReadRepository repository = new UserReadRepository() {
            @Override public List<UserView> list(UUID tenantId) { return List.of(); }
            @Override public Optional<UserView> findById(UUID id) { return Optional.of(user); }
            @Override public Optional<UserView> findByKeycloakSubject(String subject) {
                return SUBJECT.equals(subject) ? Optional.of(user) : Optional.empty();
            }
            @Override public Set<String> effectivePermissions(UUID userId) { return Set.of("platform:user:read"); }
            @Override public List<UserAccessView> pageAccess(UUID after, int limit) { return List.of(); }
        };
        UnitOfWork unitOfWork = new UnitOfWork() {
            @Override public <T> T transactional(Supplier<T> work) { return work.get(); }
        };
        return new JwtActorConverter(new MeQueries(unitOfWork, repository), tenantContext);
    }

    private static Jwt userToken() {
        return Jwt.withTokenValue("t").header("alg", "none").subject(SUBJECT).claim("scope", "openid profile").build();
    }

    private static int statusOf(Throwable failure) {
        return ((BearerTokenError) ((OAuth2AuthenticationException) failure).getError()).getHttpStatus().value();
    }
}
