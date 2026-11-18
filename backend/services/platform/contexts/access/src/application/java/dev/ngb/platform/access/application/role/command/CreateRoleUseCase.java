package dev.ngb.platform.access.application.role.command;

import dev.ngb.core.audit.Audit;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.platform.access.application.permission.exception.UnknownPermissionException;
import dev.ngb.platform.access.application.permission.port.PermissionCatalog;
import dev.ngb.platform.access.application.role.port.RoleRepository;
import dev.ngb.platform.access.domain.role.Role;
import dev.ngb.platform.access.domain.role.RoleId;
import java.time.Instant;
import java.util.Set;

/** Creates a custom role; every code must be catalogued and valid in the role's scope, and the role never exceeds the actor's scope. */
public class CreateRoleUseCase {

    public record Command(TenantId tenantId, PermissionScope scope, String name, Set<String> permissions) {}

    private final UnitOfWork unitOfWork;
    private final RoleRepository roles;
    private final PermissionCatalog catalog;
    private final AuditLog auditLog;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateRoleUseCase(
            UnitOfWork unitOfWork, RoleRepository roles, PermissionCatalog catalog, AuditLog auditLog, IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.roles = roles;
        this.catalog = catalog;
        this.auditLog = auditLog;
        this.ids = ids;
        this.clock = clock;
    }

    public Role execute(Actor actor, String requestId, Command command) {
        requireNotWider(command.scope(), actor.scope());
        return unitOfWork.transactional(() -> {
            command.permissions().forEach(code -> requireValid(code, command.scope()));
            Instant now = clock.now();
            Role role = Role.create(
                    new RoleId(ids.newId()), command.tenantId(), command.scope(), command.name(), false, command.permissions(), now);
            roles.add(role);
            Audit caller = Audit.of(actor.auditName(), requestId);
            auditLog.record(AuditEntry.tenant(
                    actor.tenantId(), caller.actor(), "role.created", "role/" + role.id(), caller.requestId(), now));
            return role;
        });
    }

    private void requireValid(String code, PermissionScope scope) {
        var permission = catalog.find(code).orElseThrow(() -> new UnknownPermissionException(code, "is not in the catalog"));
        if (!permission.validIn(scope)) {
            throw new UnknownPermissionException(code, "is not valid in " + scope + " scope");
        }
    }

    private static void requireNotWider(PermissionScope requested, PermissionScope caller) {
        if (requested == PermissionScope.PLATFORM && caller == PermissionScope.TENANT) {
            throw new AccessDeniedException("a tenant actor cannot create a platform-scope role");
        }
    }
}
