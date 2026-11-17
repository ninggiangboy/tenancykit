package dev.ngb.workflow.runtime.domain.step;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.definitions.api.expression.ExpressionEvaluator;
import dev.ngb.workflow.definitions.api.version.GraphNode;
import dev.ngb.workflow.definitions.api.version.GraphView;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.token.Token;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The first true case wins, the else is mandatory, and the step records which way it went. */
class BranchNodeTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void theFirstTrueCaseWinsEvenWhenLaterOnesAreTrueToo() {
        StepOutcome outcome = run(Map.of(
                "cases", List.of(
                        Map.of("when", false, "next", "never"),
                        Map.of("when", true, "next", "first"),
                        Map.of("when", true, "next", "second")),
                "else", "otherwise"));

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.nextNodeIds()).containsExactly("first");
        assertThat(outcome.response()).isEqualTo(Map.of("branch", 1, "next", "first"));
    }

    @Test
    void noTrueCaseTakesTheElse() {
        StepOutcome outcome = run(Map.of(
                "cases", List.of(Map.of("when", false, "next", "never")), "else", "otherwise"));

        assertThat(outcome.nextNodeIds()).containsExactly("otherwise");
        assertThat(outcome.response()).isEqualTo(Map.of("branch", "else", "next", "otherwise"));
    }

    @Test
    void aBranchWithoutElseFailsInsteadOfStalling() {
        StepOutcome outcome = run(Map.of("cases", List.of(Map.of("when", false, "next", "never"))));

        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.failure()).isEqualTo("branch_without_else");
    }

    private static StepOutcome run(Map<String, Object> settings) {
        DefaultNodeExecutor executor = new DefaultNodeExecutor(
                new LiteralExpressions(),
                (source, type, after) -> {
                    throw new AssertionError("a branch waits on nothing");
                },
                call -> {
                    throw new AssertionError("a branch calls nothing");
                });
        GraphNode node = new GraphNode("choose", "branch", settings);
        GraphView graph = new GraphView("choose", List.of(node));
        Instance instance = Instance.start(
                UUID.randomUUID(), TENANT, UUID.randomUUID(), UUID.randomUUID(), 1, Map.of(), null, "test", null,
                null, NOW);
        Token token = Token.ready(UUID.randomUUID(), TENANT, instance.id(), "choose", null, 0, NOW);
        return executor.execute(new ExecutionContext(token, instance, graph, node, Map.of(), NOW));
    }

    /** Leaves literals as they are; these conditions are literals. */
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
