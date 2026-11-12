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
 *
 * <p>Every action takes a role lock before it touches anything: a rename or a permission replacement takes
 * {@code for no key update}, an assign or unassign takes {@code for share}. The two conflict, so an assign racing a
 * permission replacement on one role serialises, and whichever runs second sees the other's committed rows.
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
        return unitOfWork.transactional(() -> switch (command.action()) {
            case REPLACE_PERMISSIONS -> replacePermissions(lockedForUpdate(command.roleId()), command.permissions());
            case RENAME -> rename(lockedForUpdate(command.roleId()), command.name());
            case ASSIGN -> assign(lockedForShare(command.roleId()), command.userId());
            case UNASSIGN -> unassign(lockedForShare(command.roleId()), command.userId());
        });
    }

    private Role lockedForUpdate(RoleId roleId) {
        return roles.lockForUpdate(roleId).orElseThrow(() -> new RoleNotFoundException(roleId.toString()));
    }

    private Role lockedForShare(RoleId roleId) {
        return roles.lockForShare(roleId).orElseThrow(() -> new RoleNotFoundException(roleId.toString()));
    }

    private Role replacePermissions(Role role, Set<String> permissions) {
        permissions.forEach(code -> {
            var permission = catalog.find(code).orElseThrow(() -> new UnknownPermissionException(code, "is not in the catalog"));
            if (!permission.validIn(role.scope())) {
                throw new UnknownPermissionException(code, "is not valid in " + role.scope() + " scope");
            }
        });
        if (!role.replacePermissions(permissions)) {
            return role;
        }
        roles.update(role);
        publisher.publishForRole(role.id());
        return role;
    }

    private Role rename(Role role, String name) {
        if (role.rename(name)) {
            roles.update(role);
        }
        return role;
    }

    private Role assign(Role role, UserId userId) {
        User user = users.lockById(userId).orElseThrow(() -> new UserNotFoundException(userId.toString()));
        access.assign(role.tenantId().value(), user.id().value(), role.id().value());
        publisher.publishFor(user);
        return role;
    }

    private Role unassign(Role role, UserId userId) {
        User user = users.lockById(userId).orElseThrow(() -> new UserNotFoundException(userId.toString()));
        access.unassign(role.tenantId().value(), user.id().value(), role.id().value());
        publisher.publishFor(user);
        return role;
    }
}
