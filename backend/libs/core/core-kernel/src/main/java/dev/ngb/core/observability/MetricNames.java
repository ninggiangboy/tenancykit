package dev.ngb.core.observability;

/**
 * The suite's metric names, one name per event. A counter is named {@code <app>.<name>}: the app prefix is the
 * service the meter belongs to, so the same fact in two services is still two meters.
 *
 * <p>The two outbox relays report different facts and therefore carry different names: the in-process relay
 * hands a row to its consumers ({@code outbox.delivered}) while the broker relay waits for the broker's confirm
 * ({@code outbox.publish.delivered}). A dashboard reads them separately, because a broker that is down shows up
 * in the second and not in the first.
 */
public final class MetricNames {

    /** Rows the in-process relay handed to the consumers. */
    public static final String OUTBOX_DELIVERED = "outbox.delivered";

    /** Rows the in-process relay gave up on. */
    public static final String OUTBOX_DEAD = "outbox.dead";

    /** Rows the broker relay published and the broker confirmed. */
    public static final String OUTBOX_PUBLISH_DELIVERED = "outbox.publish.delivered";

    /** Rows the broker relay gave up on. */
    public static final String OUTBOX_PUBLISH_DEAD = "outbox.publish.dead";

    /** Webhook deliveries by {@code outcome}. */
    public static final String WEBHOOK_DELIVERY = "webhook.delivery";

    /** The wait of one successful delivery, from the delivery row to the completed attempt. */
    public static final String WEBHOOK_LAG = "webhook.lag";

    /** Events a consumer refused as malformed, by {@code consumer}. */
    public static final String EVENTS_MALFORMED = "events.malformed";

    /** Folders an aggregation cycle closed. */
    public static final String JOB_INVOICING_CYCLE_INVOICED = "invoicing.cycle.invoiced";

    /** Invoices an aggregation cycle could not close. */
    public static final String JOB_INVOICING_CYCLE_FAILED = "invoicing.cycle.failed";

    /** Subscription periods renewed. */
    public static final String JOB_SUBSCRIPTION_RENEWED = "subscription.renewed";

    /** Subscription periods that could not be renewed. */
    public static final String JOB_SUBSCRIPTION_RENEWAL_FAILED = "subscription.renewal.failed";

    /** Stripe events by {@code outcome}. */
    public static final String JOB_STRIPE_EVENT = "stripe.event";

    /** Findings of a Stripe reconciliation that had to be flagged. */
    public static final String JOB_STRIPE_RECONCILE_MISMATCH = "stripe.reconcile.mismatch";

    /** Ledger wallets whose entries did not add up. */
    public static final String JOB_LEDGER_MISMATCH = "ledger.mismatch";

    private MetricNames() {}

    /** The name of a meter in {@code app}, such as {@code billing.outbox.delivered}. */
    public static String in(String app, String name) {
        return app + "." + name;
    }
}
