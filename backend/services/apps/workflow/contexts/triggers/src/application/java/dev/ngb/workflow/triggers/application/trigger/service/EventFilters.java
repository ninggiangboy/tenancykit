package dev.ngb.workflow.triggers.application.trigger.service;

import dev.ngb.workflow.definitions.api.expression.ExpressionEvaluator;
import dev.ngb.workflow.triggers.domain.trigger.Trigger;
import java.util.Map;

/**
 * What a trigger's expressions read: the event envelope as {@code input}.
 *
 * <p>A filter is an expression over the event, a correlation is an expression for the key a wait may match, and the
 * input expression shapes the payload the instance starts with. The evaluator never throws on data, so a filter that
 * cannot be read simply does not match.
 */
public class EventFilters {

    private final ExpressionEvaluator expressions;

    public EventFilters(ExpressionEvaluator expressions) {
        this.expressions = expressions;
    }

    /** Whether this trigger accepts the event: its type and its filter both hold. */
    public boolean matches(Trigger trigger, EventEnvelope event) {
        if (trigger.eventType() != null && !trigger.eventType().equals(event.type())) {
            return false;
        }
        if (trigger.filter() == null) {
            return true;
        }
        return Boolean.TRUE.equals(evaluate(trigger.filter(), event));
    }

    /** The payload the instance starts with: the trigger's expression, or the event's own data. */
    public Object input(Trigger trigger, EventEnvelope event) {
        return trigger.input() == null ? event.data() : evaluate(trigger.input(), event);
    }

    /** The correlation key the trigger names, as text, or null when it names none. */
    public String correlation(Trigger trigger, EventEnvelope event) {
        if (trigger.correlation() == null) {
            return null;
        }
        Object value = evaluate(trigger.correlation(), event);
        return value == null ? null : String.valueOf(value);
    }

    private Object evaluate(Object expression, EventEnvelope event) {
        Map<String, Object> input = Documents.object(event.document());
        return expressions.evaluate(
                expression, input == null ? Map.of() : input, Map.of(), Map.of(), null, null);
    }
}
