package dev.ngb.billing.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.Application;
import dev.ngb.billing.money.api.charge.ChargeWallet;
import dev.ngb.billing.money.api.wallet.WalletLookup;
import dev.ngb.billing.money.application.reservation.command.ExpireReservationsUseCase;
import dev.ngb.billing.money.application.wallet.query.WalletQueries;
import dev.ngb.billing.payment.PaymentTestWorld.PayWorld;
import dev.ngb.billing.payment.application.incident.query.IncidentQueries;
import dev.ngb.billing.payment.application.payment.command.CreateTopUpUseCase;
import dev.ngb.billing.payment.application.payment.port.PaymentRepository;
import dev.ngb.billing.payment.application.payment.query.PaymentQueries;
import dev.ngb.billing.payment.application.payment.query.PaymentView;
import dev.ngb.billing.payment.application.refund.command.RequestRefundUseCase;
import dev.ngb.billing.payment.application.refund.query.RefundQueries;
import dev.ngb.billing.payment.application.refund.query.RefundView;
import dev.ngb.billing.payment.application.stripe.command.ManageStripeEventUseCase;
import dev.ngb.billing.payment.application.stripe.command.ReceiveStripeWebhookUseCase;
import dev.ngb.billing.payment.application.stripe.command.ReceiveStripeWebhookUseCase.Disposition;
import dev.ngb.billing.payment.application.stripe.command.ReconcileStripeUseCase;
import dev.ngb.billing.payment.application.stripe.command.RetryStripeEventsUseCase;
import dev.ngb.billing.payment.application.stripe.port.StripeEventRepository;
import dev.ngb.billing.payment.application.stripe.query.StripeEventQueries;
import dev.ngb.billing.payment.application.stripe.service.StripeConfigurationService;
import dev.ngb.billing.payment.domain.payment.Payment;
import dev.ngb.billing.payment.domain.payment.PaymentId;
import dev.ngb.billing.payment.domain.payment.PaymentStatus;
import dev.ngb.billing.payment.domain.refund.Refund;
import dev.ngb.billing.payment.domain.stripe.StripeEventId;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.support.FakePaymentProvider;
import dev.ngb.billing.support.FakePaymentProviderConfig;
import dev.ngb.billing.support.OwnerDatabase;
import dev.ngb.billing.support.PostgresContainerConfig;
import dev.ngb.billing.support.ScopeTestSupport;
import dev.ngb.billing.support.StripeEvents;
import dev.ngb.billing.support.UncommittedRace;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The payment flows against real PostgreSQL, with Stripe replaced by an in-memory provider (which keeps real
 * signature checking and event parsing). The assertions are about the books: what the wallet and ledger hold,
 * what was announced, and that nothing changes except from a verified Stripe result.
 *
 * <p>The four suites that use it split top-up, verified Stripe results, refunds and reconciliation; every fixture
 * and helper they share lives here, so one change to the world is one change for all of them.
 */
@SpringBootTest(classes = Application.class)
@Import({PostgresContainerConfig.class, FakePaymentProviderConfig.class})
abstract class PaymentWorld {

    static final Audit AUDIT = new Audit("user/test", "req-1");

    /** Stripe ids are globally unique, so every test gets its own. */
    final String sfx = "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    @Autowired PaymentTestWorld worlds;
    @Autowired ScopeTestSupport scopes;
    @Autowired OwnerDatabase owner;
    @Autowired FakePaymentProvider stripe;
    @Autowired CreateTopUpUseCase topUp;
    @Autowired RequestRefundUseCase refund;
    @Autowired ReceiveStripeWebhookUseCase webhooks;
    @Autowired RetryStripeEventsUseCase retry;
    @Autowired StripeEventRepository stripeEvents;
    @Autowired ReconcileStripeUseCase reconcile;
    @Autowired StripeConfigurationService configService;
    @Autowired ExpireReservationsUseCase expireReservations;
    @Autowired ManageStripeEventUseCase eventAdmin;
    @Autowired StripeEventQueries eventQueries;
    @Autowired IncidentQueries incidentQueries;
    @Autowired RefundQueries refundQueries;
    @Autowired PaymentQueries queries;
    @Autowired PaymentRepository paymentRepository;
    @Autowired UnitOfWork unitOfWork;
    @Autowired WalletQueries wallets;
    @Autowired ChargeWallet charge;
    @Autowired WalletLookup walletLookup;

