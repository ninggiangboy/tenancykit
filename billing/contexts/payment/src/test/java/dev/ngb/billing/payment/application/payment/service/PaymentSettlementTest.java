package dev.ngb.billing.payment.application.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.money.api.ledger.LedgerEntryId;
import dev.ngb.billing.money.api.ledger.LedgerPostingResult;
import dev.ngb.billing.money.api.reservation.ReleaseReservation;
import dev.ngb.billing.money.api.reservation.ReservationResult;
import dev.ngb.billing.money.api.reservation.ReservationStatus;
import dev.ngb.billing.money.api.wallet.CreditCommand;
import dev.ngb.billing.money.api.wallet.CreditWallet;
import dev.ngb.billing.money.api.wallet.DebitCommand;
import dev.ngb.billing.money.api.wallet.DebitWallet;
import dev.ngb.billing.payment.api.payment.PaymentEventTypes;
import dev.ngb.billing.payment.application.incident.port.IncidentRepository;
import dev.ngb.billing.payment.application.messaging.service.PaymentActors;
import dev.ngb.billing.payment.application.payment.port.PaymentProvider;
import dev.ngb.billing.payment.application.payment.port.PaymentRepository;
import dev.ngb.billing.payment.application.refund.port.RefundRepository;
import dev.ngb.billing.payment.application.stripe.port.StripeConfigurationRepository;
import dev.ngb.billing.payment.domain.incident.IncidentKind;
import dev.ngb.billing.payment.domain.payment.Payment;
import dev.ngb.billing.payment.domain.payment.PaymentAttempt;
import dev.ngb.billing.payment.domain.payment.PaymentId;
import dev.ngb.billing.payment.domain.payment.PaymentStatus;
import dev.ngb.billing.payment.domain.refund.Refund;
import dev.ngb.billing.payment.domain.refund.RefundId;
import dev.ngb.billing.payment.domain.stripe.StripeConfiguration;
import dev.ngb.billing.payment.domain.stripe.StripeConfigurationId;
import dev.ngb.billing.payment.domain.stripe.StripeEnvironment;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The settlement of a verified result against in-memory ports: a top-up is credited and recorded as SUCCEEDED in
 * one step, a repeat changes nothing, and a result that contradicts a final payment credits nothing.
 */
class PaymentSettlementTest {

    private static final Currency USD = Currency.of("USD");
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final UUID ENTRY_ID = UUID.fromString("0198c1a0-0000-7000-8000-000000000042");

    private final WalletId walletId = new WalletId(UUID.randomUUID());
    private final CustomerId customerId = new CustomerId(UUID.randomUUID());
    private final StripeConfiguration configuration = StripeConfiguration.direct(new StripeConfigurationId(UUID.randomUUID()), TENANT, "acct_1",
            StripeEnvironment.TEST, "cipher-key", "cipher-whsec", "pk_test_1", NOW);

    private final Map<PaymentId, Payment> stored = new LinkedHashMap<>();
    private final List<Payment> updates = new ArrayList<>();
    private final List<CreditCommand> credits = new ArrayList<>();
    private final List<DomainEvent> published = new ArrayList<>();

    private final PaymentRepository payments = new PaymentRepository() {
        @Override
        public boolean addIfAbsent(Payment payment) {
            return stored.putIfAbsent(payment.id(), payment) == null;
        }

        @Override
        public void update(Payment payment) {
            stored.put(payment.id(), payment);
            updates.add(payment);
        }

        @Override
        public Optional<Payment> findById(PaymentId id) {
            return Optional.ofNullable(stored.get(id));
        }

        @Override
        public Optional<Payment> lockById(PaymentId id) {
            return findById(id);
        }

        @Override
        public Optional<Payment> lockByCheckoutSession(String sessionId) {
            return stored.values().stream().filter(payment -> sessionId.equals(payment.checkoutSessionId())).findFirst();
        }

        @Override
        public Optional<Payment> lockByPaymentIntent(String intentId) {
            return stored.values().stream().filter(payment -> intentId.equals(payment.paymentIntentId())).findFirst();
        }

        @Override
        public Optional<Payment> findByIdempotencyKey(String key) {
            return stored.values().stream().filter(payment -> key.equals(payment.idempotencyKey())).findFirst();
        }

        @Override
        public void addAttempt(PaymentId id, PaymentAttempt attempt) {}

        @Override
        public List<PaymentAttempt> attempts(PaymentId id) {
            return List.of();
        }
    };

    private final RefundRepository refunds = new RefundRepository() {
        @Override
        public void add(Refund refund) {}

        @Override
        public void update(Refund refund) {}

        @Override
        public Optional<Refund> findById(RefundId id) {
            return Optional.empty();
        }

        @Override
        public Optional<Refund> lockById(RefundId id) {
            return Optional.empty();
        }

        @Override
        public Optional<Refund> findByStripeRefundId(String stripeRefundId) {
            return Optional.empty();
        }

        @Override
        public Optional<Refund> findByIdempotencyKey(String key) {
            return Optional.empty();
        }

        @Override
        public long liveAmount(PaymentId paymentId) {
            return 0;
        }
    };

    private final IncidentRepository incidents = new IncidentRepository() {
        @Override
        public void record(UUID id, TenantId tenantId, StripeConfigurationId configurationId, IncidentKind kind, String subject, String detail, Instant now) {}

        @Override
        public boolean resolve(UUID id, Instant now) {
            return false;
        }
    };

