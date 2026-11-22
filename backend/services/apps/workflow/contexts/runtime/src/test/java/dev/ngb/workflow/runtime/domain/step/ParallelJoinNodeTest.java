package dev.ngb.workflow.runtime.domain.step;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.definitions.api.expression.ExpressionEvaluator;
import dev.ngb.workflow.definitions.api.version.GraphNode;
import dev.ngb.workflow.definitions.api.version.GraphView;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.token.Token;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Fan-out names every branch by its node id, refuses ambiguity, and a join derives its arrival count from the parallel. */
class ParallelJoinNodeTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void eachBranchBecomesASuccessorCollectedUnderItsNodeId() {
        Map<String, Object> parallel = parallel("after", "charge-node", "notify-node");
        StepOutcome outcome = run(parallel, "parallel", Map.of("parallel", parallel));

        assertThat(outcome.successors())
                .extracting(StepOutcome.Successor::nodeId, StepOutcome.Successor::branchKey)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("charge-node", "charge-node"),
                        org.assertj.core.groups.Tuple.tuple("notify-node", "notify-node"));
    }

    @Test
    void aRepeatedBranchIdIsRefused() {
        Map<String, Object> parallel = parallel("after", "work", "work");
        StepOutcome outcome = run(parallel, "parallel", Map.of("parallel", parallel));

        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.failure()).isEqualTo("parallel_branch_name_repeated:work");
    }

    @Test
    void moreThanFiftyBranchesIsALimitFailure() {
        List<String> branches = new ArrayList<>();
        for (int branch = 0; branch < 51; branch++) {
            branches.add("n" + branch);
        }
        Map<String, Object> parallel = parallel("after", branches.toArray(String[]::new));
        StepOutcome outcome = run(parallel, "parallel", Map.of("parallel", parallel));

        assertThat(outcome.failure()).isEqualTo("limit_exceeded:parallel_branches");
    }

    @Test
    void anAllJoinWaitsForEveryBranchOfItsParallel() {
        Map<String, Object> join = join("after", null, null);
        StepOutcome outcome = run(join, "join",
                Map.of("parallel", parallel("join", "a", "b"), "join", join), "b");

        assertThat(outcome.joining()).isTrue();
        assertThat(outcome.join().expected()).isEqualTo(2);
        assertThat(outcome.join().branchKey()).isEqualTo("b");
        assertThat(outcome.join().nextNodeId()).isEqualTo("after");
        assertThat(outcome.join().cancelOthers()).isFalse();
    }

    @Test
    void anAnyJoinFiresOnTheFirstArrivalAndAbandonsTheRest() {
        Map<String, Object> join = join("after", "any", null);
        StepOutcome outcome = run(join, "join",
                Map.of("parallel", parallel("join", "a", "b"), "join", join), "a");

        assertThat(outcome.join().expected()).isEqualTo(1);
        assertThat(outcome.join().cancelOthers()).isTrue();
    }

    @Test
    void anNOfJoinWaitsForItsCount() {
        Map<String, Object> join = join("after", "n_of", 2);
        StepOutcome outcome = run(join, "join",
                Map.of("parallel", parallel("join", "a", "b", "c"), "join", join), "c");

        assertThat(outcome.join().expected()).isEqualTo(2);
        assertThat(outcome.join().cancelOthers()).isTrue();
    }

    @Test
    void aJoinWithoutItsParallelFails() {
        Map<String, Object> join = join("after", null, null);
        StepOutcome outcome = run(join, "join", Map.of("join", join));

        assertThat(outcome.failure()).isEqualTo("join_without_a_parallel");
    }

    private static Map<String, Object> join(String next, String mode, Integer count) {
        Map<String, Object> settings = new java.util.LinkedHashMap<>();
        settings.put("type", "join");
        settings.put("next", next);
        if (mode != null) {
            settings.put("mode", mode);
        }
        if (count != null) {
            settings.put("count", count);
        }
        return settings;
    }

    private static Map<String, Object> parallel(String join, String... branches) {
        return Map.of("type", "parallel", "branches", List.of(branches), "join", join);
    }

    private static StepOutcome run(Map<String, Object> settings, String nodeId, Map<String, Map<String, Object>> graph) {
        return run(settings, nodeId, graph, null);
    }

    private static StepOutcome run(
            Map<String, Object> settings, String nodeId, Map<String, Map<String, Object>> graph, String branchKey) {
        DefaultNodeExecutor executor = new DefaultNodeExecutor(
                new LiteralExpressions(),
                (source, event, after) -> {
                    throw new AssertionError("no wait here");
                },
                call -> {
                    throw new AssertionError("no call here");
                });
        List<GraphNode> nodes = new ArrayList<>();
        graph.forEach((id, members) -> nodes.add(new GraphNode(id, String.valueOf(members.get("type")), members)));
        GraphNode node = nodes.stream().filter(candidate -> candidate.id().equals(nodeId)).findFirst().orElseThrow();
        GraphView view = new GraphView(nodes.getFirst().id(), nodes);
        Instance instance = Instance.start(
                UUID.randomUUID(), TENANT, UUID.randomUUID(), UUID.randomUUID(), 1, Map.of(), null, "test", null,
                null, NOW);
        Token token = Token.ready(UUID.randomUUID(), TENANT, instance.id(), nodeId, null, branchKey, 0, NOW);
        return executor.execute(new ExecutionContext(token, instance, view, node, Map.of(), NOW));
    }

    /** Leaves literals as they are; these settings hold literals. */
    private static final class LiteralExpressions implements ExpressionEvaluator {
        @Override
        public Object evaluate(
                Object expression,
                Map<String, Object> input,
                Map<String, Object> steps,
                Map<String, Object> variables,
                Object item,
                Integer index) {
            return expression;
        }
    }
}
