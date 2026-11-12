package dev.ngb.platform.access.application.user.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
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

/**
 * Creates the first Platform administrator when the deployment configures one. It is idempotent: a configured email
 * that already has a user changes nothing.
 */
public class BootstrapPlatformAdminUseCase {

    private static final String ROLE_NAME = "Platform Administrator";

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
        if (users.findByEmail(platform, email).isPresent()) {
            return;
        }
        User admin = createUser.execute(new CreateUserUseCase.Command(
                "platform", platform, PermissionScope.PLATFORM, email, "Platform Administrator", temporaryPassword));
        unitOfWork.transactional(() -> {
            Role role = roles.findByName(platform.value(), ROLE_NAME).orElseGet(() -> {
                Role created = Role.create(
                        new RoleId(ids.newId()), platform, PermissionScope.PLATFORM, ROLE_NAME, true,
                        catalog.codesValidIn(PermissionScope.PLATFORM), clock.now());
                roles.add(created);
                return created;
            });
            access.assign(platform.value(), admin.id().value(), role.id().value());
            publisher.publishFor(admin);
            return null;
        });
    }
}
