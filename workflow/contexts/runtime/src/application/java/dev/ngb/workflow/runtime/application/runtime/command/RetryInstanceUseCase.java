package dev.ngb.workflow.runtime.application.runtime.command;

import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.workflow.runtime.application.runtime.port.InstanceRepository;
import dev.ngb.workflow.runtime.application.runtime.port.TokenRepository;
import dev.ngb.workflow.runtime.application.runtime.port.WakeUpPublisher;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.instance.InstanceStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Retries a dead-lettered instance: the failed token becomes READY again with the same id and attempt, so the step that
 * runs next sends the same idempotency key, and the instance goes back to RUNNING with no failure reason.
 */
public class RetryInstanceUseCase {

    /** @param nodeId the one failed node to re-queue, or null for every failed token of the instance */
    public record Command(UUID instanceId, String nodeId) {}

    private final UnitOfWork unitOfWork;
    private final InstanceRepository instances;
    private final TokenRepository tokens;
    private final WakeUpPublisher wakeUp;
    private final Clock clock;

    public RetryInstanceUseCase(
            UnitOfWork unitOfWork,
            InstanceRepository instances,
            TokenRepository tokens,
            WakeUpPublisher wakeUp,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.instances = instances;
        this.tokens = tokens;
        this.wakeUp = wakeUp;
        this.clock = clock;
    }

    public Instance execute(Command command) {
        Instance retried = unitOfWork.transactional(() -> {
            Instance instance = instances
                    .findById(command.instanceId())
                    .orElseThrow(() -> new NotFoundException("No workflow instance exists here."));
            if (instance.status() != InstanceStatus.FAILED_STUCK) {
                throw new ConflictException("The instance is not dead-lettered, so there is nothing to retry.");
            }
            Instant now = clock.now();
            if (tokens.requeueFailed(instance.id(), command.nodeId(), now) == 0) {
                throw new ConflictException("The instance has no failed step to retry.");
            }
            instances.resume(instance.id(), instance.version());
            return instances.findById(instance.id()).orElseThrow();
        });
        wakeUp.wake(retried.tenantId());
        return retried;
    }
}
