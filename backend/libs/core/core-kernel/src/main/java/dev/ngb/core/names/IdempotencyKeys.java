package dev.ngb.core.names;

/**
 * The idempotency keys the suite builds when the client sends none: {@code <scope>:<part>[:<part>...]}.
 *
 * <p>The separator is {@code :} for a composed key — unlike a target, whose separator is {@code /} — so a key that
 * names a business identity (a charge, a subscription period, an order) can never be mistaken for one. A key is
 * stored in the idempotency row and in the ledger's reference, so it is never assembled inline: adding a scope is
 * one method here.
 *
 * <p>A provider key is the exception: Stripe's own idempotency header takes {@code <scope>-<id>}, which
 * {@link #provider(String, Object)} builds.
 */
public final class IdempotencyKeys {

    /** The separator between the parts of a composed key. */
    public static final String SEPARATOR = ":";

    /** The separator between the parts of a provider's idempotency key. */
    public static final String PROVIDER_SEPARATOR = "-";

    public static final String CHARGE = "charge";
    public static final String REFUND = "refund";
    public static final String REFUND_HOLD = "refund-hold";
    public static final String TOP_UP = "topup";
    public static final String SUBSCRIPTION = "subscription";
    public static final String ORDER = "order";
    public static final String USAGE = "usage";
    public static final String WALLET = "wallet";

    private IdempotencyKeys() {}

    /** {@code <scope>:<part>[:<part>...]}, the shape every key has. */
    public static String of(String scope, Object... parts) {
        StringBuilder key = new StringBuilder(scope);
        for (Object part : parts) {
            key.append(SEPARATOR).append(part);
        }
        return key.toString();
    }

    public static String charge(Object chargeId) {
        return of(CHARGE, chargeId);
    }

    public static String refund(Object refundId) {
        return of(REFUND, refundId);
    }

    /** The key of the hold a refund places on the wallet while Stripe works the refund. */
    public static String refundHold(Object refundId) {
        return of(REFUND_HOLD, refundId);
    }

    public static String topUp(Object paymentId) {
        return of(TOP_UP, paymentId);
    }

    /** The key of one period of a subscription, so a repeated renewal charges the period once. */
    public static String subscription(Object subscriptionId, int periodNumber) {
        return of(SUBSCRIPTION, subscriptionId, periodNumber);
    }

    /** The key of one order, derived from the customer and the client's own key. */
    public static String order(Object customerId, Object clientKey) {
        return of(ORDER, customerId, clientKey);
    }

    /** The key of one usage record, derived from the order it belongs to and the metric. */
    public static String usage(Object orderId, Object metric) {
        return of(USAGE, orderId, metric);
    }

    /** The key of a customer's wallet settings, derived from the customer and the currency. */
    public static String wallet(Object customerId, Object currency) {
        return of(WALLET, customerId, currency);
    }

    /** A third party's own key for one call, such as {@code topup-<id>} for Stripe. */
    public static String provider(String scope, Object id) {
        return scope + PROVIDER_SEPARATOR + id;
    }
}
