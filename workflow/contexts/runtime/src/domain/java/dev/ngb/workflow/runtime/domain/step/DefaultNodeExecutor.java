package dev.ngb.workflow.runtime.domain.step;

import dev.ngb.workflow.definitions.api.expression.ExpressionEvaluator;
import dev.ngb.workflow.runtime.domain.timer.TimerKind;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The core's node behaviour: the trigger, a {@code set}, the end, a deliberate fail, a delay and an event wait.
 *
 * <p>Every other node type reports itself as unsupported for now, so an instance that reaches one stops with a reason
 * instead of pretending to have done something the engine cannot do yet. The phases that add an http call, a task, a
 * parallel, a for-each or a subflow each extend this class.
 */
public class DefaultNodeExecutor implements NodeExecutor {

    private final ExpressionEvaluator expressions;
    private final BufferedEventLookup events;

    public DefaultNodeExecutor(ExpressionEvaluator expressions, BufferedEventLookup events) {
        this.expressions = expressions;
        this.events = events;
    }

    @Override
    public StepOutcome execute(ExecutionContext context) {
        return switch (context.node().type()) {
            case "trigger" -> StepOutcome.succeeded(null, context.node().next(), context.variables());
            case "set" -> set(context);
            case "end" -> StepOutcome.succeeded("ended", null, context.variables());
            case "fail" -> fail(context);
            case "delay" -> delay(context);
            case "wait" -> wait(context);
            default -> StepOutcome.failed(
                    "node_not_supported:" + context.node().type(), null, context.variables());
        };
    }

    private StepOutcome set(ExecutionContext context) {
        Map<String, Object> variables = new LinkedHashMap<>(context.variables());
        Map<String, Object> values = object(context.node().document("values"));
        values.forEach((name, expression) -> variables.put(
                name,
                expressions.evaluate(
                        expression,
                        input(context.instance().input()),
                        Map.of(),
                        variables,
                        null,
                        null)));
        return StepOutcome.succeeded(variables, context.node().next(), variables);
    }

    private StepOutcome fail(ExecutionContext context) {
        Object reason = context.node().document("reason");
        Object evaluated = reason == null
                ? null
                : expressions.evaluate(reason, input(context.instance().input()), Map.of(), context.variables(), null, null);
        return StepOutcome.failed(
                evaluated instanceof String text ? text : "the workflow failed at this node",
                evaluated,
                context.variables());
    }

    /**
     * Parks the token until the deadline, then lets the same node continue. The deadline is a row, so a restart while the
     * instance is parked resumes it exactly once.
     */
    private StepOutcome delay(ExecutionContext context) {
        if ("delay".equals(context.token().resumeBranch())) {
            return StepOutcome.succeeded(context.token().resumePayload(), context.node().next(), context.variables());
        }
        Instant due = dueAt(context);
        if (due == null) {
            return StepOutcome.failed("delay_without_a_deadline", null, context.variables());
        }
        return StepOutcome.parked(
                Map.of("parked", "delay", "dueAt", due.toString()),
                new StepOutcome.Reservation(TimerKind.DELAY, due, null, null, null),
                context.variables());
    }

    /**
     * Parks the token on an event, with an optional timeout. An event that already arrived inside the early-event window
     * satisfies the wait immediately, so a slow start cannot miss a fast producer.
     */
    private StepOutcome wait(ExecutionContext context) {
        String branch = context.token().resumeBranch();
        if ("event".equals(branch)) {
            return StepOutcome.succeeded(context.token().resumePayload(), context.node().next(), context.variables());
        }
        if ("timeout".equals(branch)) {
            Map<String, Object> response = Map.of("timedOut", true);
            String onTimeout = context.node().text("on_timeout");
            return onTimeout == null
                    ? StepOutcome.failed("wait_timed_out", response, context.variables())
                    : StepOutcome.succeeded(response, onTimeout, context.variables());
        }

        String eventType = context.node().text("event");
        if (eventType == null) {
            return StepOutcome.failed("wait_without_an_event", null, context.variables());
        }
        String source = context.node().text("source") == null ? "any" : context.node().text("source");
        Duration timeout = duration(context, context.node().document("timeout"));
        Instant expiresAt = timeout == null
                ? context.now().plus(Duration.ofDays(7))
                : context.now().plus(timeout);

        Optional<BufferedEvent> buffered = events.earliest(source, eventType, context.instance().startedAt());
        if (buffered.isPresent()) {
            return StepOutcome.succeeded(buffered.get().payload(), context.node().next(), context.variables());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("parked", "wait");
        response.put("event", eventType);
        response.put("source", source);
        response.put("expiresAt", expiresAt.toString());
        return StepOutcome.parked(
                response,
                new StepOutcome.Reservation(
                        timeout == null ? null : TimerKind.WAIT_TIMEOUT,
                        timeout == null ? null : context.now().plus(timeout),
                        source,
                        eventType,
                        expiresAt),
                context.variables());
    }

    private Instant dueAt(ExecutionContext context) {
        String until = context.node().text("until");
        if (until != null) {
            try {
                return Instant.parse(until);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        Duration duration = duration(context, context.node().document("duration"));
        return duration == null ? null : context.now().plus(duration);
    }

    private Duration duration(ExecutionContext context, Object document) {
        Object value = document;
        if (value instanceof Map<?, ?>) {
            value = expressions.evaluate(
                    value,
                    input(context.instance().input()),
                    Map.of(),
                    context.variables(),
                    null,
                    null);
        }
        if (value instanceof Number seconds) {
            return Duration.ofSeconds(seconds.longValue());
        }
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return Duration.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Map<String, Object> input(Object value) {
        return value instanceof Map<?, ?> ? object(value) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> members ? (Map<String, Object>) members : Map.of();
    }
}
