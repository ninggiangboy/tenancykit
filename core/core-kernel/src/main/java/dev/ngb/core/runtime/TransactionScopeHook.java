package dev.ngb.core.runtime;

import dev.ngb.core.tenancy.RequestScope;

/**
 * A seam for an application that must run something at the start of every new transaction (for example, telling
 * the database which tenant the transaction belongs to). {@link UnitOfWork} calls it once per outer transaction,
 * after binding {@code scope} and before the work runs.
 */
public interface TransactionScopeHook {

    /** The scope a new transaction will be bound to; the caller compares it with the scope already bound. */
    RequestScope currentScope();

    /** Runs as the first step inside each new transaction. */
    void onNewTransaction(RequestScope scope);

    /**
     * A hook for an application that has no per-transaction work to do. It answers the Platform scope, the one scope
     * that needs no statement of its own, so nested units of work still compare equal.
     */
    static TransactionScopeHook none() {
        return new TransactionScopeHook() {
            @Override
            public RequestScope currentScope() {
                return RequestScope.platform();
            }

            @Override
            public void onNewTransaction(RequestScope scope) {}
        };
    }
}
