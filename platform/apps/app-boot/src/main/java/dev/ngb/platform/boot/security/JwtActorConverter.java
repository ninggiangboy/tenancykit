package dev.ngb.platform.boot.security;

import dev.ngb.core.security.Actor;
import dev.ngb.core.security.ActorType;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.tenancy.RequestScope;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns a verified token into the actor it names. Only Codes with a namespace are permissions; a Keycloak token's
 * {@code scope} claim ("openid profile") never becomes one.
 */
public class JwtActorConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String subject = jwt.getSubject() == null ? "" : jwt.getSubject();
        ActorType type = subject.startsWith("tenant-system/")
                ? ActorType.TENANT_SYSTEM
                : subject.startsWith("service/") ? ActorType.SERVICE : ActorType.USER;
        String tenantId = jwt.getClaimAsString("tenant_id");
        UUID id = jwt.getId() == null ? UUID.nameUUIDFromBytes(subject.getBytes(StandardCharsets.UTF_8)) : UUID.fromString(jwt.getId());
        PermissionScope scope = tenantId == null
                ? PermissionScope.PLATFORM
                : PermissionScope.TENANT;
        dev.ngb.core.id.TenantId tenant = tenantId == null
                ? RequestScope.PLATFORM_TENANT_ID
                : new dev.ngb.core.id.TenantId(UUID.fromString(tenantId));
        return new ActorAuthentication(new Actor(type, id, tenant, scope, subject, permissions(jwt)));
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
}
