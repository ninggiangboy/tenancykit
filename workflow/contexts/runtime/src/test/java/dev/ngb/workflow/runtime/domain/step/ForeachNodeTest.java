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

/** A for-each evaluates its list, bounds its concurrency, and knows its body token when it comes back. */
class ForeachNodeTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void theFirstArrivalCarriesTheItemsAndTheDefaults() {
        StepOutcome outcome = run(Map.of(
                "items", List.of(Map.of("sku", "a"), Map.of("sku", "b")),
                "each", "body",
                "next", "done"), null, 0);

        assertThat(outcome.iterating()).isTrue();
        assertThat(outcome.foreach().bodyReturn()).isFalse();
        assertThat(outcome.foreach().items()).hasSize(2);
        assertThat(outcome.foreach().concurrency()).isEqualTo(1);
        assertThat(outcome.foreach().itemError()).isEqualTo("stop");
        assertThat(outcome.foreach().each()).isEqualTo("body");
        assertThat(outcome.foreach().next()).isEqualTo("done");
    }

    @Test
    void aConcurrencyIsClampedToFifty() {
        StepOutcome outcome = run(Map.of(
                "items", List.of(1, 2),
                "each", "body",
                "next", "done",
                "concurrency", 500), null, 0);

        assertThat(outcome.foreach().concurrency()).isEqualTo(50);
    }

    @Test
    void moreThanAThousandItemsIsALimitFailure() {
        List<Integer> items = new java.util.ArrayList<>();
        for (int index = 0; index < 1_001; index++) {
            items.add(index);
        }
        StepOutcome outcome = run(Map.of("items", items, "each", "body", "next", "done"), null, 0);

        assertThat(outcome.failure()).isEqualTo("limit_exceeded:foreach_items");
    }

    @Test
    void aBodyTokenComingBackSaysSoAndCarriesItsIteration() {
        StepOutcome outcome = run(
                Map.of("items", List.of(1), "each", "body", "next", "done"),
                "loop",
                7);

        assertThat(outcome.foreach().bodyReturn()).isTrue();
        assertThat(outcome.response()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) outcome.response()).get("iteration")).isEqualTo(7);
        assertThat(((Map<?, ?>) outcome.response()).get("output")).isNull();
    }

    @Test
    void aLoopCanCollectErrors() {
        StepOutcome outcome = run(
                Map.of("items", List.of(1), "each", "body", "next", "done", "on_item_error", "continue"),
                null,
                0);

        assertThat(outcome.foreach().itemError()).isEqualTo("continue");
    }

    private static StepOutcome run(Map<String, Object> settings, String loopNodeId, int iteration) {
        DefaultNodeExecutor executor = new DefaultNodeExecutor(
                new LiteralExpressions(),
                (source, event, after) -> {
                    throw new AssertionError("no wait here");
                },
                call -> {
                    throw new AssertionError("no call here");
                });
        GraphNode node = new GraphNode("loop", "foreach", settings);
        GraphView graph = new GraphView("loop", List.of(node));
        Instance instance = Instance.start(
                UUID.randomUUID(), TENANT, UUID.randomUUID(), UUID.randomUUID(), 1, Map.of(), null, "test", null,
                null, NOW);
        Token token = Token.ready(
                UUID.randomUUID(), TENANT, instance.id(), "loop", null, null, iteration, null, loopNodeId, NOW);
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
            if (expression instanceof Map<?, ?> map && map.size() == 1 && map.containsKey("input")) {
                return input.get(String.valueOf(map.get("input")));
            }
            if (expression instanceof Map<?, ?> map && map.size() == 1 && map.containsKey("item")) {
                return item instanceof Map<?, ?> values ? values.get(String.valueOf(map.get("item"))) : null;
            }
            return expression;
        }
    }
}
