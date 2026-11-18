package dev.ngb.billing.sharedkernel.error;

import dev.ngb.core.error.NotFoundException;

/**
 * The messages a context raises about a resource that is not there. Each one is the sentence a client sees through
 * the error handlers, so it is written once and never inlined: the text of a message that is already asserted by a
 * test cannot drift because an unrelated change retyped it.
 *
 * <p>A lookup that a client cannot distinguish from a resource it may not see (a Customer of another Tenant
 * System, for instance) answers the same sentence as one that does not exist, which is why these are shared across
 * the contexts that read the same resource.
 */
public final class Messages {

    private Messages() {}

    public static NotFoundException customerNotFound() {
        return new NotFoundException("customer not found");
    }

    public static NotFoundException walletNotFound() {
        return new NotFoundException("wallet not found");
    }

    public static NotFoundException reservationNotFound() {
        return new NotFoundException("reservation not found");
    }

    public static NotFoundException chargeNotFound() {
        return new NotFoundException("charge not found");
    }

    public static NotFoundException ledgerEntryNotFound() {
        return new NotFoundException("ledger entry not found");
    }

    public static NotFoundException paymentNotFound() {
        return new NotFoundException("payment not found");
    }

    public static NotFoundException refundNotFound() {
        return new NotFoundException("refund not found");
    }

    public static NotFoundException stripeEventNotFound() {
        return new NotFoundException("stripe event not found");
    }

    public static NotFoundException incidentNotFound() {
        return new NotFoundException("open incident not found");
    }

    public static NotFoundException invoiceNotFound() {
        return new NotFoundException("invoice not found");
    }

    public static NotFoundException subscriptionNotFound() {
        return new NotFoundException("subscription not found");
    }

    /** A coupon, a code or a redemption is not there; none of them is probed for existence by a caller. */
    public static NotFoundException couponNotFound() {
        return new NotFoundException("coupon not found");
    }

    public static NotFoundException promotionCodeNotFound() {
        return new NotFoundException("promotion code not found");
    }

    public static NotFoundException couponRedemptionNotFound() {
        return new NotFoundException("coupon redemption not found");
    }

    /**
     * The one answer for a coupon or code that cannot be redeemed now: unknown, inactive, expired or exhausted all
     * read the same, so a caller cannot probe which codes exist or which are spent.
     */
    public static NotFoundException couponNotRedeemable() {
        return new NotFoundException("coupon not found");
    }

    public static NotFoundException pricingPlanNotFound() {
        return new NotFoundException("pricing plan not found");
    }

    public static NotFoundException planVersionNotFound() {
        return new NotFoundException("plan version not found");
    }

    public static NotFoundException usageRatingNotFound() {
        return new NotFoundException("usage rating not found");
    }

    public static NotFoundException webhookEndpointNotFound() {
        return new NotFoundException("webhook endpoint not found");
    }

    public static NotFoundException webhookDeliveryNotFound() {
        return new NotFoundException("webhook delivery not found");
    }

    public static NotFoundException billingSessionNotFound() {
        return new NotFoundException("billing session not found");
    }

    public static NotFoundException webhookRouteNotFound() {
        return new NotFoundException("unknown webhook endpoint");
    }
}
