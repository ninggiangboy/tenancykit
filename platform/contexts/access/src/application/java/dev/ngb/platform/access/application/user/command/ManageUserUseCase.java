package dev.ngb.platform.access.application.user.command;

import dev.ngb.core.audit.Audit;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Actor;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.access.application.access.service.AccessPublisher;
import dev.ngb.platform.access.application.user.port.UserRepository;
import dev.ngb.platform.access.domain.user.User;
import dev.ngb.platform.access.domain.user.UserId;

/** Enables, disables or renames a user; every effective change publishes the new access and is audited. */
public class ManageUserUseCase {

    public enum Action { ENABLE, DISABLE, RENAME }

    public record Command(UserId userId, Action action, String newName, ExpectedVersion expected) {}

    private final UnitOfWork unitOfWork;
    private final UserRepository users;
    private final AccessPublisher access;
    private final AuditLog auditLog;
    private final Clock clock;

    public ManageUserUseCase(
            UnitOfWork unitOfWork, UserRepository users, AccessPublisher access, AuditLog auditLog, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.users = users;
        this.access = access;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public User execute(Actor actor, String requestId, Command command) {
        return unitOfWork.transactional(() -> {
            User user = users.lockById(command.userId()).orElseThrow(() -> new NotFoundException("no user " + command.userId()));
            command.expected().check(user.version());
            boolean changed = switch (command.action()) {
                case ENABLE -> user.enable();
                case DISABLE -> user.disable();
                case RENAME -> user.rename(command.newName());
            };
            if (!changed) {
                return user;
            }
            users.update(user);
            access.publishFor(user);
            Audit caller = Audit.of(actor.auditName(), requestId);
            auditLog.record(AuditEntry.tenant(
                    actor.tenantId(),
                    caller.actor(),
                    action(command.action()),
                    "user/" + user.id(),
                    caller.requestId(),
                    clock.now()));
            return user;
        });
    }

    private static String action(Action action) {
        return switch (action) {
            case ENABLE -> "user.enabled";
            case DISABLE -> "user.disabled";
            case RENAME -> "user.renamed";
        };
    }
}