    @BeforeEach
    void resetStripe() {
        stripe.reset();
    }

    Payment startTopUp(PayWorld w, long amount) {
        return startTopUp(w, amount, "key-" + UUID.randomUUID()).payment();
    }

    CreateTopUpUseCase.Result startTopUp(PayWorld w, long amount, String key) {
        return scopes.inTenant(w.money().tenant().id(), () -> topUp.execute(new CreateTopUpUseCase.Command(
                w.money().wallet().id(), Money.of(amount, w.money().wallet().currency()), key, AUDIT)));
    }

    ReceiveStripeWebhookUseCase.Receipt deliver(PayWorld w, String body) {
        return webhooks.receiveDirect(w.configuration().id(), body, StripeEvents.sign(w.webhookSecret(), body));
    }

    ReceiveStripeWebhookUseCase.Receipt paid(PayWorld w, Payment p, String eventId, String intent) {
        return deliver(w, StripeEvents.paidCheckout(eventId, null, p.checkoutSessionId(), intent, p.id().toString(), p.amount().minorUnits()));
    }

    long balance(PayWorld w) {
        return owner.count("select balance from wallets where id = ?", w.money().wallet().id().value());
    }

    long held(PayWorld w) {
        return owner.count("select held from wallets where id = ?", w.money().wallet().id().value());
    }

    long ledgerEntries(PayWorld w) {
        return owner.count("select count(*) from ledger_entries where wallet_id = ?", w.money().wallet().id().value());
    }

    long outbox(PayWorld w, String type) {
        return owner.count("select count(*) from outbox where tenant_id = ? and type = ?", w.money().tenant().id().value(), type);
    }

    PaymentView reload(PayWorld w, Payment p) {
        return scopes.inTenant(w.money().tenant().id(), () -> queries.payment(p.id().value()).payment());
    }

    Refund requestRefund(PayWorld w, Payment p, Long amount, String key) {
        Money money = amount == null ? null : Money.of(amount, p.amount().currency());
        return scopes.inTenant(w.money().tenant().id(), () -> refund.execute(new RequestRefundUseCase.Command(p.id(), money, "requested", key, null, AUDIT)).refund());
    }

    RefundView reloadRefund(PayWorld w, Refund r) {
        return scopes.inTenant(w.money().tenant().id(), () -> refundQueries.get(r.id().value()));
    }

    void refundEvent(PayWorld w, Payment p, Refund r, String status) {
        deliver(w, StripeEvents.refund(StripeEvents.newEventId(), null, r.stripeRefundId(), p.paymentIntentId(), r.id().toString(), status,
                r.amount().minorUnits()));
    }

    /** A payment that was topped up and credited, ready to be refunded. */
    Payment settledPayment(PayWorld w, long amount) {
        Payment p = startTopUp(w, amount);
        assertThat(paid(w, p, StripeEvents.newEventId(), "pi_" + UUID.randomUUID().toString().replace("-", "")).disposition()).isEqualTo(Disposition.PROCESSED);
        assertThat(reload(w, p).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        return scopes.inTenant(w.money().tenant().id(), () -> unitOfWork.transactional(() -> paymentRepository.findById(p.id()).orElseThrow()));
    }

    void makeFailedEventsDue(TenantId tenant) {
        owner.jdbc().update("update stripe_events set next_attempt_at = now() - interval '1 second' where tenant_id = ? and status = 'FAILED'",
                tenant.value());
    }

    List<StripeEventId> claim(TenantId tenant) {
        Instant now = Instant.now();
        return scopes.inTenant(tenant, () -> unitOfWork.transactional(
                () -> stripeEvents.claimDue(now, now.minus(Duration.ofMinutes(2)), Duration.ofMinutes(2), 10)));
    }

    void expireHolds(PayWorld w, Refund requested) {
        owner.jdbc().update("update reservations set expires_at = now() - interval '1 second', created_at = now() - interval '1 minute' where id = ?",
                requested.reservationId());
        scopes.inTenant(w.money().tenant().id(), () -> expireReservations.execute(10));
        assertThat(held(w)).as("the hold is gone").isZero();
    }

    static Object attempt(java.util.concurrent.CountDownLatch start, Supplier<Refund> work) {
        try {
            start.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return work.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new IllegalStateException(e);
        } catch (RuntimeException e) {
            return e;
        }
    }
}
