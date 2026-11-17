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
    private final OutboundHttp http;

    public DefaultNodeExecutor(
            ExpressionEvaluator expressions, BufferedEventLookup events, OutboundHttp http) {
        this.expressions = expressions;
        this.events = events;
        this.http = http;
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
            case "branch" -> branch(context);
            case "http" -> call(context);
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

    /**
     * Takes the first case whose condition is true, or the mandatory else. The decision is the step's output, so the
     * history says which way an instance went and why.
     */
    private StepOutcome branch(ExecutionContext context) {
        Object cases = context.node().document("cases");
        int index = 0;
        if (cases instanceof java.util.List<?> ordered) {
            for (Object entry : ordered) {
                if (!(entry instanceof Map<?, ?> fallback)) {
                    index++;
                    continue;
                }
                Map<String, Object> test = object(fallback);
                Object condition = evaluate(context, test.get("when"), context.variables());
                if (truthy(condition)) {
                    String next = test.get("next") instanceof String id ? id : null;
                    return next == null
                            ? StepOutcome.failed("branch_case_without_a_next", Map.of("branch", index), context.variables())
                            : StepOutcome.succeeded(Map.of("branch", index, "next", next), next, context.variables());
                }
                index++;
            }
        }
        String otherwise = context.node().text("else");
        if (otherwise == null) {
            return StepOutcome.failed("branch_without_else", null, context.variables());
        }
        return StepOutcome.succeeded(Map.of("branch", "else", "next", otherwise), otherwise, context.variables());
    }

    private Object evaluate(ExecutionContext context, Object expression, Map<String, Object> variables) {
        return expressions.evaluate(
                expression, input(context.instance().input()), Map.of(), variables, null, null);
    }

    /** A condition is true only when it plainly says so: JSON true, a non-zero number or a non-empty string. */
    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value)
                || value instanceof Number number && number.doubleValue() != 0
                || value instanceof String text && !text.isBlank() && !"false".equalsIgnoreCase(text);
    }

    /**
     * Calls an app or a connection and records what came back: the whole answer, status, headers and body. A node may
     * reshape it with a {@code response} expression over the same member names.
     */
    private StepOutcome call(ExecutionContext context) {
        String app = context.node().text("app");
        String connection = context.node().text("connection");
        Object path = context.node().document("path");
        Object url = context.node().document("url");
        Object body = context.node().document("body");

        Map<String, String> headers = new LinkedHashMap<>();
        object(context.node().document("headers")).forEach((name, expression) -> {
            Object value = expressions.evaluate(
                    expression, input(context.instance().input()), Map.of(), context.variables(), null, null);
            if (value != null) {
                headers.put(name, String.valueOf(value));
            }
        });

        OutboundHttp.Result result = http.send(new OutboundHttp.Call(
                context.node().text("method") == null ? "GET" : context.node().text("method").toUpperCase(),
                app,
                connection,
                path == null
                        ? null
                        : String.valueOf(expressions.evaluate(
                                path, input(context.instance().input()), Map.of(), context.variables(), null, null)),
                url == null
                        ? null
                        : String.valueOf(expressions.evaluate(
                                url, input(context.instance().input()), Map.of(), context.variables(), null, null)),
                headers,
                body == null
                        ? null
                        : expressions.evaluate(
                                body, input(context.instance().input()), Map.of(), context.variables(), null, null),
                IdempotencyKeys.of(
                        context.instance().id(), context.token().id(), context.node().id(), context.token().iteration()),
                duration(context, context.node().document("timeout"))));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", result.status());
        response.put("headers", result.headers());
        response.put("body", result.body());
        if (result.error() != null) {
            response.put("error", result.error());
        }
        if (!result.arrived() || result.status() >= 400) {
            return StepOutcome.failed(
                    "http_call_failed:" + (result.error() == null ? result.status() : result.error()),
                    response,
                    context.variables());
        }
        Object shaped = context.node().document("response");
        if (shaped != null) {
            Map<String, Object> scope = new LinkedHashMap<>(context.variables());
            scope.put("response", response);
            return StepOutcome.succeeded(
                    expressions.evaluate(shaped, input(context.instance().input()), Map.of(), scope, null, null),
                    context.node().next(),
                    context.variables());
        }
        return StepOutcome.succeeded(response, context.node().next(), context.variables());
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
