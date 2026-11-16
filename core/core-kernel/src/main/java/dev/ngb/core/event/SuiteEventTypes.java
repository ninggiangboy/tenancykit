package dev.ngb.core.event;

import java.util.Set;
import java.util.TreeSet;

/**
 * Every event type on the suite's wire, in one place. The name is the contract: it is the AMQP routing key, the
 * {@code type} of the delivered document, and the base name of the schema file under
 * {@code contracts/events/<app>/<type>.schema.json}. Nothing else may spell a type as a literal.
 *
 * <p>The producer's own {@code ...EventTypes} class in its {@code api} source set keeps the names a consumer of
 * that producer reacts to; both are this list. One test per producer compares {@link #billing()} and
 * {@link #platform()} with the schema files of that app, so a new schema without a constant — or a constant
 * without a schema — fails the build.
 */
public final class SuiteEventTypes {

    // Billing: the money path.
    public static final String WALLET_CREDITED = "wallet.credited";
    public static final String WALLET_DEBITED = "wallet.debited";
    public static final String RESERVATION_CREATED = "reservation.created";
    public static final String RESERVATION_RELEASED = "reservation.released";
    public static final String RESERVATION_EXPIRED = "reservation.expired";
    public static final String CHARGE_SUCCEEDED = "charge.succeeded";
    public static final String CHARGE_FAILED = "charge.failed";
    public static final String LEDGER_ENTRY_REVERSED = "ledger.entry-reversed";

    // Billing: the customer that owns a wallet.
    public static final String CUSTOMER_CREATED = "customer.created";

    // Billing: pricing and usage.
    public static final String PRICING_PLAN_VERSION_PUBLISHED = "pricing-plan.version-published";
    public static final String PRICING_PLAN_RETIRED = "pricing-plan.retired";
    public static final String USAGE_RATED = "usage.rated";

    // Billing: paying.
    public static final String PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String REFUND_SUCCEEDED = "refund.succeeded";

    // Billing: invoicing.
    public static final String INVOICE_CREATED = "invoice.created";
    public static final String INVOICE_PAID = "invoice.paid";
    public static final String SUBSCRIPTION_RENEWED = "subscription.renewed";
    public static final String SUBSCRIPTION_PAYMENT_FAILED = "subscription.payment_failed";

    // Billing: coupons.
    public static final String COUPON_REDEEMED = "coupon.redeemed";
    public static final String COUPON_REDEMPTION_ENDED = "coupon.redemption-ended";

    // Platform: the state-transfer events every consumer replicates.
    public static final String TENANT_CHANGED = "tenant.changed";
    public static final String TENANT_DOMAIN_CHANGED = "tenant-domain.changed";
    public static final String TENANT_SYSTEM_CHANGED = "tenant-system.changed";
    public static final String USER_ACCESS_CHANGED = "user-access.changed";
    public static final String ENTITLEMENT_CHANGED = "entitlement.changed";

    // Survey: the three facts a Tenant reacts to.
    public static final String SURVEY_VERSION_PUBLISHED = "survey-version.published";
    public static final String SURVEY_CLOSED = "survey.closed";
    public static final String SURVEY_RESPONSE_SUBMITTED = "survey-response.submitted";

    /** The five {@code *.changed} types of the replica, in the order a rebuild pages them. */
    public static final Set<String> PLATFORM_REPLICA_TYPES = Set.of(
            TENANT_CHANGED, TENANT_DOMAIN_CHANGED, TENANT_SYSTEM_CHANGED, USER_ACCESS_CHANGED, ENTITLEMENT_CHANGED);

    private SuiteEventTypes() {}

    /** Every type Billing produces, matching the schemas under {@code contracts/events/billing}. */
    public static Set<String> billing() {
        return Set.of(
                WALLET_CREDITED, WALLET_DEBITED, RESERVATION_CREATED, RESERVATION_RELEASED, RESERVATION_EXPIRED, CHARGE_SUCCEEDED,
                CHARGE_FAILED, LEDGER_ENTRY_REVERSED, CUSTOMER_CREATED, PRICING_PLAN_VERSION_PUBLISHED, PRICING_PLAN_RETIRED,
                USAGE_RATED, PAYMENT_SUCCEEDED, PAYMENT_FAILED, REFUND_SUCCEEDED, INVOICE_CREATED, INVOICE_PAID, SUBSCRIPTION_RENEWED,
                SUBSCRIPTION_PAYMENT_FAILED, COUPON_REDEEMED, COUPON_REDEMPTION_ENDED);
    }

    /** Every type Platform produces, matching the schemas under {@code contracts/events/platform}. */
    public static Set<String> platform() {
        return PLATFORM_REPLICA_TYPES;
    }

    /** Every type Survey produces, matching the schemas under {@code contracts/events/survey}. */
    public static Set<String> survey() {
        return Set.of(SURVEY_VERSION_PUBLISHED, SURVEY_CLOSED, SURVEY_RESPONSE_SUBMITTED);
    }

    /** Every type of the suite, producers together. */
    public static Set<String> all() {
        Set<String> types = new TreeSet<>(billing());
        types.addAll(platform());
        types.addAll(survey());
        return Set.copyOf(types);
    }
}
