package dev.ngb.workflow.runtime.domain.step;

/**
 * Runs one node. Implementations are pure with respect to the database: the engine claims the token, this decides what
 * the node did, and the engine records the outcome in one transaction afterwards.
 */
public interface NodeExecutor {

    StepOutcome execute(ExecutionContext context);
}
