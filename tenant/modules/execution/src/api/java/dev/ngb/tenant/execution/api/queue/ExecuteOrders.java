package dev.ngb.tenant.execution.api.queue;

/**
 * The contract the wiring uses to drain the execution queue: one bounded batch of claimed orders, each run and
 * settled in its own unit of work.
 */
public interface ExecuteOrders {

    /** What one batch did. */
    record Report(int claimed, int completed, int failed, int retried) {}

    Report run(int batchSize);
}
