package dev.ngb.workflow.runtime.application.runtime.service;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.workflow.definitions.api.version.GraphNode;
import dev.ngb.workflow.definitions.api.version.PublishedGraph;
import dev.ngb.workflow.definitions.api.version.VersionReader;
import dev.ngb.workflow.runtime.application.runtime.port.InstanceRepository;
import dev.ngb.workflow.runtime.application.runtime.port.JoinStateRepository;
import dev.ngb.workflow.runtime.application.runtime.port.StepRunRepository;
import dev.ngb.workflow.runtime.application.runtime.port.TimerRepository;
import dev.ngb.workflow.runtime.application.runtime.port.TokenRepository;
import dev.ngb.workflow.runtime.application.runtime.port.WaitSubscriptionRepository;
import dev.ngb.workflow.runtime.application.runtime.port.WakeUpPublisher;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.instance.InstanceStatus;
import dev.ngb.workflow.runtime.domain.step.ExecutionContext;
import dev.ngb.workflow.runtime.domain.step.IdempotencyKeys;
import dev.ngb.workflow.runtime.domain.step.NodeExecutor;
import dev.ngb.workflow.runtime.domain.step.StepOutcome;
import dev.ngb.workflow.runtime.domain.step.StepRun;
import dev.ngb.workflow.runtime.domain.timer.Timer;
import dev.ngb.workflow.runtime.domain.token.Token;
import dev.ngb.workflow.runtime.domain.token.TokenStatus;
import dev.ngb.workflow.runtime.domain.wait.WaitSubscription;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The one loop a worker runs: claim a due token, run its node outside the transaction, then record the step and the
 * tokens that follow in one transaction.
 *
 * <p>The claim commits before the node runs, and the completion is a conditional update on the token's status and the
 * lease owner. A worker that dies after the call leaves a RUNNING token; the sweeper makes it READY after the lease runs
 * out, and the retry writes one step row because the token's attempt is part of its key. A node that cannot finish now
 * parks its token: the same transaction writes the timer or subscription that will resume it, so a crash cannot leave a
 * wait without a deadline or a deadline without a wait.
 */
public class ClaimAndRun {

    /**
     * What one pass did.
     *
     * @param claimed false when no token was due
     * @param status the instance's status after the step, or null when nothing was claimed
     * @param failure the reason the node failed, or null when it succeeded
     */
    public record Outcome(boolean claimed, UUID tokenId, InstanceStatus status, String failure) {}

    private static final Outcome NOTHING = new Outcome(false, null, null, null);

    private final UnitOfWork unitOfWork;
    private final InstanceRepository instances;
    private final TokenRepository tokens;
    private final StepRunRepository steps;
    private final TimerRepository timers;
    private final WaitSubscriptionRepository subscriptions;
    private final JoinStateRepository joinStates;
    private final VersionReader versions;
    private final NodeExecutor executor;
    private final WakeUpPublisher wakeUp;
    private final IdGenerator ids;
    private final Clock clock;
    private final String workerId;
    private final Duration lease;

    public ClaimAndRun(
            UnitOfWork unitOfWork,
            InstanceRepository instances,
            TokenRepository tokens,
            StepRunRepository steps,
            TimerRepository timers,
            WaitSubscriptionRepository subscriptions,
            JoinStateRepository joinStates,
            VersionReader versions,
            NodeExecutor executor,
            WakeUpPublisher wakeUp,
            IdGenerator ids,
            Clock clock,
            String workerId,
            Duration lease) {
        this.unitOfWork = unitOfWork;
        this.instances = instances;
        this.tokens = tokens;
        this.steps = steps;
        this.timers = timers;
        this.subscriptions = subscriptions;
        this.joinStates = joinStates;
        this.versions = versions;
        this.executor = executor;
        this.wakeUp = wakeUp;
        this.ids = ids;
        this.clock = clock;
        this.workerId = workerId;
        this.lease = lease;
    }

    /** Runs at most one step, and answers whether it did. */
    public Outcome runOnce() {
        Instant claimedAt = clock.now();
        Optional<Token> claimed = unitOfWork.transactional(
                () -> tokens.claimNext(workerId, claimedAt, claimedAt.plus(lease)));
        if (claimed.isEmpty()) {
            return NOTHING;
        }
        Token token = claimed.get();
        Instance instance = unitOfWork.transactional(() -> instances.findById(token.instanceId()))
                .orElseThrow(() -> new IllegalStateException(
                        "a token names an instance that is not readable"));
        PublishedGraph version = unitOfWork
                .transactional(() -> versions.graph(instance.versionId()))
                .orElseThrow(() -> new IllegalStateException(
                        "the version an instance pins is not readable"));

        GraphNode node = version.graph().node(token.nodeId()).orElse(null);
        StepOutcome outcome = node == null
                ? StepOutcome.failed("node_not_found:" + token.nodeId(), null, instance.variables())
                : executor.execute(
                        new ExecutionContext(token, instance, version.graph(), node, instance.variables(), clock.now()));

        Instant finishedAt = clock.now();
        InstanceStatus status = unitOfWork.transactional(() -> record(token, instance, outcome, finishedAt));
        if (!status.terminal()) {
            // A following token may already be due; the wake-up saves a sweep interval.
            wakeUp.wake(token.tenantId());
        }
        return new Outcome(true, token.id(), status, outcome.failure());
    }

