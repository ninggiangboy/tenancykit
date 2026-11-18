package dev.ngb.platform.boot.security;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.ActorType;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.access.api.user.MeQueries;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;

/**
 * Turns a verified token into the actor it names.
 *
 * <ul>
 *   <li>A Keycloak user token carries only the subject. The actor is the active Platform user with that subject in
 *       the hostname's tenant, holding the permissions Platform computes for them; the token's own {@code scope}
 *       claim ("openid profile") never grants anything.
 *   <li>A Platform suite token (service client or Tenant System) carries its permission codes in {@code scope}. A
 *       Tenant System token naming another tenant than the host resolved to is refused with 403.
 * </ul>
 */
public class JwtActorConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final MeQueries users;
    private final TenantContext tenantContext;

    public JwtActorConverter(MeQueries users, TenantContext tenantContext) {
        this.users = users;
        this.tenantContext = tenantContext;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String subject = jwt.getSubject() == null ? "" : jwt.getSubject();
        if (subject.startsWith("tenant-system/")) {
            return new ActorAuthentication(tenantSystem(jwt, subject));
        }
        if (subject.startsWith("service/")) {
            return new ActorAuthentication(service(jwt, subject));
        }
        return new ActorAuthentication(user(subject));
    }

    private Actor user(String subject) {
        TenantId hostTenant = tenantContext.current().dataTenantId();
        return users.findBySubject(subject)
                .filter(me -> "ACTIVE".equals(me.user().status()))
                .filter(me -> hostTenant.value().equals(me.user().tenantId()))
                .map(me -> new Actor(
                        ActorType.USER,
                        me.user().id(),
                        new TenantId(me.user().tenantId()),
                        PermissionScope.valueOf(me.user().scope()),
                        me.user().displayName(),
                        Set.copyOf(me.permissions())))
                .orElseThrow(() -> refused(HttpStatus.UNAUTHORIZED, BearerTokenErrorCodes.INVALID_TOKEN, "the user is not active here"));
    }

    private Actor tenantSystem(Jwt jwt, String subject) {
        String tenantClaim = jwt.getClaimAsString("tenant_id");
        if (tenantClaim == null) {
            throw refused(HttpStatus.UNAUTHORIZED, BearerTokenErrorCodes.INVALID_TOKEN, "the token names no tenant");
        }
        TenantId tenant = new TenantId(UUID.fromString(tenantClaim));
        if (!tenant.equals(tenantContext.current().dataTenantId())) {
            throw refused(HttpStatus.FORBIDDEN, BearerTokenErrorCodes.INSUFFICIENT_SCOPE, "the token belongs to another tenant");
        }
        String systemClaim = jwt.getClaimAsString("tenant_system_id");
        UUID systemId = systemClaim == null ? subjectId(subject) : UUID.fromString(systemClaim);
        return new Actor(ActorType.TENANT_SYSTEM, systemId, tenant, PermissionScope.TENANT, subject, permissions(jwt));
    }

    private Actor service(Jwt jwt, String subject) {
        return new Actor(
                ActorType.SERVICE, subjectId(subject), RequestScope.PLATFORM_TENANT_ID, PermissionScope.PLATFORM, subject, permissions(jwt));
    }

    private static UUID subjectId(String subject) {
        return UUID.nameUUIDFromBytes(subject.getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> permissions(Jwt jwt) {
        String scope = jwt.getClaimAsString("scope");
        if (scope == null || scope.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(scope.split(" "))
                .filter(code -> code.contains(":"))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static OAuth2AuthenticationException refused(HttpStatus status, String code, String description) {
        return new OAuth2AuthenticationException(new BearerTokenError(code, status, description, null));
    }
}
