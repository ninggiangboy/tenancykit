package dev.ngb.platform.access.application.user.command;

import dev.ngb.core.names.AggregateTargets;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.platform.access.application.access.service.AccessPublisher;
import dev.ngb.platform.access.application.auth.port.IdentityProviderPort;
import dev.ngb.platform.access.application.user.port.UserRepository;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.access.domain.user.UserId;
import java.time.Instant;

/**
 * Creates the Keycloak identity and the Platform user. The identity is created before the unit of work, so no HTTP
 * call ever runs inside a transaction, and it is deleted again when the write fails.
 */
public class CreateUserUseCase {

    public record Command(
            String tenantCode, TenantId tenantId, PermissionScope scope, String email, String displayName, String temporaryPassword) {}

    private final UnitOfWork unitOfWork;
    private final UserRepository users;
    private final IdentityProviderPort identities;
    private final AccessPublisher access;
    private final AuditLog auditLog;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateUserUseCase(
            UnitOfWork unitOfWork, UserRepository users, IdentityProviderPort identities, AccessPublisher access,
            AuditLog auditLog, IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.users = users;
        this.identities = identities;
        this.access = access;
        this.auditLog = auditLog;
        this.ids = ids;
        this.clock = clock;
    }

    public User execute(Actor actor, String requestId, Command command) {
        requireNotWider(command.scope(), actor.scope());
        String subject = createIdentity(
                command.tenantCode(), command.email(), command.displayName(), command.temporaryPassword());
        try {
            return persist(actor, requestId, command, subject);
        } catch (RuntimeException e) {
            deleteIdentity(subject);
            throw e;
        }
    }

    /** Creates the Keycloak identity outside any transaction and answers its subject. */
    public String createIdentity(String tenantCode, String email, String displayName, String temporaryPassword) {
        return identities.createUser(tenantCode + ":" + email, email, displayName, temporaryPassword);
    }

    /**
     * Persists the user whose identity already exists, in the caller's unit of work. A caller that opened a wider
     * transaction owns the compensation for {@code keycloakSubject}.
     */
    public User persist(Actor actor, String requestId, Command command, String keycloakSubject) {
        return unitOfWork.transactional(() -> {
            if (users.findByEmail(command.tenantId(), command.email()).isPresent()) {
                throw new ConflictException("a user with this email already exists: " + command.email());
            }
            Instant now = clock.now();
            User user = User.create(
                    new UserId(ids.newId()), command.tenantId(), command.scope(), keycloakSubject, command.email(),
                    command.displayName(), now);
            users.add(user);
            access.publishNew(user);
            Audit caller = Audit.of(actor.auditName(), requestId);
            auditLog.record(AuditEntry.tenant(
                    actor.tenantId(), caller.actor(), "user.created", AggregateTargets.user(user.id()), caller.requestId(), now));
            return user;
        });
    }

    /** Removes an identity created by {@link #createIdentity} whose user was never persisted. */
    public void deleteIdentity(String keycloakSubject) {
        identities.deleteUser(keycloakSubject);
    }

    private static void requireNotWider(PermissionScope requested, PermissionScope caller) {
        if (requested == PermissionScope.PLATFORM && caller == PermissionScope.TENANT) {
            throw new AccessDeniedException("a tenant actor cannot create a platform-scope user");
        }
    }
}
