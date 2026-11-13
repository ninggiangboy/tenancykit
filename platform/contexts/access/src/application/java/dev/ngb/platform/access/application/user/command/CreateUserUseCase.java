package dev.ngb.platform.access.application.user.command;

import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.platform.access.application.access.service.AccessPublisher;
import dev.ngb.platform.access.application.auth.port.IdentityProviderPort;
import dev.ngb.platform.access.application.user.port.UserRepository;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.access.domain.user.UserId;
import java.time.Instant;

/** Creates the Keycloak identity and the Platform user in one step, then publishes the user's (empty) access. */
public class CreateUserUseCase {

    public record Command(
            String tenantCode, TenantId tenantId, PermissionScope scope, String email, String displayName, String temporaryPassword) {}

    private final UnitOfWork unitOfWork;
    private final UserRepository users;
    private final IdentityProviderPort identities;
    private final AccessPublisher access;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateUserUseCase(
            UnitOfWork unitOfWork, UserRepository users, IdentityProviderPort identities, AccessPublisher access, IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.users = users;
        this.identities = identities;
        this.access = access;
        this.ids = ids;
        this.clock = clock;
    }

    public User execute(Command command) {
        return unitOfWork.transactional(() -> {
            if (users.findByEmail(command.tenantId(), command.email()).isPresent()) {
                throw new ConflictException("a user with this email already exists: " + command.email());
            }
            String subject = identities.createUser(
                    command.tenantCode() + ":" + command.email(), command.email(), command.displayName(), command.temporaryPassword());
            Instant now = clock.now();
            User user = User.create(
                    new UserId(ids.newId()), command.tenantId(), command.scope(), subject, command.email(), command.displayName(), now);
            users.add(user);
            access.publishNew(user);
            return user;
        });
    }
}
