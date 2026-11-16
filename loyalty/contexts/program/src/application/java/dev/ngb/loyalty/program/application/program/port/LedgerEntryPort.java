package dev.ngb.loyalty.program.application.program.port;

/**
 * Whether the Tenant has any ledger entry yet. A program's currency decides what a point is worth, so it may be chosen
 * freely until the first points are granted or spent and is frozen from then on.
 *
 * <p>The points ledger owns the answer; this context only asks the question.
 */
public interface LedgerEntryPort {

    boolean hasEntries();
}
