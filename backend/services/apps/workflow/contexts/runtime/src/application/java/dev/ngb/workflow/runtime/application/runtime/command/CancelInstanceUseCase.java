package dev.ngb.workflow.runtime.application.runtime.command;

import dev.ngb.core.error.ConflictException;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.workflow.definitions.api.version.PublishedGraph;
import dev.ngb.workflow.definitions.api.version.VersionReader;
import dev.ngb.workflow.runtime.application.runtime.port.InstanceRepository;
import dev.ngb.workflow.runtime.application.runtime.port.TaskCanceller;
import dev.ngb.workflow.runtime.application.runtime.port.TimerRepository;
import dev.ngb.workflow.runtime.application.runtime.port.TokenRepository;
import dev.ngb.workflow.runtime.application.runtime.port.WaitSubscriptionRepository;
import dev.ngb.workflow.runtime.application.runtime.port.WakeUpPublisher;
import dev.ngb.workflow.runtime.application.runtime.service.CompensationRunner;
import dev.ngb.workflow.runtime.domain.event.RuntimeEvents;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.instance.InstanceStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Cancels an instance an operator no longer wants: every live token becomes CANCELLED and leaves no timer or event able
 * to resume it, the open tasks are cancelled, and the instance finishes CANCELLED. The caller decides whether the
 * completed steps are rolled back first.
 */
public class CancelInstanceUseCase {

    /**
     * @param instanceId the instance to cancel
     * @param compensate whether the completed steps are rolled back before the instance is cancelled
     */
    public record Command(UUID instanceId, boolean compensate) {}

    private final UnitOfWork unitOfWork;
    private final InstanceRepository instances;
    private final TokenRepository tokens;
    private final TimerRepository timers;
    private final WaitSubscriptionRepository subscriptions;
    private final CompensationRunner compensation;
    private final VersionReader versions;
    private final TaskCanceller tasks;
    private final WakeUpPublisher wakeUp;
    private final Clock clock;
    private final EventPublisher events;
    private final IdGenerator ids;

    public CancelInstanceUseCase(
            UnitOfWork unitOfWork,
            InstanceRepository instances,
            TokenRepository tokens,
            TimerRepository timers,
            WaitSubscriptionRepository subscriptions,
            CompensationRunner compensation,
            VersionReader versions,
            TaskCanceller tasks,
            WakeUpPublisher wakeUp,
            Clock clock,
            EventPublisher events,
            IdGenerator ids) {
        this.unitOfWork = unitOfWork;
        this.instances = instances;
        this.tokens = tokens;
        this.timers = timers;
        this.subscriptions = subscriptions;
        this.compensation = compensation;
        this.versions = versions;
        this.tasks = tasks;
        this.wakeUp = wakeUp;
        this.clock = clock;
        this.events = events;
        this.ids = ids;
    }

    public Instance execute(Command command) {
        Instance instance = unitOfWork
                .transactional(() -> instances.findById(command.instanceId()))
                .orElseThrow(() -> new NotFoundException("No workflow instance exists here."));
        if (instance.terminal()) {
            throw new ConflictException("The instance has already finished.");
        }
        if (command.compensate()) {
            PublishedGraph version = versions
                    .graph(instance.versionId())
                    .orElseThrow(() -> new NotFoundException("The version this instance pins is not readable."));
            compensation.compensate(instance, version.graph());
        }
        Instance cancelled = unitOfWork.transactional(() -> {
            Instant now = clock.now();
            for (UUID tokenId : tokens.liveTokenIds(instance.id())) {
                if (tokens.cancel(tokenId, now)) {
                    timers.cancelForToken(tokenId, now);
                    subscriptions.resolveForToken(tokenId, now);
                }
            }
            tasks.cancelOpen(instance.id());
            instances.finish(instance.id(), InstanceStatus.CANCELLED, now);
            Instance finished = instances.findById(instance.id()).orElseThrow();
            String code = versions.definitionCode(finished.definitionId()).orElse("");
            events.publish(RuntimeEvents.finished(
                    finished, code, InstanceStatus.CANCELLED, ids.newId(), now));
            return finished;
        });
        wakeUp.wake(instance.tenantId());
        return cancelled;
    }
}
