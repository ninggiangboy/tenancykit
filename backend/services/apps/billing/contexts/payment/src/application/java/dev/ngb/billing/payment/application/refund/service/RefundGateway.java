package dev.ngb.billing.payment.application.refund.service;

import dev.ngb.core.names.IdempotencyKeys;
import dev.ngb.billing.payment.application.payment.port.PaymentProvider;
import dev.ngb.billing.payment.application.stripe.port.StripeConfigurationRepository;
import dev.ngb.billing.payment.application.stripe.service.ProviderAccess;
import dev.ngb.billing.payment.domain.payment.Payment;
import dev.ngb.billing.payment.domain.refund.Refund;
import dev.ngb.billing.payment.domain.stripe.StripeConfiguration;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.NotFoundException;

/**
 * The provider side of a refund: which configuration a payment can be refunded through, and the call that sends the
 * money back. It reads the configuration table and calls the provider and touches no other row, so the use case can
 * ask Stripe while it holds no lock and write the answer in a transaction of its own.
 */
public class RefundGateway {

    private final StripeConfigurationRepository configurations;
    private final PaymentProvider provider;
    private final ProviderAccess access;

    public RefundGateway(StripeConfigurationRepository configurations, PaymentProvider provider, ProviderAccess access) {
        this.configurations = configurations;
        this.provider = provider;
        this.access = access;
    }

    /**
     * The configuration a payment can be refunded through: its own while connected, or the current one when the
     * Tenant has since linked the same Stripe account again (a rotated key). A payment of an account that is no
     * longer connected has to be refunded in the Stripe Dashboard.
     */
    public StripeConfiguration usable(Payment payment) {
        StripeConfiguration own = configurations.findById(payment.configurationId()).orElseThrow(() -> new NotFoundException("no stripe configuration " + payment.configurationId()));
        if (own.status().isLive()) {
            return own;
        }
        return configurations.findLive().filter(live -> live.accountId().equals(own.accountId()))
                .orElseThrow(() -> new ConflictException("the payment's Stripe account is no longer connected, so it cannot be refunded here"));
    }

    /**
     * Asks the provider to send the money back. The idempotency key is the refund's own, so a request repeated
     * while the provider still remembers the key returns the first refund instead of sending the money twice.
     */
    public PaymentProvider.RefundObject refund(StripeConfiguration configuration, Refund refund, Payment payment) {
        return provider.createRefund(access.of(configuration), new PaymentProvider.RefundRequest(
                refund.id(), payment.id(), payment.paymentIntentId(), refund.amount(), refund.reason()), IdempotencyKeys.provider(IdempotencyKeys.REFUND, refund.id()));
    }
}
