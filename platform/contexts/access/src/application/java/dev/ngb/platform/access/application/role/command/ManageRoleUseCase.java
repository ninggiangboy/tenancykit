package dev.ngb.platform.access.application.role.command;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.platform.access.application.access.port.AccessRepository;
import dev.ngb.platform.access.application.access.service.AccessPublisher;
import dev.ngb.platform.access.application.permission.exception.UnknownPermissionException;
import dev.ngb.platform.access.application.permission.port.PermissionCatalog;
import dev.ngb.platform.access.application.role.exception.RoleNotFoundException;
import dev.ngb.platform.access.application.role.port.RoleRepository;
import dev.ngb.platform.access.application.user.exception.UserNotFoundException;
import dev.ngb.platform.access.application.user.port.UserRepository;
import dev.ngb.platform.access.domain.role.Role;
import dev.ngb.platform.access.domain.role.RoleId;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.access.domain.user.UserId;
import java.util.Set;

/**
 * Edits a role or (un)assigns it. Changing the permission set recomputes every holder, so consumers never need role
 * or join logic.
 */
public class ManageRoleUseCase {

    public enum Action { REPLACE_PERMISSIONS, RENAME, ASSIGN, UNASSIGN }

    public record Command(RoleId roleId, Action action, Set<String> permissions, String name, UserId userId) {}

    private final UnitOfWork unitOfWork;
    private final RoleRepository roles;
    private final UserRepository users;
    private final AccessRepository access;
    private final PermissionCatalog catalog;
    private final AccessPublisher publisher;

    public ManageRoleUseCase(
            UnitOfWork unitOfWork, RoleRepository roles, UserRepository users, AccessRepository access, PermissionCatalog catalog,
            AccessPublisher publisher) {
        this.unitOfWork = unitOfWork;
        this.roles = roles;
        this.users = users;
        this.access = access;
        this.catalog = catalog;
        this.publisher = publisher;
    }

    public Role execute(Command command) {
        return unitOfWork.transactional(() -> {
            Role role = roles.findById(command.roleId()).orElseThrow(() -> new RoleNotFoundException(command.roleId().toString()));
            switch (command.action()) {
                case REPLACE_PERMISSIONS -> replacePermissions(role, command.permissions());
                case RENAME -> {
                    if (role.rename(command.name())) {
                        roles.update(role);
                    }
                }
                case ASSIGN -> assign(role, command.userId());
                case UNASSIGN -> unassign(role, command.userId());
            }
            return role;
        });
    }

    private void replacePermissions(Role role, Set<String> permissions) {
        permissions.forEach(code -> {
            var permission = catalog.find(code).orElseThrow(() -> new UnknownPermissionException(code, "is not in the catalog"));
            if (!permission.validIn(role.scope())) {
                throw new UnknownPermissionException(code, "is not valid in " + role.scope() + " scope");
            }
        });
        if (!role.replacePermissions(permissions)) {
            return;
        }
        roles.update(role);
        publisher.publishForRole(role.id());
    }

    private void assign(Role role, UserId userId) {
        User user = users.findById(userId).orElseThrow(() -> new UserNotFoundException(userId.toString()));
        access.assign(role.tenantId().value(), user.id().value(), role.id().value());
        publisher.publishFor(user);
    }

    private void unassign(Role role, UserId userId) {
        User user = users.findById(userId).orElseThrow(() -> new UserNotFoundException(userId.toString()));
        access.unassign(role.tenantId().value(), user.id().value(), role.id().value());
        publisher.publishFor(user);
    }
}