    /** Runs up to {@code limit} steps, stopping as soon as none is due. */
    public int drain(int limit) {
        int ran = 0;
        while (ran < limit && runOnce().claimed()) {
            ran++;
        }
        return ran;
    }

    private InstanceStatus record(Token token, Instance instance, StepOutcome outcome, Instant finishedAt) {
        boolean joined = false;
        if (outcome.joining()) {
            JoinStateRepository.Arrival arrival = joinStates.arrive(
                    token.tenantId(),
                    token.instanceId(),
                    token.nodeId(),
                    outcome.join().expected(),
                    outcome.join().branchKey(),
                    outcome.join().contribution(),
                    finishedAt);
            if (arrival.complete()) {
                if (outcome.join().cancelOthers()) {
                    // The join has decided: the branches still moving must not act after the flow has left the fan-out.
                    tokens.cancelLive(token.instanceId(), token.id(), finishedAt);
                }
                // The last arrival continues with every branch's output merged by branch name.
                outcome = StepOutcome.succeeded(arrival.outputs(), outcome.join().nextNodeId(), outcome.variables());
                joined = true;
            } else {
                boolean completed = tokens.finish(token.id(), workerId, TokenStatus.DONE, finishedAt);
                if (!completed) {
                    return instance.status();
                }
                recordStep(token, outcome, finishedAt);
                long parkedVersion = updateVariables(instance, outcome);
                if (tokens.liveCount(token.instanceId()) > 0) {
                    return InstanceStatus.RUNNING;
                }
                // No branch can arrive any more, so this join will never reach its count.
                instances.finish(instance.id(), InstanceStatus.FAILED, finishedAt, parkedVersion);
                return InstanceStatus.FAILED;
            }
        }
        if (outcome.parked()) {
            return park(token, instance, outcome, finishedAt);
        }
        boolean completed = tokens.finish(
                token.id(), workerId, outcome.failed() ? TokenStatus.FAILED : TokenStatus.DONE, finishedAt);
        if (!completed) {
            // The lease ran out and another worker owns the token; it writes the only completion.
            return instance.status();
        }
        recordStep(token, outcome, finishedAt);

        if (!outcome.failed()) {
            // A branch keeps its name for every step until the join collects it; the flow after a join carries none.
            String inheritedBranch = joined ? null : token.branchKey();
            if (outcome.successors().isEmpty()) {
                for (String next : outcome.nextNodeIds()) {
                    tokens.add(Token.ready(
                            ids.newId(), token.tenantId(), token.instanceId(), next, token.id(), inheritedBranch,
                            token.iteration(), finishedAt));
                }
            } else {
                for (StepOutcome.Successor successor : outcome.successors()) {
                    tokens.add(Token.ready(
                            ids.newId(), token.tenantId(), token.instanceId(), successor.nodeId(), token.id(),
                            successor.branchKey(), token.iteration(), finishedAt));
                }
            }
        }

        InstanceStatus next = outcome.failed()
                ? InstanceStatus.FAILED
                : tokens.liveCount(token.instanceId()) == 0 ? InstanceStatus.COMPLETED : InstanceStatus.RUNNING;

        long version = updateVariables(instance, outcome);
        if (next.terminal()) {
            instances.finish(instance.id(), next, finishedAt, version);
        }
        return next;
    }

    /** Moves the token to WAITING and writes the deadline or subscription that will resume it. */
    private InstanceStatus park(Token token, Instance instance, StepOutcome outcome, Instant finishedAt) {
        if (!tokens.park(token.id(), workerId, finishedAt)) {
            return instance.status();
        }
        recordStep(token, outcome, finishedAt);

        StepOutcome.Reservation reservation = outcome.reservation();
        if (reservation.kind() != null) {
            timers.add(Timer.due(
                    ids.newId(),
                    token.tenantId(),
                    token.instanceId(),
                    token.id(),
                    reservation.kind(),
                    reservation.dueAt(),
                    finishedAt));
        }
        if (reservation.waits()) {
            subscriptions.add(WaitSubscription.opening(
                    ids.newId(),
                    token.tenantId(),
                    token.instanceId(),
                    token.id(),
                    reservation.waitSource(),
                    reservation.waitEventType(),
                    finishedAt,
                    reservation.waitExpiresAt()));
        }

        long version = updateVariables(instance, outcome);
        if (tokens.activeCount(token.instanceId()) == 0) {
            instances.setStatus(instance.id(), InstanceStatus.WAITING, version);
            return InstanceStatus.WAITING;
        }
        return InstanceStatus.RUNNING;
    }

    private void recordStep(Token token, StepOutcome outcome, Instant finishedAt) {
        steps.add(new StepRun(
                ids.newId(),
                token.tenantId(),
                token.instanceId(),
                token.id(),
                token.nodeId(),
                token.attempt(),
                token.updatedAt(),
                finishedAt,
                outcome.failed() ? "FAILED" : "SUCCEEDED",
                null,
                outcome.response(),
                IdempotencyKeys.of(token.instanceId(), token.id(), token.nodeId(), token.iteration())));
    }

    private long updateVariables(Instance instance, StepOutcome outcome) {
        long version = instance.version();
        if (!outcome.variables().equals(instance.variables())) {
            version = instances.updateVariables(instance.id(), outcome.variables(), version);
        }
        return version;
    }
}
