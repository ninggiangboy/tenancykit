package dev.ngb.platform.access.application.user.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.ActorType;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.platform.access.application.access.port.AccessRepository;
import dev.ngb.platform.access.application.access.service.AccessPublisher;
import dev.ngb.platform.access.application.permission.port.PermissionCatalog;
import dev.ngb.platform.access.application.role.port.RoleRepository;
import dev.ngb.platform.access.application.user.port.UserRepository;
import dev.ngb.platform.access.domain.role.Role;
import dev.ngb.platform.access.domain.role.RoleId;
import dev.ngb.platform.access.domain.user.User;
import java.util.Set;
import java.util.UUID;

/**
 * Ensures the configured first Platform administrator exists with its role. It runs on every start and is a repair,
 * not a one-shot create: a boot that failed between creating the user and assigning its role would otherwise leave an
 * administrator who can sign in but holds no permission, and every later boot would skip it.
 *
 * <p>Running it again is harmless. The role is found by name instead of being created twice, and the assignment inserts
 * only when it is missing, so a repeated boot rewrites nothing.
 */
public class BootstrapPlatformAdminUseCase {

    private static final String ROLE_NAME = "Platform Administrator";

    private static final String REQUEST_ID = "platform-bootstrap";

    private static final Actor PLATFORM_ACTOR = new Actor(
            ActorType.SERVICE, new UUID(0, 0), RequestScope.PLATFORM_TENANT_ID, PermissionScope.PLATFORM, "platform-bootstrap", Set.of());

    private final UnitOfWork unitOfWork;
    private final CreateUserUseCase createUser;
    private final UserRepository users;
    private final RoleRepository roles;
    private final AccessRepository access;
    private final PermissionCatalog catalog;
    private final AccessPublisher publisher;
    private final IdGenerator ids;
    private final Clock clock;

    public BootstrapPlatformAdminUseCase(
            UnitOfWork unitOfWork, CreateUserUseCase createUser, UserRepository users, RoleRepository roles, AccessRepository access,
            PermissionCatalog catalog, AccessPublisher publisher, IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.createUser = createUser;
        this.users = users;
        this.roles = roles;
        this.access = access;
        this.catalog = catalog;
        this.publisher = publisher;
        this.ids = ids;
        this.clock = clock;
    }

    public void execute(String email, String temporaryPassword) {
        if (email == null || email.isBlank() || temporaryPassword == null || temporaryPassword.isBlank()) {
            return;
        }
        TenantId platform = RequestScope.PLATFORM_TENANT_ID;
        // The lookup is its own unit of work: row-level security reads the tenant from the transaction, so a query
        // outside one sees no user and every boot would try to create the identity again.
        User admin = unitOfWork.transactional(() -> users.findByEmail(platform, email))
                .orElseGet(() -> createUser.execute(PLATFORM_ACTOR, REQUEST_ID, new CreateUserUseCase.Command(
                        "platform", platform, PermissionScope.PLATFORM, email, "Platform Administrator", temporaryPassword)));
        ensureAccess(admin);
    }

    /**
     * Writes the role, the assignment and the published access state in one unit of work, so a failure leaves none of
     * them behind and the next boot retries all three.
     */
    private void ensureAccess(User admin) {
        unitOfWork.transactional(() -> {
            Role role = ensureRole();
            access.assign(RequestScope.PLATFORM_TENANT_ID.value(), admin.id().value(), role.id().value());
            publisher.publishFor(admin.id());
            return null;
        });
    }

    private Role ensureRole() {
        TenantId platform = RequestScope.PLATFORM_TENANT_ID;
        return roles.findByName(platform.value(), ROLE_NAME).orElseGet(() -> {
            Role created = Role.create(
                    new RoleId(ids.newId()), platform, PermissionScope.PLATFORM, ROLE_NAME, true,
                    catalog.codesValidIn(PermissionScope.PLATFORM), clock.now());
            roles.add(created);
            return created;
        });
    }
}
