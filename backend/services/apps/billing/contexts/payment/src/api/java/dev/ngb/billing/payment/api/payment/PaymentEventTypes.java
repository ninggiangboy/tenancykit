package dev.ngb.billing.payment.api.payment;

import dev.ngb.core.event.SuiteEventTypes;

/**
 * The published names of the payment events other contexts react to: a consumer compares the type of an outbox event
 * with one of these constants, never with a literal. The schemas in {@code contracts/events} define the payloads.
 */
public final class PaymentEventTypes {

    /** A top-up was paid, or an invoice payment was paid. */
    public static final String PAYMENT_SUCCEEDED = SuiteEventTypes.PAYMENT_SUCCEEDED;

    /** A top-up ended without money. */
    public static final String PAYMENT_FAILED = SuiteEventTypes.PAYMENT_FAILED;

    private PaymentEventTypes() {}
}
