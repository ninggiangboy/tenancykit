package dev.ngb.platform.access.application.role.command;

import dev.ngb.core.audit.Audit;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.access.application.access.port.AccessRepository;
import dev.ngb.platform.access.application.access.service.AccessPublisher;
import dev.ngb.platform.access.application.permission.exception.UnknownPermissionException;
import dev.ngb.platform.access.application.permission.port.PermissionCatalog;
import dev.ngb.platform.access.application.role.port.RoleRepository;
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

    public record Command(
            RoleId roleId, Action action, Set<String> permissions, String name, UserId userId, ExpectedVersion expected) {}

    private final UnitOfWork unitOfWork;
    private final RoleRepository roles;
    private final UserRepository users;
    private final AccessRepository access;
    private final PermissionCatalog catalog;
    private final AccessPublisher publisher;
    private final AuditLog auditLog;
    private final Clock clock;

    public ManageRoleUseCase(
            UnitOfWork unitOfWork, RoleRepository roles, UserRepository users, AccessRepository access, PermissionCatalog catalog,
            AccessPublisher publisher, AuditLog auditLog, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.roles = roles;
        this.users = users;
        this.access = access;
        this.catalog = catalog;
        this.publisher = publisher;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public Role execute(Actor actor, String requestId, Command command) {
        return unitOfWork.transactional(() -> switch (command.action()) {
            case REPLACE_PERMISSIONS -> replacePermissions(actor, requestId, lockedForUpdate(command), command.permissions());
            case RENAME -> rename(actor, requestId, lockedForUpdate(command), command.name());
            case ASSIGN -> assign(actor, requestId, lockedForShare(command.roleId()), command.userId());
            case UNASSIGN -> unassign(actor, requestId, lockedForShare(command.roleId()), command.userId());
        });
    }

    /**
     * A rename and a permission replacement carry a version the caller may pin with {@code If-Match}, so they check it
     * under the lock, before any mutation. An assign changes the user's access, not the role, and takes no version.
     */
    private Role lockedForUpdate(Command command) {
        Role role = roles.lockForUpdate(command.roleId()).orElseThrow(() -> new NotFoundException("no role " + command.roleId()));
        command.expected().check(role.version());
        return role;
    }

    private Role lockedForShare(RoleId roleId) {
        return roles.lockForShare(roleId).orElseThrow(() -> new NotFoundException("no role " + roleId));
    }

    private Role replacePermissions(Actor actor, String requestId, Role role, Set<String> permissions) {
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
        audit(actor, requestId, "role.permissions-replaced", "role/" + role.id());
        return role;
    }

    private Role rename(Actor actor, String requestId, Role role, String name) {
        if (role.rename(name)) {
            roles.update(role);
            audit(actor, requestId, "role.renamed", "role/" + role.id());
        }
        return role;
    }

    private Role assign(Actor actor, String requestId, Role role, UserId userId) {
        requireNotWider(role.scope(), actor.scope());
        User user = users.lockById(userId).orElseThrow(() -> new NotFoundException("no user " + userId));
        access.assign(role.tenantId().value(), user.id().value(), role.id().value());
        publisher.publishFor(user);
        audit(actor, requestId, "role.assigned", "role/" + role.id() + "/user/" + user.id());
        return role;
    }

    private Role unassign(Actor actor, String requestId, Role role, UserId userId) {
        requireNotWider(role.scope(), actor.scope());
        User user = users.lockById(userId).orElseThrow(() -> new NotFoundException("no user " + userId));
        access.unassign(role.tenantId().value(), user.id().value(), role.id().value());
        publisher.publishFor(user);
        audit(actor, requestId, "role.unassigned", "role/" + role.id() + "/user/" + user.id());
        return role;
    }

    private void audit(Actor actor, String requestId, String action, String target) {
        Audit caller = Audit.of(actor.auditName(), requestId);
        TenantId tenantId = actor.tenantId();
        auditLog.record(AuditEntry.tenant(tenantId, caller.actor(), action, target, caller.requestId(), clock.now()));
    }

    private static void requireNotWider(PermissionScope roleScope, PermissionScope caller) {
        if (roleScope == PermissionScope.PLATFORM && caller == PermissionScope.TENANT) {
            throw new AccessDeniedException("a tenant actor cannot assign a platform-scope role");
        }
    }
}
