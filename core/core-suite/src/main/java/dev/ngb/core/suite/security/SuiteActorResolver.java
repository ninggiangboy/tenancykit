package dev.ngb.core.suite.security;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.names.ActorNames;
import dev.ngb.core.replica.ReplicaTables;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.ActorType;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.tenancy.TenantContext;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns a verified token into the {@link Actor} it names, from the replica. Two shapes:
 *
 * <ul>
 *   <li>A Keycloak user token carries only the subject; the actor is the active {@code user_grants} row for that
 *       subject whose tenant is the hostname's tenant. Platform tokens and other issuers never reach this path.
 *   <li>A Platform token names a Tenant System: the actor is built from its claims after checking the tenant system
 *       is {@code ACTIVE} in the replica and that the token's {@code tenant_id} equals the hostname's tenant.
 * </ul>
 *
 * <p>The tenant always comes from the resolved host, never from a client-supplied value.
 */
public class SuiteActorResolver {

    /** The subject prefix Platform uses for a Tenant System token. */
    public static final String TENANT_SYSTEM_SUBJECT = ActorNames.TENANT_SYSTEM_SUBJECT;

    /** The subject prefix Platform uses for a service-client token. */
    public static final String SERVICE_SUBJECT = ActorNames.SERVICE_SUBJECT;

    private final UnitOfWork unitOfWork;
    private final DSLContext dsl;
    private final TenantContext tenantContext;

    public SuiteActorResolver(UnitOfWork unitOfWork, DSLContext dsl, TenantContext tenantContext) {
        this.unitOfWork = unitOfWork;
        this.dsl = dsl;
        this.tenantContext = tenantContext;
    }

    /**
     * @return the actor, or empty when the identity is not active in the host's tenant (the caller answers 401), which
     *     includes a token whose claims cannot name one: a blank subject, a subject that is not a UUID and a
     *     {@code tenant_id} that is not a UUID are all "no actor", never an error
     * @throws TenantMismatchException when a Tenant System token names a different tenant than the host resolved to
     */
    public Optional<Actor> resolve(Jwt jwt) {
        String subject = jwt.getSubject() == null ? "" : jwt.getSubject();
        if (subject.startsWith(TENANT_SYSTEM_SUBJECT)) {
            return tenantSystemActor(jwt, subject.substring(TENANT_SYSTEM_SUBJECT.length()));
        }
        if (subject.isBlank() || subject.startsWith(SERVICE_SUBJECT)) {
            return Optional.empty();
        }
        return userActor(jwt, subject);
    }

    private Optional<Actor> userActor(Jwt jwt, String subject) {
        UUID tenantId = tenantContext.current().dataTenantId().value();
        return unitOfWork.transactional(() -> dsl
                .select(
                        ReplicaTables.GRANT_USER_ID,
                        ReplicaTables.GRANT_TENANT_ID,
                        ReplicaTables.GRANT_SCOPE,
                        ReplicaTables.GRANT_DISPLAY_NAME,
                        ReplicaTables.GRANT_STATUS,
                        ReplicaTables.GRANT_PERMISSIONS,
                        ReplicaTables.GRANT_VERSION)
                .from(ReplicaTables.USER_GRANTS)
                .where(ReplicaTables.GRANT_SUBJECT.eq(subject))
                .and(ReplicaTables.GRANT_TENANT_ID.eq(tenantId))
                .limit(1)
                .fetchOptional())
                .filter(row -> "ACTIVE".equals(row.value5()))
                .map(row -> new Actor(
                        ActorType.USER,
                        row.value1(),
                        new TenantId(row.value2()),
                        PermissionScope.valueOf(row.value3()),
                        row.value4(),
                        permissions(row.value6())));
    }

    private Optional<Actor> tenantSystemActor(Jwt jwt, String systemIdClaim) {
        Optional<UUID> tokenTenant = uuidClaim(jwt.getClaimAsString("tenant_id"));
        Optional<UUID> systemId = uuidClaim(systemIdClaim);
        if (tokenTenant.isEmpty() || systemId.isEmpty()) {
            return Optional.empty();
        }
        UUID hostTenant = tenantContext.current().dataTenantId().value();
        if (!tokenTenant.get().equals(hostTenant)) {
            throw new TenantMismatchException();
        }
        return unitOfWork.transactional(() -> dsl
                        .select(ReplicaTables.SYSTEM_STATUS, ReplicaTables.SYSTEM_NAME, ReplicaTables.SYSTEM_TENANT_ID)
                        .from(ReplicaTables.TENANT_SYSTEMS)
                        .where(ReplicaTables.SYSTEM_ID.eq(systemId.get()))
                        .and(ReplicaTables.SYSTEM_TENANT_ID.eq(hostTenant))
                        .limit(1)
                        .fetchOptional())
                .filter(row -> "ACTIVE".equals(row.value1()))
                .map(row -> new Actor(
                        ActorType.TENANT_SYSTEM,
                        systemId.get(),
                        new TenantId(row.value3()),
                        PermissionScope.TENANT,
                        row.value2(),
                        scope(jwt)));
    }

    /** The claim as a UUID, or empty when it is absent, blank or not a UUID at all. */
    private static Optional<UUID> uuidClaim(String claim) {
        if (claim == null || claim.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(claim));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    private static Set<String> permissions(String[] codes) {
        return codes == null ? Set.of() : Arrays.stream(codes).collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> scope(Jwt jwt) {
        String scope = jwt.getClaimAsString("scope");
        if (scope == null || scope.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(scope.split(" "))
                .filter(code -> code.contains(":"))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The Tenant System token's tenant differs from the host's tenant. */
    public static class TenantMismatchException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public TenantMismatchException() {
            super("the token belongs to another tenant");
        }
    }
}
