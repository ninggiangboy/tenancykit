package dev.ngb.platform.tenancy.application.uiremote.command;

import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.names.AggregateTargets;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.tenancy.application.uiremote.exception.UnknownUiRemoteException;
import dev.ngb.platform.tenancy.application.uiremote.port.UiRemoteRepository;
import dev.ngb.platform.tenancy.domain.uiremote.UiRemote;
import java.time.Instant;

/**
 * The operator's one write on a remote: enable or disable it, point it at a registered version, and edit its title and
 * navigation order. Activating a version is this call, which is why a deploy never goes live by itself and a rollback
 * is the same click on an older version.
 */
public class SetUiRemoteStateUseCase {

    /** A null field keeps its stored value; {@code expected} is the caller's {@code If-Match}. */
    public record Command(
            String name, Boolean enabled, String activeVersion, String title, Integer navOrder, String actor,
            String requestId, ExpectedVersion expected) {}

    private final UnitOfWork unitOfWork;
    private final UiRemoteRepository remotes;
    private final AuditLog auditLog;
    private final Clock clock;

    public SetUiRemoteStateUseCase(UnitOfWork unitOfWork, UiRemoteRepository remotes, AuditLog auditLog, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.remotes = remotes;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public UiRemote execute(Command command) {
        return unitOfWork.transactional(() -> {
            UiRemote remote = remotes.lock(command.name()).orElseThrow(() -> new UnknownUiRemoteException(command.name()));
            command.expected().check(remote.version());
            if (command.activeVersion() != null && remotes.findVersion(command.name(), command.activeVersion()).isEmpty()) {
                throw new IllegalArgumentException(
                        "version " + command.activeVersion() + " of " + command.name() + " is not registered");
            }
            boolean wasEnabled = remote.isEnabled();
            String previousActive = remote.activeVersion();
            if (!remote.update(command.enabled(), command.activeVersion(), command.title(), command.navOrder())) {
                return remote;
            }
            remotes.update(remote);
            Instant now = clock.now();
            auditLog.record(AuditEntry.platform(
                    command.actor(), action(remote, wasEnabled, previousActive), AggregateTargets.uiRemote(remote.name()),
                    command.requestId(), now));
            return remote;
        });
    }

    private static String action(UiRemote remote, boolean wasEnabled, String previousActive) {
        if (!java.util.Objects.equals(previousActive, remote.activeVersion())) {
            return "ui-remote.activated";
        }
        if (wasEnabled != remote.isEnabled()) {
            return remote.isEnabled() ? "ui-remote.enabled" : "ui-remote.disabled";
        }
        return "ui-remote.updated";
    }
}
