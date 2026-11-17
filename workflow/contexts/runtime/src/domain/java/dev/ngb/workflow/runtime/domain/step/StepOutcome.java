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
        Join join,
        Foreach foreach,
        Subflow subflow) {

    /** One following token: the node it starts at and, for a parallel branch, the name it is collected under. */
    public record Successor(String nodeId, String branchKey) {}

    /**
     * An arrival at a join. The engine counts it in one conditional update; only the arrival that reaches the expected
     * count continues, with every branch's output merged by branch name.
     *
     * @param cancelOthers true for an {@code any} or {@code n_of} join, whose firing abandons the branches still moving
     */
    public record Join(String nextNodeId, int expected, String branchKey, Object contribution, boolean cancelOthers) {}

    /**
     * A for-each node's decision. On the first arrival it carries the evaluated list and the limits; when a body token
     * comes back to the node it only says so, and the engine advances the loop it already owns.
     *
     * @param bodyReturn true when this is a body token arriving back at the node
     * @param each the node one iteration starts at, null on a return
     * @param next the node after the loop, null on a return
     * @param items the evaluated list, empty on a return
     * @param concurrency how many iterations may walk the body at once
     * @param itemError {@code stop} (the default) or {@code continue}
     * @param output the expression an iteration records as its output, or null
     */
    public record Foreach(
            boolean bodyReturn,
            String each,
            String next,
            List<Object> items,
            int concurrency,
            String itemError,
            Object output) {

        public Foreach {
            items = items == null
                    ? List.of()
                    : Collections.unmodifiableList(new java.util.ArrayList<>(items));
        }
    }

    /**
     * A sub-workflow to start: the definition code, the version to pin ({@code null} resolves the latest at start) and
     * the input the child reads. The engine creates the child and parks this token until the child finishes.
     */
    public record Subflow(String definition, Integer version, Object input) {}

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
                false, null, response, next == null ? List.of() : List.of(next), variables, null, List.of(), null, null,
                null);
    }

    public static StepOutcome failed(String failure, Object response, Map<String, Object> variables) {
        return new StepOutcome(true, failure, response, List.of(), variables, null, List.of(), null, null, null);
    }

    public static StepOutcome parked(
            Object response, Reservation reservation, Map<String, Object> variables) {
        return new StepOutcome(false, null, response, List.of(), variables, reservation, List.of(), null, null, null);
    }

    /** Several following tokens, each carrying the branch it belongs to. */
    public static StepOutcome fannedOut(
            Object response, List<Successor> successors, Map<String, Object> variables) {
        return new StepOutcome(false, null, response, List.of(), variables, null, successors, null, null, null);
    }

    /** Waiting for the rest of its branches: the engine counts this arrival and decides whether the join fires. */
    public static StepOutcome joining(
            Object response, Join join, Map<String, Object> variables) {
        return new StepOutcome(false, null, response, List.of(), variables, null, List.of(), join, null, null);
    }

    /** The engine owns the loop's progress: it starts iterations, counts arrivals and continues after the last one. */
    public static StepOutcome foreaching(
            Object response, Foreach foreach, Map<String, Object> variables) {
        return new StepOutcome(false, null, response, List.of(), variables, null, List.of(), null, foreach, null);
    }

    public boolean parked() {
        return reservation != null;
    }

    public boolean joining() {
        return join != null;
    }

    /** The engine starts the child instance and parks this token until the child ends. */
    public static StepOutcome subflowing(
            Object response, Subflow subflow, Map<String, Object> variables) {
        return new StepOutcome(false, null, response, List.of(), variables, null, List.of(), null, null, subflow);
    }

    public boolean iterating() {
        return foreach != null;
    }

    public boolean subflowing() {
        return subflow != null;
    }
}
