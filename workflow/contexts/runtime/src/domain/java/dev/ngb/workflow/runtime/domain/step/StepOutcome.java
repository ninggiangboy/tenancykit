package dev.ngb.workflow.runtime.domain.step;

import dev.ngb.workflow.runtime.domain.timer.TimerKind;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What running one node concluded: did it fail, what does the step history record, which nodes follow and what
 * variables the instance carries afterwards.
 *
 * <p>A failure is a normal outcome, not an exception: the engine records it and stops the instance. The token's next
 * ids are empty when the path ends. A node that cannot finish now parks its token with a {@link Reservation}: a timer,
 * an event subscription, or both.
 */
public record StepOutcome(
        boolean failed,
        String failure,
        Object response,
        List<String> nextNodeIds,
        Map<String, Object> variables,
        Reservation reservation,
        List<Successor> successors,
        Join join) {

    /** One following token: the node it starts at and, for a parallel branch, the name it is collected under. */
    public record Successor(String nodeId, String branchKey) {}

    /**
     * An arrival at a join. The engine counts it in one conditional update; only the arrival that reaches the expected
     * count continues, with every branch's output merged by branch name.
     */
    public record Join(String nextNodeId, int expected, String branchKey, Object contribution) {}

    /**
     * What the engine must write when it parks a token.
     *
     * @param kind the timer to create, or null when only an event can resume the token
     * @param dueAt when the timer fires, or null without one
     * @param waitSource the source the event comes from, or {@code any}, or null when the token waits on no event
     * @param waitEventType the event type accepted, or null when the token waits on no event
     * @param waitExpiresAt how long the subscription stays open
     */
    public record Reservation(
            TimerKind kind, Instant dueAt, String waitSource, String waitEventType, Instant waitExpiresAt) {

        public boolean waits() {
            return waitEventType != null;
        }
    }

    public StepOutcome {
        nextNodeIds = nextNodeIds == null ? List.of() : List.copyOf(nextNodeIds);
        variables = variables == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        successors = successors == null ? List.of() : List.copyOf(successors);
    }

    public static StepOutcome succeeded(Object response, String next, Map<String, Object> variables) {
        return new StepOutcome(
                false, null, response, next == null ? List.of() : List.of(next), variables, null, List.of(), null);
    }

    public static StepOutcome failed(String failure, Object response, Map<String, Object> variables) {
        return new StepOutcome(true, failure, response, List.of(), variables, null, List.of(), null);
    }

    public static StepOutcome parked(
            Object response, Reservation reservation, Map<String, Object> variables) {
        return new StepOutcome(false, null, response, List.of(), variables, reservation, List.of(), null);
    }

    /** Several following tokens, each carrying the branch it belongs to. */
    public static StepOutcome fannedOut(
            Object response, List<Successor> successors, Map<String, Object> variables) {
        return new StepOutcome(false, null, response, List.of(), variables, null, successors, null);
    }

    /** Waiting for the rest of its branches: the engine counts this arrival and decides whether the join fires. */
    public static StepOutcome joining(
            Object response, Join join, Map<String, Object> variables) {
        return new StepOutcome(false, null, response, List.of(), variables, null, List.of(), join);
    }

    public boolean parked() {
        return reservation != null;
    }

    public boolean joining() {
        return join != null;
    }
}
