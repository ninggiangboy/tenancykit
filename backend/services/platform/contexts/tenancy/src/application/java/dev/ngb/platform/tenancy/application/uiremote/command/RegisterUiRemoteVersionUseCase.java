package dev.ngb.platform.tenancy.application.uiremote.command;

import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.names.AggregateTargets;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.platform.tenancy.application.uiremote.exception.UiRemoteVersionConflictException;
import dev.ngb.platform.tenancy.application.uiremote.port.UiRemoteRepository;
import dev.ngb.platform.tenancy.domain.uiremote.RemoteAudience;
import dev.ngb.platform.tenancy.domain.uiremote.UiRemote;
import dev.ngb.platform.tenancy.domain.uiremote.UiRemoteVersion;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Registers one version of a UI remote, as CI does after publishing the build. Registering creates the remote on its
 * first call, never enables it and never changes the active version: a deploy cannot reach a browser until an operator
 * activates the version.
 *
 * <p>Registering the same version with the same content again is the same result; registering it with different content
 * is a conflict, because a version is immutable once a shell may have verified it.
 */
public class RegisterUiRemoteVersionUseCase {

    /** The fields a first registration needs; later registrations ignore everything but the version itself. */
    public record Command(
            String name, String version, String entryPath, String integrity, int sdkMajor, String appCode, String title,
            List<String> audiences, Integer navOrder, String actor, String requestId) {}

    /** {@code created} is false when this version was already registered with the same content. */
    public record Result(UiRemoteVersion version, boolean created) {}

    private final UnitOfWork unitOfWork;
    private final UiRemoteRepository remotes;
    private final AuditLog auditLog;
    private final Clock clock;

    public RegisterUiRemoteVersionUseCase(UnitOfWork unitOfWork, UiRemoteRepository remotes, AuditLog auditLog, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.remotes = remotes;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public Result execute(Command command) {
        return unitOfWork.transactional(() -> {
            Instant now = clock.now();
            Optional<UiRemote> existing = remotes.lock(command.name());
            if (existing.isEmpty()) {
                if (command.appCode() == null || command.title() == null) {
                    throw new BadRequestException("a first registration names the app and the title of the remote");
                }
                remotes.insert(UiRemote.register(
                        command.name(), command.appCode(), command.title(), audiences(command.audiences()),
                        command.navOrder() == null ? 100 : command.navOrder(), now));
            }
            UiRemoteVersion candidate = UiRemoteVersion.register(
                    command.name(), command.version(), command.entryPath(), command.integrity(), command.sdkMajor(), now,
                    command.actor());
            Optional<UiRemoteVersion> registered = remotes.findVersion(command.name(), command.version());
            if (registered.isPresent()) {
                if (!registered.get().sameContent(candidate)) {
                    throw new UiRemoteVersionConflictException(command.name(), command.version());
                }
                return new Result(registered.get(), false);
            }
            remotes.insertVersion(candidate);
            auditLog.record(AuditEntry.platform(
                    command.actor(), "ui-remote.version.registered",
                    AggregateTargets.sub(AggregateTargets.uiRemote(command.name()), "version", command.version()),
                    command.requestId(), now));
            return new Result(candidate, true);
        });
    }

    private static Set<RemoteAudience> audiences(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new BadRequestException("a first registration names the audiences the remote is mounted in");
        }
        EnumSet<RemoteAudience> audiences = EnumSet.noneOf(RemoteAudience.class);
        for (String value : raw) {
            audiences.add(RemoteAudience.parse(value));
        }
        return audiences;
    }
}
