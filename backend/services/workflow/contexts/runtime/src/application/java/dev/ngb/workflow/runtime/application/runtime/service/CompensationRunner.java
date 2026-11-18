package dev.ngb.workflow.runtime.application.runtime.service;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.workflow.definitions.api.version.GraphNode;
import dev.ngb.workflow.definitions.api.version.GraphView;
import dev.ngb.workflow.runtime.application.runtime.port.CompensationRunRepository;
import dev.ngb.workflow.runtime.application.runtime.port.InstanceRepository;
import dev.ngb.workflow.runtime.application.runtime.port.StepRunRepository;
import dev.ngb.workflow.runtime.domain.compensation.CompensationRun;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.step.ErrorClass;
import dev.ngb.workflow.runtime.domain.step.ErrorClassifier;
import dev.ngb.workflow.runtime.domain.step.ExecutionContext;
import dev.ngb.workflow.runtime.domain.step.NodeExecutor;
import dev.ngb.workflow.runtime.domain.step.RetryPolicy;
import dev.ngb.workflow.runtime.domain.step.StepOutcome;
import dev.ngb.workflow.runtime.domain.token.Token;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Rolls an instance back: the compensations of the steps it completed, from the last one backwards, each in its own unit
 * of work so no HTTP call is made inside a transaction.
 *
 * <p>A compensation runs through the same node executor as any step, on a synthetic token that lives for the whole
 * compensation: a retried compensation therefore re-sends the same idempotency key. It retries by its own policy, waiting
 * the policy's backoff because the rollback is synchronous.
 */
public class CompensationRunner {

    /** The branch a compensation token carries, so its step rows are recognisable as compensations. */
    public static final String BRANCH = "compensation";

    /** One completed step and the http node that undoes it. */
    private record Rollback(String stepNodeId, GraphNode node) {}

    private final UnitOfWork unitOfWork;
    private final InstanceRepository instances;
    private final StepRunRepository steps;
    private final CompensationRunRepository compensationRuns;
    private final NodeExecutor executor;
    private final IdGenerator ids;
    private final Clock clock;

    public CompensationRunner(
            UnitOfWork unitOfWork,
            InstanceRepository instances,
            StepRunRepository steps,
            CompensationRunRepository compensationRuns,
            NodeExecutor executor,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.instances = instances;
        this.steps = steps;
        this.compensationRuns = compensationRuns;
        this.executor = executor;
        this.ids = ids;
        this.clock = clock;
    }

    /** Whether this node's failure rolls the instance back instead of leaving it dead-lettered. */
    public boolean due(GraphNode node) {
        return node != null && "fail".equals(node.type()) && Boolean.TRUE.equals(node.document("compensate"));
    }

    /**
     * Rolls the instance back and answers the step whose compensation failed, or empty when every compensation
     * succeeded. The instance's own status is the caller's decision, because finishing a sub-workflow's child also
     * resumes the token that waits for it.
     */
    public Optional<String> compensate(Instance instance, GraphView graph) {
        for (Rollback rollback : rollbacksInReverseOrder(instance, graph)) {
            if (!compensateOne(instance, graph, rollback)) {
                return Optional.of(rollback.stepNodeId());
            }
        }
        return Optional.empty();
    }

    private List<Rollback> rollbacksInReverseOrder(Instance instance, GraphView graph) {
        List<String> completed = unitOfWork.transactional(() -> steps.completedNodeIds(instance.id()));
        List<Rollback> rollbacks = new ArrayList<>();
        for (String nodeId : completed) {
            GraphNode node = graph.node(nodeId).orElse(null);
            String compensationId = node == null ? null : node.text("compensate");
            GraphNode compensation = compensationId == null ? null : graph.node(compensationId).orElse(null);
            if (compensation != null && "http".equals(compensation.type())) {
                rollbacks.add(new Rollback(nodeId, compensation));
            }
        }
        Collections.reverse(rollbacks);
        return rollbacks;
    }

    private boolean compensateOne(Instance instance, GraphView graph, Rollback rollback) {
        UUID stepRunId = unitOfWork
                .transactional(() -> steps.latestStepId(instance.id(), rollback.stepNodeId()))
                .orElseThrow(() -> new IllegalStateException(
                        "a completed step of instance " + instance.id() + " has no row to name"));
        UUID tokenId = ids.newId();
        CompensationRun run = CompensationRun.started(
                ids.newId(), instance.tenantId(), instance.id(), stepRunId, rollback.node().id(), clock.now());
        unitOfWork.transactional(() -> {
            compensationRuns.add(run);
            return Boolean.TRUE;
        });

        RetryPolicy retry = RetryPolicy.of(rollback.node());
        int attempt = 1;
        CompensationRun current = run;
        while (true) {
            StepOutcome outcome = execute(instance, graph, rollback.node(), tokenId);
            ErrorClass errorClass = ErrorClassifier.classify(outcome);
            Instant finishedAt = clock.now();
            if (!outcome.failed()) {
                CompensationRun done = current.finished(CompensationRun.SUCCEEDED, null, finishedAt);
                unitOfWork.transactional(() -> {
                    compensationRuns.update(done);
                    return Boolean.TRUE;
                });
                return true;
            }
            if (errorClass != ErrorClass.RETRYABLE || !retry.hasAttemptsLeft(attempt)) {
                CompensationRun failed = current.finished(CompensationRun.FAILED, outcome.failure(), finishedAt);
                unitOfWork.transactional(() -> {
                    compensationRuns.update(failed);
                    return Boolean.TRUE;
                });
                return false;
            }
            await(retry.backoff(attempt));
            attempt++;
            current = current.attempted();
            CompensationRun retried = current;
            unitOfWork.transactional(() -> {
                compensationRuns.update(retried);
                return Boolean.TRUE;
            });
        }
    }

    private StepOutcome execute(Instance instance, GraphView graph, GraphNode node, UUID tokenId) {
        Instant now = clock.now();
        Instance current = unitOfWork.transactional(() -> instances.findById(instance.id())).orElse(instance);
        Map<String, Object> outputs = unitOfWork.transactional(() -> steps.outputs(instance.id()));
        Token token = Token.ready(tokenId, instance.tenantId(), instance.id(), node.id(), null, BRANCH, 0, now);
        return executor.execute(
                new ExecutionContext(token, current, graph, node, current.variables(), outputs, now));
    }

    private static void await(Duration wait) {
        if (wait.isZero()) {
            return;
        }
        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("a compensation was interrupted while it backed off", interrupted);
        }
    }
}
