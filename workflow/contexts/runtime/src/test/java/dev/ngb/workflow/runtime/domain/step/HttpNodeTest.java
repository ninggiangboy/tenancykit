package dev.ngb.workflow.runtime.domain.step;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.definitions.api.expression.ExpressionEvaluator;
import dev.ngb.workflow.definitions.api.version.GraphNode;
import dev.ngb.workflow.definitions.api.version.GraphView;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.token.Token;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The http node: it builds the call from the node and the variables, sends it through the engine's port, and records
 * what came back — a bad status is a failure with the response attached, not an exception.
 */
class HttpNodeTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void theNodeBuildsTheCallFromItsSettingsAndSendsTheIdempotencyKey() {
        RecordingHttp http = new RecordingHttp(new OutboundHttp.Result(200, Map.of("Content-Type", List.of("application/json")), "[{\"id\":1}]", null));
        StepOutcome outcome = run(http, Map.of(
                "app", "crm",
                "method", "get",
                "path", "/api/v1/customers",
                "headers", Map.of("X-Trace", "abc"),
                "body", Map.of("limit", 10),
                "next", "done"));

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.nextNodeIds()).containsExactly("done");
        assertThat(http.last.method()).isEqualTo("GET");
        assertThat(http.last.app()).isEqualTo("crm");
        assertThat(http.last.path()).isEqualTo("/api/v1/customers");
        assertThat(http.last.headers()).containsEntry("X-Trace", "abc");
        assertThat(http.last.body()).isEqualTo(Map.of("limit", 10));
        assertThat(http.last.idempotencyKey()).hasSize(64);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) outcome.response();
        assertThat(response).containsEntry("status", 200).containsEntry("body", "[{\"id\":1}]");
    }

    @Test
    void aServerErrorIsAFailedStepThatKeepsTheResponse() {
        RecordingHttp http = new RecordingHttp(new OutboundHttp.Result(503, Map.of(), "busy", null));
        StepOutcome outcome = run(http, Map.of("connection", "crm-api", "path", "/customers"));

        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.failure()).isEqualTo("http_call_failed:503");
        assertThat(outcome.response()).isInstanceOf(Map.class);
        assertThat(outcome.nextNodeIds()).isEmpty();
    }

    @Test
    void aRefusedCredentialFailsWithItsReasonAndNoStatus() {
        RecordingHttp http = new RecordingHttp(new OutboundHttp.Result(0, Map.of(), null, "credential_invalid"));
        StepOutcome outcome = run(http, Map.of("app", "mailroom", "path", "/messages"));

        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.failure()).isEqualTo("http_call_failed:credential_invalid");
    }

    private static StepOutcome run(RecordingHttp http, Map<String, Object> settings) {
        DefaultNodeExecutor executor = new DefaultNodeExecutor(new LiteralExpressions(), (source, type, after) -> {
            throw new AssertionError("this node waits on nothing");
        }, http);
        GraphNode node = new GraphNode("call", "http", settings);
        GraphView graph = new GraphView("call", List.of(node));
        Instance instance = Instance.start(
                UUID.randomUUID(), TENANT, UUID.randomUUID(), UUID.randomUUID(), 1, Map.of("kind", "test"),
                null, "test", null, null, NOW);
        Token token = Token.ready(UUID.randomUUID(), TENANT, instance.id(), "call", null, 0, NOW);
        return executor.execute(new ExecutionContext(token, instance, graph, node, Map.of("customer", 7), NOW));
    }

    private static final class RecordingHttp implements OutboundHttp {
        OutboundHttp.Result result;
        OutboundHttp.Call last;

        RecordingHttp(OutboundHttp.Result result) {
            this.result = result;
        }

        @Override
        public OutboundHttp.Result send(OutboundHttp.Call call) {
            last = call;
            return result;
        }
    }

    /** Leaves every value as it is; these settings hold literals, so the node's shaping is what is under test. */
    private static final class LiteralExpressions implements ExpressionEvaluator {
        @Override
        @SuppressWarnings("unchecked")
        public Object evaluate(
                Object expression,
                Map<String, Object> input,
                Map<String, Object> steps,
                Map<String, Object> variables,
                Object item,
                Integer index) {
            return expression instanceof Map<?, ?>
                    ? new LinkedHashMap<>((Map<String, Object>) expression)
                    : expression;
        }
    }
}
