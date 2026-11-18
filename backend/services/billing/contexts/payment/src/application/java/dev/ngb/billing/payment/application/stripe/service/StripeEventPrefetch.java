package dev.ngb.billing.payment.application.stripe.service;

import dev.ngb.billing.payment.application.payment.port.PaymentProvider;
import dev.ngb.billing.payment.application.stripe.exception.InvalidSignatureException;
import dev.ngb.billing.payment.application.stripe.port.StripeConfigurationRepository;
import dev.ngb.billing.payment.domain.stripe.IntegrationMode;
import dev.ngb.billing.payment.domain.stripe.StripeConfiguration;
import dev.ngb.billing.payment.domain.stripe.StripeConfigurationId;
import dev.ngb.billing.payment.domain.stripe.StripeEvent;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.SecretCipher;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Stripe side of an event: the configuration it belongs to, its signature, the payload as a verified event, and
 * the objects Stripe holds that applying it needs. Every read from Stripe happens here, outside any row lock, so a
 * caller never holds a lock across a network call.
 */
public class StripeEventPrefetch {

    private final UnitOfWork unitOfWork;
    private final StripeConfigurationRepository configurations;
    private final PaymentProvider provider;
    private final ProviderAccess access;
    private final SecretCipher cipher;

    public StripeEventPrefetch(
            UnitOfWork unitOfWork, StripeConfigurationRepository configurations, PaymentProvider provider, ProviderAccess access,
            SecretCipher cipher) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.access = Objects.requireNonNull(access, "access");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    /** What applying an event needs from Stripe, read before the transaction opens. */
    public record Prefetch(PaymentProvider.Intent intent, PaymentProvider.AccountState account) {
        public static final Prefetch NONE = new Prefetch(null, null);
    }

    /** The configuration a webhook endpoint names, or empty when it is unknown. */
    public Optional<StripeConfiguration> configuration(StripeConfigurationId id) {
        return unitOfWork.transactional(() -> configurations.findById(id));
    }

    /** The live configuration of the current tenant, or empty when Stripe is not configured. */
    public Optional<StripeConfiguration> liveConfiguration() {
        return unitOfWork.transactional(configurations::findLive);
    }

    /** The configuration of a stored event; its absence means the row was deleted under a queued event. */
    public StripeConfiguration configurationOf(StripeEvent event) {
        return unitOfWork.transactional(() -> configurations.findById(event.configurationId()))
                .orElseThrow(() -> new IllegalStateException("the event's configuration is missing"));
    }

    /** The payload as a verified event, taken as it was stored. */
    public PaymentProvider.ProviderEvent interpret(String payload) {
        return provider.interpret(payload);
    }

    /**
     * Verifies a Direct webhook with the endpoint's own secret.
     *
     * @throws InvalidSignatureException when the body does not match the signature or the timestamp is stale
     */
    public PaymentProvider.ProviderEvent verify(StripeConfiguration configuration, String payload, String signature) {
        return verify(payload, signature, cipher.decrypt(configuration.webhookSecretCiphertext(), configuration.id().toString()));
    }

    /**
     * Verifies a webhook against a secret the caller holds, such as the Platform's Connect secret.
     *
     * @throws InvalidSignatureException when the body does not match the signature or the timestamp is stale
     */
    public PaymentProvider.ProviderEvent verify(String payload, String signature, String secret) {
        return provider.verifyWebhook(payload, signature, secret);
    }

    public List<PaymentProvider.Checkout> recentCheckouts(StripeConfiguration configuration, Instant since) {
        return provider.recentCheckouts(access.of(configuration), since);
    }

    public List<PaymentProvider.RefundObject> recentRefunds(StripeConfiguration configuration, Instant since) {
        return provider.recentRefunds(access.of(configuration), since);
    }

    public PaymentProvider.Intent retrievePayment(StripeConfiguration configuration, String paymentIntentId) {
        return provider.retrievePayment(access.of(configuration), paymentIntentId);
    }

    /**
     * Applies what Stripe now says a connected account can do, rather than the snapshot the event carried, and
     * stores it.
     */
    public void observeAccount(StripeConfiguration configuration, PaymentProvider.AccountState account, Instant now) {
        configuration.observeAccount(account.cardPaymentsActive(), now);
        unitOfWork.transactional(() -> configurations.update(configuration));
    }

    /** True for the event that reports a delayed payment method failed after the session completed. */
    public static boolean isAsyncFailure(PaymentProvider.ProviderEvent observed) {
        return "checkout.session.async_payment_failed".equals(observed.type());
    }

    /**
     * Reads what applying {@code event} needs: the payment intent of a completed session that is not yet paid (a
     * delayed payment method), and the state of a connected account. Everything else is in the event.
     */
    public Prefetch fetch(StripeEvent event) {
        if (event.status().isFinal()) {
            return Prefetch.NONE;
        }
        PaymentProvider.ProviderEvent observed = provider.interpret(event.payload());
        PaymentProvider.Subject subject = observed.subject();
        boolean unpaidSession = subject instanceof PaymentProvider.Checkout c && c.status() == PaymentProvider.CheckoutStatus.COMPLETE
                && c.payment() == PaymentProvider.CheckoutPayment.UNPAID && c.paymentIntentId() != null && !isAsyncFailure(observed);
        if (!unpaidSession && !(subject instanceof PaymentProvider.AccountObject)) {
            return Prefetch.NONE;
        }
        StripeConfiguration configuration = unitOfWork.transactional(() -> configurations.findById(event.configurationId()))
                .orElseThrow(() -> new IllegalStateException("the event's configuration is missing"));
        if (unpaidSession) {
            return new Prefetch(
                    provider.retrievePayment(access.of(configuration), ((PaymentProvider.Checkout) subject).paymentIntentId()), null);
        }
        if (configuration.mode() == IntegrationMode.CONNECT
                && configuration.accountId().equals(((PaymentProvider.AccountObject) subject).accountId())) {
            return new Prefetch(null, provider.retrieveAccount(access.platformKey(), configuration.accountId()));
        }
        return Prefetch.NONE;
    }
}
