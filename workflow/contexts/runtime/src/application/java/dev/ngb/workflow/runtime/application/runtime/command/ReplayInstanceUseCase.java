package dev.ngb.workflow.runtime.application.runtime.command;

import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.workflow.definitions.api.version.PublishedGraph;
import dev.ngb.workflow.definitions.api.version.VersionReader;
import dev.ngb.workflow.runtime.application.runtime.port.InstanceRepository;
import dev.ngb.workflow.runtime.application.runtime.port.TokenRepository;
import dev.ngb.workflow.runtime.application.runtime.port.WakeUpPublisher;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.token.Token;
import java.time.Instant;
import java.util.UUID;

/**
 * Replays a finished instance: a new instance starts from the same input and the same pinned version, and records the
 * instance it was replayed from. The old instance stays as it was, so the pair is the comparison.
 */
public class ReplayInstanceUseCase {

    public record Command(UUID instanceId) {}

    private final UnitOfWork unitOfWork;
    private final InstanceRepository instances;
    private final TokenRepository tokens;
    private final VersionReader versions;
    private final WakeUpPublisher wakeUp;
    private final IdGenerator ids;
    private final Clock clock;

    public ReplayInstanceUseCase(
            UnitOfWork unitOfWork,
            InstanceRepository instances,
            TokenRepository tokens,
            VersionReader versions,
            WakeUpPublisher wakeUp,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.instances = instances;
        this.tokens = tokens;
        this.versions = versions;
        this.wakeUp = wakeUp;
        this.ids = ids;
        this.clock = clock;
    }

    public Instance execute(Command command) {
        Instance replayed = unitOfWork.transactional(() -> {
            Instance source = instances
                    .findById(command.instanceId())
                    .orElseThrow(() -> new NotFoundException("No workflow instance exists here."));
            if (!source.terminal()) {
                throw new ConflictException("The instance has not finished, so it cannot be replayed.");
            }
            PublishedGraph version = versions
                    .graph(source.versionId())
                    .orElseThrow(() -> new NotFoundException("The version this instance pins is not readable."));
            Instant now = clock.now();
            Instance copy = Instance.start(
                            ids.newId(),
                            source.tenantId(),
                            source.definitionId(),
                            source.versionId(),
                            source.versionNumber(),
                            source.input(),
                            source.correlationKey(),
                            source.startedBy(),
                            null,
                            null,
                            now)
                    .replayedFrom(source.id());
            instances.add(copy);
            tokens.add(Token.ready(
                    ids.newId(), source.tenantId(), copy.id(), version.graph().start(), null, 0, now));
            return copy;
        });
        wakeUp.wake(replayed.tenantId());
        return replayed;
    }
}
