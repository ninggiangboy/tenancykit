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

/** Fan-out names every branch, refuses ambiguity, and a join carries the expected count and its own branch. */
class ParallelJoinNodeTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void eachBranchBecomesASuccessorWithItsName() {
        StepOutcome outcome = run("parallel", Map.of("branches", List.of(
                Map.of("name", "charge", "next", "charge-node"),
                Map.of("name", "notify", "next", "notify-node"))), null);

        assertThat(outcome.successors())
                .extracting(StepOutcome.Successor::nodeId, StepOutcome.Successor::branchKey)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("charge-node", "charge"),
                        org.assertj.core.groups.Tuple.tuple("notify-node", "notify"));
    }

    @Test
    void aRepeatedBranchNameIsRefused() {
        StepOutcome outcome = run("parallel", Map.of("branches", List.of(
                Map.of("name", "work", "next", "a"),
                Map.of("name", "work", "next", "b"))), null);

        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.failure()).isEqualTo("parallel_branch_name_repeated:work");
    }

    @Test
    void moreThanFiftyBranchesIsALimitFailure() {
        List<Map<String, Object>> branches = new ArrayList<>();
        for (int branch = 0; branch < 51; branch++) {
            branches.add(Map.of("name", "b" + branch, "next", "n" + branch));
        }
        StepOutcome outcome = run("parallel", Map.of("branches", branches), null);

        assertThat(outcome.failure()).isEqualTo("limit_exceeded:parallel_branches");
    }

    @Test
    void aJoinCarriesItsExpectedCountAndItsBranch() {
        StepOutcome outcome = run(
                "join", Map.of("expect", 2, "next", "after", "output", Map.of("ok", true)), "notify");

        assertThat(outcome.joining()).isTrue();
        assertThat(outcome.join().expected()).isEqualTo(2);
        assertThat(outcome.join().branchKey()).isEqualTo("notify");
        assertThat(outcome.join().nextNodeId()).isEqualTo("after");
        assertThat(outcome.join().contribution()).isEqualTo(Map.of("ok", true));
    }

    @Test
    void aJoinWithoutAnExpectedCountFails() {
        StepOutcome outcome = run("join", Map.of("next", "after"), null);

        assertThat(outcome.failure()).isEqualTo("join_without_an_expected_count");
    }

    private static StepOutcome run(String type, Map<String, Object> settings, String branchKey) {
        DefaultNodeExecutor executor = new DefaultNodeExecutor(
                new LiteralExpressions(),
                (source, event, after) -> {
                    throw new AssertionError("no wait here");
                },
                call -> {
                    throw new AssertionError("no call here");
                });
        GraphNode node = new GraphNode("node", type, settings);
        GraphView graph = new GraphView("node", List.of(node));
        Instance instance = Instance.start(
                UUID.randomUUID(), TENANT, UUID.randomUUID(), UUID.randomUUID(), 1, Map.of(), null, "test", null,
                null, NOW);
        Token token = Token.ready(UUID.randomUUID(), TENANT, instance.id(), "node", null, branchKey, 0, NOW);
        return executor.execute(new ExecutionContext(token, instance, graph, node, Map.of(), NOW));
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
