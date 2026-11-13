package dev.ngb.billing.payment.application.messaging.service;

/**
 * The names payment acts under when no person is behind the change. A name is stored in the ledger entries and the
 * audit log, so it must not change once released; ledger entries written before this name was introduced hold the
 * bare {@code stripe}, and history is not rewritten.
 */
public final class PaymentActors {

    /** The actor that records what Stripe collected: a top-up credit and the debit of a refund. */
    public static final String STRIPE = "system/stripe";

    private PaymentActors() {}
}
