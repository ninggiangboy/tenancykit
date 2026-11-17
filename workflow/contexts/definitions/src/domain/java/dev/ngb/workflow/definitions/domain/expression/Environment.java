package dev.ngb.workflow.definitions.domain.expression;

/**
 * Where an expression's references come from while it runs.
 *
 * <p>The runtime fills this from the instance's payload, the output of finished steps, the workflow variables and the
 * current iteration. The evaluator has no other access to anything: no clock, no network, no host.
 */
public interface Environment {

    /** A value of the trigger or manual payload at a closed dotted path. */
    Value input(String path);

    /** A finished step's output at a closed dotted path. */
    Value step(String nodeId, String path);

    /** A workflow variable, or null when the name is unknown. */
    Value variable(String name);

    /** The current for-each item at a closed dotted path. */
    Value item(String path);

    /** The current for-each index, or null outside one. */
    Value index();
}
