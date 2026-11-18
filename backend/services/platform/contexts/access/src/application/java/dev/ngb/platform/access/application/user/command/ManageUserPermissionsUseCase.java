package dev.ngb.platform.access.application.user.command;

import dev.ngb.core.names.AggregateTargets;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.access.application.access.port.AccessRepository;
import dev.ngb.platform.access.application.access.service.AccessPublisher;
import dev.ngb.platform.access.application.permission.exception.UnknownPermissionException;
import dev.ngb.platform.access.application.permission.port.PermissionCatalog;
import dev.ngb.platform.access.application.user.port.UserRepository;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.access.domain.user.UserId;

/** Grants or revokes one direct permission on a user, outside any role. Each effective change is audited. */
public class ManageUserPermissionsUseCase {

    public record Command(UserId userId, String permissionCode, boolean grant, ExpectedVersion expected) {}

    private final UnitOfWork unitOfWork;
    private final UserRepository users;
    private final AccessRepository access;
    private final PermissionCatalog catalog;
    private final AccessPublisher publisher;
    private final AuditLog auditLog;
    private final Clock clock;

    public ManageUserPermissionsUseCase(
            UnitOfWork unitOfWork, UserRepository users, AccessRepository access, PermissionCatalog catalog,
            AccessPublisher publisher, AuditLog auditLog, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.users = users;
        this.access = access;
        this.catalog = catalog;
        this.publisher = publisher;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public User execute(Actor actor, String requestId, Command command) {
        return unitOfWork.transactional(() -> {
            User user = users.lockById(command.userId()).orElseThrow(() -> new NotFoundException("no user " + command.userId()));
            command.expected().check(user.version());
            var permission = catalog.find(command.permissionCode())
                    .orElseThrow(() -> new UnknownPermissionException(command.permissionCode(), "is not in the catalog"));
            if (!permission.validIn(user.scope())) {
                throw new UnknownPermissionException(command.permissionCode(), "is not valid in " + user.scope() + " scope");
            }
            if (actor.scope() == PermissionScope.TENANT && !permission.validIn(PermissionScope.TENANT)) {
                throw new UnknownPermissionException(command.permissionCode(), "is not valid in tenant scope");
            }
            if (command.grant()) {
                access.grant(user.tenantId().value(), user.id().value(), command.permissionCode());
            } else {
                access.revoke(user.tenantId().value(), user.id().value(), command.permissionCode());
            }
            publisher.publishFor(user);
            Audit caller = Audit.of(actor.auditName(), requestId);
            auditLog.record(AuditEntry.tenant(
                    actor.tenantId(),
                    caller.actor(),
                    command.grant() ? "user.permission-granted" : "user.permission-revoked",
                    AggregateTargets.sub(AggregateTargets.user(user.id()), "permission", command.permissionCode()),
                    caller.requestId(),
                    clock.now()));
            return user;
        });
    }
}
