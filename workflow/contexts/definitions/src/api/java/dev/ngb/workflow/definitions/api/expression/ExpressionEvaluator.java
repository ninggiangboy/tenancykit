package dev.ngb.workflow.definitions.api.expression;

import java.util.Map;

/**
 * The expression language as another context may use it: one document, the values it may read, and the JSON value it
 * produces.
 *
 * <p>The evaluator has no clock, no network and no host access, and it never throws on data; a document it cannot parse
 * answers null. That is what lets the runtime evaluate a branch condition without a failure path of its own.
 */
public interface ExpressionEvaluator {

    /**
     * @param expression the JSON AST
     * @param input the trigger or manual payload
     * @param steps the output of finished steps, by node id
     * @param variables the workflow variables set so far
     * @param item the current for-each item, or null outside one
     * @param index the current for-each index, or null outside one
     * @return the JSON value, or null when the expression cannot be read or evaluates to null
     */
    Object evaluate(
            Object expression,
            Map<String, Object> input,
            Map<String, Object> steps,
            Map<String, Object> variables,
            Object item,
            Integer index);

    /**
     * A reference resolved to the document it names, so a node that needs a list or an object gets the data itself
     * rather than the scalar view {@link #evaluate} produces. An expression that is not a plain reference (an operator,
     * an array or a literal) is evaluated normally.
     *
     * <p>The default is {@link #evaluate}, which is right for a caller that only ever passes literal documents.
     */
    default Object document(
            Object expression,
            Map<String, Object> input,
            Map<String, Object> steps,
            Map<String, Object> variables,
            Object item,
            Integer index) {
        return evaluate(expression, input, steps, variables, item, index);
    }
}