    private final StripeConfigurationRepository configurations = new StripeConfigurationRepository() {
        @Override
        public void add(StripeConfiguration added) {}

        @Override
        public void update(StripeConfiguration updated) {}

        @Override
        public Optional<StripeConfiguration> findById(StripeConfigurationId id) {
            return Optional.of(configuration);
        }

        @Override
        public Optional<StripeConfiguration> findLive() {
            return Optional.of(configuration);
        }
    };

    private final CreditWallet credit = command -> {
        credits.add(command);
        return new LedgerPostingResult(new LedgerEntryId(ENTRY_ID), 1, command.amount(), command.amount(), false);
    };

    private final DebitWallet debit = command -> new LedgerPostingResult(new LedgerEntryId(UUID.randomUUID()), 1, command.amount(), command.amount(), false);

    private final ReleaseReservation release = id -> new ReservationResult(id, ReservationStatus.RELEASED, Money.zero(USD), NOW, false);

    private final PaymentSettlement settlement = new PaymentSettlement(payments, refunds, incidents, configurations, credit, debit, release,
            published::add, UUID::randomUUID, () -> NOW);

    private Payment topUp(long minorUnits) {
        Payment payment = Payment.start(new PaymentId(UUID.randomUUID()), configuration, walletId, customerId, Money.of(minorUnits, USD), "key-1", "hash", NOW);
        stored.put(payment.id(), payment);
        return payment;
    }

    private PaymentProvider.Intent intent(Payment payment, PaymentProvider.IntentState state, long minorUnits, String currency) {
        return new PaymentProvider.Intent("pi_1", payment.id().toString(), state, minorUnits, currency, null, null);
    }

    @Test
    void aVerifiedTopUpBecomesSucceededAndRemembersTheEntryThatCreditedTheWallet() {
        Payment payment = topUp(2_900);

        PaymentSettlement.Result result = settlement.intent(configuration, intent(payment, PaymentProvider.IntentState.SUCCEEDED, 2_900, "USD"), "evt_1");

        assertThat(result.kind()).isEqualTo(PaymentSettlement.Kind.APPLIED);
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.ledgerEntryId()).as("the entry id is part of the transition, not a later step").isEqualTo(ENTRY_ID);
        assertThat(payment.completedAt()).isEqualTo(NOW);
        assertThat(payment.failureReason()).isNull();
        assertThat(credits).singleElement().satisfies(command -> {
            assertThat(command.walletId()).isEqualTo(walletId);
            assertThat(command.amount()).isEqualTo(Money.of(2_900, USD));
            assertThat(command.idempotencyKey()).isEqualTo("topup:" + payment.id());
            assertThat(command.actor()).as("the actor names the system, not the provider's bare name").isEqualTo(PaymentActors.STRIPE);
        });
        assertThat(updates).containsExactly(payment);
        assertThat(published).extracting(DomainEvent::type).containsExactly(PaymentEventTypes.PAYMENT_SUCCEEDED);
    }

    @Test
    void aRepeatedSuccessCreditsAndAnnouncesOnce() {
        Payment payment = topUp(2_900);
        settlement.intent(configuration, intent(payment, PaymentProvider.IntentState.SUCCEEDED, 2_900, "USD"), "evt_1");

        PaymentSettlement.Result repeat = settlement.intent(configuration, intent(payment, PaymentProvider.IntentState.SUCCEEDED, 2_900, "USD"), "evt_2");

        assertThat(repeat.kind()).isEqualTo(PaymentSettlement.Kind.STALE);
        assertThat(credits).as("one credit per payment").hasSize(1);
        assertThat(updates).hasSize(1);
        assertThat(published).hasSize(1);
        assertThat(payment.ledgerEntryId()).isEqualTo(ENTRY_ID);
    }

    @Test
    void aSuccessThatContradictsAFailedPaymentCreditsNothing() {
        Payment payment = topUp(2_900);
        payment.fail("declined", NOW);

        PaymentSettlement.Result result = settlement.intent(configuration, intent(payment, PaymentProvider.IntentState.SUCCEEDED, 2_900, "USD"), "evt_3");

        assertThat(result.kind()).isEqualTo(PaymentSettlement.Kind.REJECTED);
        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.ledgerEntryId()).isNull();
        assertThat(credits).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void anInvoicePaymentSucceedsWithoutCreditingTheWallet() {
        Payment payment = Payment.startForInvoice(new PaymentId(UUID.randomUUID()), configuration, UUID.randomUUID(), walletId, customerId,
                Money.of(9_900, USD), "key-2", "hash", NOW);
        stored.put(payment.id(), payment);

        PaymentSettlement.Result result = settlement.intent(configuration, intent(payment, PaymentProvider.IntentState.SUCCEEDED, 9_900, "USD"), "evt_4");

        assertThat(result.kind()).isEqualTo(PaymentSettlement.Kind.APPLIED);
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.ledgerEntryId()).as("invoicing credits the wallet for this one").isNull();
        assertThat(credits).isEmpty();
        assertThat(published).extracting(DomainEvent::type).containsExactly(PaymentEventTypes.PAYMENT_SUCCEEDED);
    }

    @Test
    void anAmountStripeDidNotCollectExactlyIsRejectedBeforeAnythingMoves() {
        Payment payment = topUp(2_900);

        PaymentSettlement.Result result = settlement.intent(configuration, intent(payment, PaymentProvider.IntentState.SUCCEEDED, 2_000, "USD"), "evt_5");

        assertThat(result.kind()).isEqualTo(PaymentSettlement.Kind.REJECTED);
        assertThat(payment.status()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
        assertThat(payment.ledgerEntryId()).isNull();
        assertThat(credits).isEmpty();
        assertThat(updates).isEmpty();
        assertThat(published).isEmpty();
    }
}
