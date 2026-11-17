package dev.ngb.workflow.runtime.application.runtime.service;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.workflow.definitions.api.version.GraphNode;
import dev.ngb.workflow.definitions.api.version.GraphView;
import dev.ngb.workflow.definitions.api.version.PublishedGraph;
import dev.ngb.workflow.definitions.api.version.VersionReader;
import dev.ngb.workflow.runtime.application.runtime.port.InstanceRepository;
import dev.ngb.workflow.runtime.application.runtime.port.JoinStateRepository;
import dev.ngb.workflow.runtime.application.runtime.port.LoopStateRepository;
import dev.ngb.workflow.runtime.application.runtime.port.StepRunRepository;
import dev.ngb.workflow.runtime.application.runtime.port.TaskGateway;
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
import java.util.List;
import java.util.Map;
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
 *
 * <p>The engine owns what a node cannot see: a join's count, a for-each's progress and a sub-workflow's child instance.
 * The node reports what it decided; this class writes it.
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

    /** How many sub-workflow steps may separate a child from a top-level instance. */
    private static final int MAX_SUBFLOW_DEPTH = 5;

    /** How many steps one instance may take before it stops with a reason instead of running forever. */
    private static final int MAX_STEPS = 1_000;

    private final UnitOfWork unitOfWork;
    private final InstanceRepository instances;
    private final TokenRepository tokens;
    private final StepRunRepository steps;
    private final TimerRepository timers;
    private final WaitSubscriptionRepository subscriptions;
    private final JoinStateRepository joinStates;
    private final LoopStateRepository loops;
    private final TaskGateway tasks;
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
            LoopStateRepository loops,
            TaskGateway tasks,
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
        this.loops = loops;
        this.tasks = tasks;
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
        Map<String, Object> outputs = unitOfWork.transactional(() -> steps.outputs(instance.id()));

        GraphNode node = version.graph().node(token.nodeId()).orElse(null);
        long taken = unitOfWork.transactional(() -> steps.count(instance.id()));
        StepOutcome outcome = taken >= MAX_STEPS
                ? StepOutcome.failed(
                        "limit_exceeded:steps",
                        Map.of("reason", "limit_exceeded:steps", "steps", MAX_STEPS),
                        instance.variables())
                : node == null
                        ? StepOutcome.failed("node_not_found:" + token.nodeId(), null, instance.variables())
                        : executor.execute(new ExecutionContext(
                                token, instance, version.graph(), node, instance.variables(), outputs, clock.now()));

        Instant finishedAt = clock.now();
        InstanceStatus status =
                unitOfWork.transactional(() -> record(token, instance, outcome, finishedAt, version.graph()));
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

    private InstanceStatus record(
            Token token, Instance instance, StepOutcome outcome, Instant finishedAt, GraphView graph) {
        if (outcome.iterating()) {
            return iterate(token, instance, outcome, finishedAt, graph);
        }
        if (outcome.subflowing()) {
            return startChild(token, instance, outcome, finishedAt);
        }
        if (outcome.hasTask()) {
            return createTask(token, instance, outcome, finishedAt);
        }
        if (outcome.failed() && token.loopNodeId() != null) {
            InstanceStatus afterItemFailure = itemFailed(token, instance, outcome, finishedAt, graph);
            if (afterItemFailure != null) {
                return afterItemFailure;
            }
        }
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
                updateVariables(instance, outcome);
                if (tokens.liveCount(token.instanceId()) > 0) {
                    return InstanceStatus.RUNNING;
                }
                // No branch can arrive any more, so this join will never reach its count.
                finishInstance(instance, InstanceStatus.FAILED, finishedAt, outcome);
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
            // A for-each token keeps its iteration, item and loop for the whole body.
            String inheritedBranch = joined ? null : token.branchKey();
            if (outcome.successors().isEmpty()) {
                for (String next : outcome.nextNodeIds()) {
                    tokens.add(Token.ready(
                            ids.newId(), token.tenantId(), token.instanceId(), next, token.id(), inheritedBranch,
                            token.iteration(), token.payload(), token.loopNodeId(), finishedAt));
                }
            } else {
                for (StepOutcome.Successor successor : outcome.successors()) {
                    tokens.add(Token.ready(
                            ids.newId(), token.tenantId(), token.instanceId(), successor.nodeId(), token.id(),
                            successor.branchKey(), token.iteration(), null, null, finishedAt));
                }
            }
        }

        InstanceStatus next = outcome.failed()
                ? InstanceStatus.FAILED
                : tokens.liveCount(token.instanceId()) == 0 ? InstanceStatus.COMPLETED : InstanceStatus.RUNNING;

        updateVariables(instance, outcome);
        if (next.terminal()) {
            finishInstance(instance, next, finishedAt, outcome);
        }
        return next;
    }

    /**
     * A for-each node's two arrivals. The first evaluates the list and starts the iterations the concurrency allows; a
     * body token coming back records its output and starts the replacement, or leaves the loop after the last one.
     */
    private InstanceStatus iterate(
            Token token, Instance instance, StepOutcome outcome, Instant finishedAt, GraphView graph) {
        StepOutcome.Foreach foreach = outcome.foreach();
        boolean completed = tokens.finish(token.id(), workerId, TokenStatus.DONE, finishedAt);
        if (!completed) {
            return instance.status();
        }
        if (!foreach.bodyReturn()) {
            LoopStateRepository.Start start = loops.start(
                    token.tenantId(),
                    token.instanceId(),
                    token.nodeId(),
                    foreach.items(),
                    foreach.concurrency(),
                    finishedAt);
            recordStep(token, outcome, finishedAt);
            if (start.total() == 0) {
                tokens.add(Token.ready(
                        ids.newId(), token.tenantId(), token.instanceId(), foreach.next(), token.id(),
                        token.branchKey(), 0, null, null, finishedAt));
            } else {
                startIterations(token, token.nodeId(), foreach.each(), start.toStart(), finishedAt);
            }
            updateVariables(instance, outcome);
            return tokens.liveCount(token.instanceId()) == 0
                    ? InstanceStatus.COMPLETED
                    : InstanceStatus.RUNNING;
        }

        Object contribution = outputOf(outcome);
        LoopStateRepository.Advance advance = loops.advance(
                token.tenantId(),
                token.instanceId(),
                token.loopNodeId(),
                token.iteration(),
                contribution,
                null,
                true,
                finishedAt);
        StepOutcome recorded = advance.complete()
                ? StepOutcome.succeeded(
                        Map.of("outputs", advance.outputs(), "errors", advance.errors()), null, outcome.variables())
                : outcome;
        recordStep(token, recorded, finishedAt);
        String bodyNode = graph.node(token.loopNodeId()).map(node -> node.text("each")).orElse(null);
        startIterations(token, token.loopNodeId(), bodyNode, advance.toStart(), finishedAt);
        if (advance.complete()) {
            String next = graph.node(token.loopNodeId()).map(GraphNode::next).orElse(null);
            if (next == null) {
                finishInstance(instance, InstanceStatus.FAILED, finishedAt, outcome);
                return InstanceStatus.FAILED;
            }
            tokens.add(Token.ready(
                    ids.newId(), token.tenantId(), token.instanceId(), next, token.id(), token.branchKey(), 0,
                    null, null, finishedAt));
        }
        updateVariables(instance, outcome);
        return tokens.liveCount(token.instanceId()) == 0
                ? InstanceStatus.COMPLETED
                : InstanceStatus.RUNNING;
    }

    /**
     * A node inside a for-each body failed. When the loop collects errors it records this one and moves on; otherwise
     * the instance fails as any other step would, and the branches still moving are stopped.
     *
     * @return the instance status when the loop absorbs the failure, or null when the failure ends the instance
     */
    private InstanceStatus itemFailed(
            Token token, Instance instance, StepOutcome outcome, Instant finishedAt, GraphView graph) {
        GraphNode loop = graph.node(token.loopNodeId()).orElse(null);
        if (loop == null || !"continue".equals(loop.text("on_item_error"))) {
            return null;
        }
        boolean finished = tokens.finish(token.id(), workerId, TokenStatus.FAILED, finishedAt);
        if (!finished) {
            return instance.status();
        }
        recordStep(token, outcome, finishedAt);
        LoopStateRepository.Advance advance = loops.advance(
                token.tenantId(),
                token.instanceId(),
                token.loopNodeId(),
                token.iteration(),
                null,
                Map.of(
                        "failure", outcome.failure() == null ? "" : outcome.failure(),
                        "response", outcome.response() == null ? Map.of() : outcome.response()),
                true,
                finishedAt);
        startIterations(token, token.loopNodeId(), loop.text("each"), advance.toStart(), finishedAt);
        if (advance.complete()) {
            String next = loop.next();
            if (next == null) {
                finishInstance(instance, InstanceStatus.FAILED, finishedAt, outcome);
                return InstanceStatus.FAILED;
            }
            tokens.add(Token.ready(
                    ids.newId(), token.tenantId(), token.instanceId(), next, token.id(), token.branchKey(), 0, null,
                    null, finishedAt));
        }
        updateVariables(instance, outcome);
        return tokens.liveCount(token.instanceId()) == 0
                ? InstanceStatus.COMPLETED
                : InstanceStatus.RUNNING;
    }

    /**
     * Starts the child instance of a sub-workflow and parks this token on it. The child's own transaction resumes the
     * token when it ends, so a slow child costs a wait rather than a poll, and a parent that dies in between is safe:
     * its token is WAITING and the child's finish is the one event that makes it READY.
     */
    private InstanceStatus startChild(
            Token token, Instance instance, StepOutcome outcome, Instant finishedAt) {
        StepOutcome.Subflow subflow = outcome.subflow();
        int depth = instance.depth() + 1;
        if (depth > MAX_SUBFLOW_DEPTH) {
            return failChildStart(token, instance, outcome, finishedAt,
                    "limit_exceeded:sub_workflow_depth",
                    Map.of("reason", "limit_exceeded:sub_workflow_depth", "depth", depth));
        }
        PublishedGraph childVersion = versions
                .graphOf(subflow.definition(), subflow.version())
                .orElse(null);
        if (childVersion == null) {
            return failChildStart(token, instance, outcome, finishedAt,
                    "subflow_definition_missing:" + subflow.definition(),
                    Map.of("reason", "subflow_definition_missing", "definition", subflow.definition()));
        }
        UUID childId = ids.newId();
        instances.add(Instance.start(
                childId,
                token.tenantId(),
                childVersion.definitionId(),
                childVersion.versionId(),
                childVersion.version(),
                subflow.input(),
                null,
                "subflow:" + instance.id(),
                null,
                null,
                instance.id(),
                token.id(),
                depth,
                finishedAt));
        tokens.add(Token.ready(
                ids.newId(), token.tenantId(), childId, childVersion.graph().start(), null, 0, finishedAt));
        return park(
                token,
                instance,
                StepOutcome.parked(
                        Map.of(
                                "instanceId", childId.toString(),
                                "definition", subflow.definition(),
                                "version", childVersion.version()),
                        new StepOutcome.Reservation(null, null, null, null, null),
                        outcome.variables()),
                finishedAt);
    }

    private InstanceStatus failChildStart(
            Token token, Instance instance, StepOutcome outcome, Instant finishedAt, String reason, Object response) {
        boolean completed = tokens.finish(token.id(), workerId, TokenStatus.FAILED, finishedAt);
        if (!completed) {
            return instance.status();
        }
        recordStep(token, StepOutcome.failed(reason, response, outcome.variables()), finishedAt);
        finishInstance(instance, InstanceStatus.FAILED, finishedAt, outcome);
        return InstanceStatus.FAILED;
    }

    /**
     * Creates the human task the node asked for and parks this token on it. A group that no longer exists fails the
     * step with a readable reason instead of creating a task nobody can see.
     */
    private InstanceStatus createTask(
            Token token, Instance instance, StepOutcome outcome, Instant finishedAt) {
        StepOutcome.Task task = outcome.task();
        TaskGateway.Result created = tasks.create(new TaskGateway.Draft(
                instance.id(),
                token.id(),
                token.nodeId(),
                task.title(),
                task.form(),
                task.assigneeUser(),
                task.assigneeGroup(),
                task.dueAt(),
                task.escalationGroup(),
                task.escalateAt(),
                task.expiresAt(),
                task.outcomes()));
        if (created.failed()) {
            boolean finished = tokens.finish(token.id(), workerId, TokenStatus.FAILED, finishedAt);
            if (!finished) {
                return instance.status();
            }
            recordStep(
                    token,
                    StepOutcome.failed(created.failure(), Map.of("reason", created.failure()), outcome.variables()),
                    finishedAt);
            finishInstance(instance, InstanceStatus.FAILED, finishedAt, outcome);
            return InstanceStatus.FAILED;
        }
        return park(
                token,
                instance,
                StepOutcome.parked(
                        Map.of("taskId", created.taskId().toString()),
                        new StepOutcome.Reservation(null, null, null, null, null),
                        outcome.variables()),
                finishedAt);
    }

    /** Ends an instance and, when it is a sub-workflow's child, resumes the token that started it. */
    private void finishInstance(Instance instance, InstanceStatus status, Instant finishedAt, StepOutcome outcome) {
        instances.finish(instance.id(), status, finishedAt);
        if (instance.parentTokenId() == null) {
            return;
        }
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("instanceId", instance.id().toString());
        payload.put("status", status.name());
        payload.put("output", outcome.response());
        payload.put("variables", outcome.variables());
        tokens.resume(instance.parentTokenId(), "subflow", payload, finishedAt);
    }

    private void startIterations(
            Token token,
            String loopNodeId,
            String bodyNode,
            List<LoopStateRepository.Iteration> iterations,
            Instant now) {
        if (bodyNode == null) {
            throw new IllegalStateException("a for-each has no body node to start");
        }
        for (LoopStateRepository.Iteration iteration : iterations) {
            tokens.add(Token.ready(
                    ids.newId(),
                    token.tenantId(),
                    token.instanceId(),
                    bodyNode,
                    token.id(),
                    token.branchKey(),
                    iteration.index(),
                    iteration.item(),
                    loopNodeId,
                    now));
        }
    }

    private static Object outputOf(StepOutcome outcome) {
        return outcome.response() instanceof Map<?, ?> response ? response.get("output") : null;
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

    /**
     * Writes the variables a step produced. Two parallel branches may write at the same instant; the loser retries
     * against the row as it stands and writes last, which is the documented last-writer-wins rule for shared variables.
     */
    private long updateVariables(Instance instance, StepOutcome outcome) {
        long version = instance.version();
        if (outcome.variables().equals(instance.variables())) {
            return version;
        }
        Instance current = instance;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (instances.updateVariablesIfVersion(current.id(), outcome.variables(), current.version())) {
                return current.version() + 1;
            }
            current = instances.findById(instance.id()).orElse(current);
        }
        return current.version();
    }
}
