package dev.ngb.customer.customers.application.customer.query;

import dev.ngb.customer.customers.application.customer.exception.ExportInProgressException;

/**
 * How one export per tenant is held: a short lease, so a second request while one is streaming is refused instead of
 * reading the same rows a second time, and a lease that ran out because a process died lets the next one in.
 *
 * <p>The lease is its own committed fact, taken before the stream starts and released after it ends, so two callers
 * never block on each other's long read.
 */
public interface ExportLease {

    /**
     * Claims this tenant's export slot.
     *
     * @throws ExportInProgressException when another export of this tenant is still holding it
     */
    Hold acquire();

    /** The claim, released when the export ends or the process stops being able to hold it. */
    interface Hold extends AutoCloseable {

        @Override
        void close();
    }
}
