package dev.ngb.billing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.billing.money.api.charge.ChargeCommand;
import dev.ngb.billing.money.api.charge.ChargeStatus;
import dev.ngb.billing.payment.PaymentTestWorld.PayWorld;
import dev.ngb.billing.payment.application.incident.query.IncidentListing;
import dev.ngb.billing.payment.application.refund.command.RequestRefundUseCase;
import dev.ngb.billing.payment.application.stripe.command.ReceiveStripeWebhookUseCase.Disposition;
import dev.ngb.billing.payment.application.stripe.exception.ProviderRejectedException;
import dev.ngb.billing.payment.application.stripe.exception.ProviderUnavailableException;
import dev.ngb.billing.payment.domain.payment.Payment;
import dev.ngb.billing.payment.domain.refund.Refund;
import dev.ngb.billing.payment.domain.refund.RefundStatus;
import dev.ngb.billing.payment.application.refund.query.RefundView;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.support.FakePaymentProvider;
import dev.ngb.billing.support.ListQueries;
import dev.ngb.billing.support.StripeEvents;
import dev.ngb.billing.support.UncommittedRace;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.IdempotencyConflictException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Refunds: the amount is held the moment it is requested and debited only when Stripe confirms, a refund is
 * idempotent by key, and a hold that has expired never drives the wallet negative.
 */
class RefundIntegrationTest extends PaymentWorld {

    @Test
    void aRefundStripeSettlesAfterItsHoldExpiredIsStillDebitedWhileTheBalanceIsThere() {
        PayWorld w = worlds.direct(0);
        Payment p = settledPayment(w, 5000);
        Refund requested = requestRefund(w, p, null, "late-1");
        expireHolds(w, requested);

        refundEvent(w, p, requested, "succeeded");

        assertThat(reloadRefund(w, requested).status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(balance(w)).isZero();
        assertThat(held(w)).isZero();
    }

    @Test
    void aRefundSettledAfterItsHoldExpiredAndTheBalanceWasSpentIsFlaggedAndNeverOverdrawsTheWallet() {
        PayWorld w = worlds.direct(0);
        Payment p = settledPayment(w, 5000);
        Refund requested = requestRefund(w, p, null, "late-2");
        expireHolds(w, requested);
        scopes.inTenant(w.money().tenant().id(), () -> charge.charge(new ChargeCommand(w.money().wallet().id(), null,
                Money.of(4000, p.amount().currency()), "usage-late", null, "spend-late")));

        var receipt = deliver(w, StripeEvents.refund(StripeEvents.newEventId(), null, requested.stripeRefundId(), p.paymentIntentId(),
                requested.id().toString(), "succeeded", 5000));

        assertThat(receipt.disposition()).isEqualTo(Disposition.DEFERRED);
        assertThat(balance(w)).as("the wallet is not driven negative").isEqualTo(1000);
        assertThat(reloadRefund(w, requested).status()).as("the refund stays open for a person").isEqualTo(RefundStatus.PENDING);
        assertThat(owner.count("select count(*) from stripe_events where tenant_id = ? and status = 'FAILED' and last_error like '%Insufficient%'",
                w.money().tenant().id().value())).isEqualTo(1);
    }

    @Test
    void aRefundWhoseOutcomeStaysUnknownForHalfADayCannotBeRepeated() {
        PayWorld w = worlds.direct(0);
        Payment p = settledPayment(w, 5000);
        stripe.failNextRefund = FakePaymentProvider.unreachable();
        assertThatThrownBy(() -> requestRefund(w, p, null, "old-1")).isInstanceOf(ProviderUnavailableException.class);
        owner.jdbc().update("update refunds set created_at = now() - interval '13 hours' where tenant_id = ?", w.money().tenant().id().value());

        assertThatThrownBy(() -> requestRefund(w, p, null, "old-1")).isInstanceOf(ConflictException.class).hasMessageContaining("unknown");

        assertThat(stripe.refunds).hasSize(1);
    }

    @Test
    void aRefundHoldsTheBalanceAtOnceAndDebitsOnlyWhenStripeConfirms() {
        PayWorld w = worlds.direct(0);
        Payment p = settledPayment(w, 5000);

        Refund requested = requestRefund(w, p, null, "refund-1");

        assertThat(requested.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(requested.amount()).isEqualTo(Money.of(5000, p.amount().currency()));
        assertThat(requested.stripeRefundId()).startsWith("re_fake_");
        assertThat(balance(w)).as("the ledger is untouched until Stripe confirms").isEqualTo(5000);
        assertThat(held(w)).as("but the unspent balance is held").isEqualTo(5000);
        assertThat(stripe.refunds).hasSize(1);
        assertThat(stripe.refunds.get(0).idempotencyKey()).isEqualTo("refund-" + requested.id());

        refundEvent(w, p, requested, "succeeded");

        assertThat(balance(w)).isZero();
        assertThat(held(w)).isZero();
        RefundView done = reloadRefund(w, requested);
        assertThat(done.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(done.ledgerEntryId()).isNotNull();
        assertThat(reload(w, p).refunded()).isEqualTo(Money.of(5000, p.amount().currency()));
        assertThat(outbox(w, "refund.succeeded")).isEqualTo(1);
        assertThat(outbox(w, "wallet.debited")).isEqualTo(1);
    }

    @Test
    void aRepeatedRefundEventDebitsOnce() {
        PayWorld w = worlds.direct(0);
        Payment p = settledPayment(w, 5000);
        Refund requested = requestRefund(w, p, 2000L, "refund-1");

        refundEvent(w, p, requested, "succeeded");
        refundEvent(w, p, requested, "succeeded");

        assertThat(balance(w)).isEqualTo(3000);
        assertThat(ledgerEntries(w)).isEqualTo(2);
        assertThat(outbox(w, "refund.succeeded")).isEqualTo(1);
    }

    @Test
    void aPartialThenAFullRefundStaysWithinTheCapturedAmount() {
        PayWorld w = worlds.direct(0);
        Payment p = settledPayment(w, 10_000);

        Refund first = requestRefund(w, p, 4000L, "r-1");
        refundEvent(w, p, first, "succeeded");
        assertThatThrownBy(() -> requestRefund(w, p, 7000L, "r-2")).isInstanceOf(ConflictException.class);
        Refund rest = requestRefund(w, p, null, "r-3");

        assertThat(rest.amount()).as("omitting the amount refunds what is left").isEqualTo(Money.of(6000, p.amount().currency()));
        refundEvent(w, p, rest, "succeeded");
        assertThat(reload(w, p).refunded()).isEqualTo(p.amount());
        assertThat(balance(w)).isZero();
        assertThatThrownBy(() -> requestRefund(w, p, 1L, "r-4")).isInstanceOf(ConflictException.class);
    }

    @Test
    void refundsInFlightCountAgainstTheCapturedAmount() {
        PayWorld w = worlds.direct(0);
        Payment p = settledPayment(w, 10_000);

        requestRefund(w, p, 6000L, "r-1");

        assertThatThrownBy(() -> requestRefund(w, p, 4001L, "r-2")).isInstanceOf(ConflictException.class);
        assertThat(requestRefund(w, p, 4000L, "r-3").status()).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void aRefundBeyondTheUnspentBalanceIsRefusedBeforeStripeIsAsked() {
        PayWorld w = worlds.direct(0);
        Payment p = settledPayment(w, 5000);
        scopes.inTenant(w.money().tenant().id(), () -> charge.charge(new ChargeCommand(w.money().wallet().id(), null,
                Money.of(3000, p.amount().currency()), "usage-1", null, "spend-1")));

        assertThatThrownBy(() -> requestRefund(w, p, null, "r-1")).isInstanceOf(ConflictException.class).hasMessageContaining("unspent");

        assertThat(stripe.refunds).isEmpty();
        assertThat(owner.count("select count(*) from refunds where tenant_id = ?", w.money().tenant().id().value())).isZero();
        assertThat(held(w)).isZero();
        assertThat(requestRefund(w, p, 2000L, "r-2").status()).as("what is left unspent can be refunded").isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void theBalanceHeldForARefundCannotBeSpentMeanwhile() {
        PayWorld w = worlds.direct(0);
        Payment p = settledPayment(w, 5000);
        requestRefund(w, p, null, "r-1");

        var attempt = scopes.inTenant(w.money().tenant().id(), () -> charge.charge(new ChargeCommand(w.money().wallet().id(), null,
                Money.of(1, p.amount().currency()), "usage-2", null, "spend-2")));

        assertThat(attempt.status()).isEqualTo(ChargeStatus.FAILED);
    }

    @Test
    void aFailedRefundReleasesTheHold() {
        PayWorld w = worlds.direct(0);
        Payment p = settledPayment(w, 5000);
        Refund requested = requestRefund(w, p, null, "r-1");

        refundEvent(w, p, requested, "failed");

        assertThat(reloadRefund(w, requested).status()).isEqualTo(RefundStatus.FAILED);
        assertThat(held(w)).isZero();
        assertThat(balance(w)).isEqualTo(5000);
        assertThat(reload(w, p).refunded().isZero()).isTrue();
        assertThat(requestRefund(w, p, null, "r-2").status()).as("the full amount can be tried again").isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void stripeRefusingARefundReleasesTheHoldAndRecordsTheFailure() {
        PayWorld w = worlds.direct(0);
        Payment p = settledPayment(w, 5000);
        stripe.failNextRefund = new ProviderRejectedException("Stripe rejected the request (charge_already_refunded)");

        assertThatThrownBy(() -> requestRefund(w, p, null, "r-1")).isInstanceOf(ProviderRejectedException.class);

        assertThat(held(w)).isZero();
        assertThat(owner.count("select count(*) from refunds where tenant_id = ? and status = 'FAILED'", w.money().tenant().id().value())).isEqualTo(1);
    }

    @Test
    void anUnreachableStripeLeavesTheRefundPendingAndTheSameKeyFinishesIt() {
        PayWorld w = worlds.direct(0);
        Payment p = settledPayment(w, 5000);
        stripe.failNextRefund = FakePaymentProvider.unreachable();

        assertThatThrownBy(() -> requestRefund(w, p, null, "r-1")).isInstanceOf(ProviderUnavailableException.class);
        assertThat(owner.count("select count(*) from refunds where tenant_id = ? and status = 'PENDING' and stripe_refund_id is null",
                w.money().tenant().id().value())).isEqualTo(1);
        assertThat(held(w)).isEqualTo(5000);

        Refund finished = requestRefund(w, p, null, "r-1");

        assertThat(finished.stripeRefundId()).isNotNull();
        assertThat(owner.count("select count(*) from refunds where tenant_id = ?", w.money().tenant().id().value())).isEqualTo(1);
        assertThat(held(w)).as("still one hold, not two").isEqualTo(5000);
    }

    @Test
    void aRefundIsIdempotent() {
        PayWorld w = worlds.direct(0);
        Payment p = settledPayment(w, 5000);

        Refund first = requestRefund(w, p, 1000L, "same");
        Refund second = requestRefund(w, p, 1000L, "same");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(stripe.refunds).hasSize(1);
        assertThat(held(w)).isEqualTo(1000);
        assertThatThrownBy(() -> requestRefund(w, p, 2000L, "same")).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void aRefundArrivingWhileTheFirstIsUncommittedWaitsOnThePaymentAndReplaysIt() throws Exception {
        PayWorld w = worlds.direct(0);
        Payment p = settledPayment(w, 5000);
        var tenant = w.money().tenant().id();
        var command = new RequestRefundUseCase.Command(p.id(), null, "requested", "held-refund", null, AUDIT);

        var race = UncommittedRace.run(unitOfWork, scopes, owner, tenant, "%from %\"payments\"%for update%",
                () -> scopes.inTenant(tenant, () -> refund.execute(command)), () -> scopes.inTenant(tenant, () -> refund.execute(command)));

        assertThat(race.first().replayed()).isFalse();
        assertThat(race.second().replayed()).as("the key is read after the payment lock, so the refund is found").isTrue();
        assertThat(race.second().refund().id()).isEqualTo(race.first().refund().id());
        assertThat(owner.count("select count(*) from refunds where tenant_id = ?", tenant.value())).isEqualTo(1);
        assertThat(held(w)).as("one hold").isEqualTo(5000);
    }

    @Test
    void concurrentRefundsWithOneKeyCreateOneRefundAndOneHold() throws Exception {
        PayWorld w = worlds.direct(0);
        Payment p = settledPayment(w, 5000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<Refund>> futures = IntStream.range(0, 8)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> requestRefund(w, p, null, "parallel-refund"), pool))
                    .toList();
            Set<UUID> ids = new HashSet<>();
            for (var future : futures) {
                ids.add(future.get(30, TimeUnit.SECONDS).id().value());
            }
            assertThat(ids).hasSize(1);
            assertThat(owner.count("select count(*) from refunds where tenant_id = ?", w.money().tenant().id().value())).isEqualTo(1);
            assertThat(held(w)).as("one hold, not eight").isEqualTo(5000);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theSameRefundKeyOnTwoPaymentsConcurrentlyConflicts() throws Exception {
        PayWorld w = worlds.direct(0);
        Payment first = settledPayment(w, 5000);
        Payment second = settledPayment(w, 5000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            CompletableFuture<Object> one = CompletableFuture.supplyAsync(() -> attempt(start, () -> requestRefund(w, first, null, "cross-key")), pool);
            CompletableFuture<Object> two = CompletableFuture.supplyAsync(() -> attempt(start, () -> requestRefund(w, second, null, "cross-key")), pool);
            start.countDown();

            Object a = one.get(30, TimeUnit.SECONDS);
            Object b = two.get(30, TimeUnit.SECONDS);
            assertThat(List.of(a, b)).filteredOn(Refund.class::isInstance).as("one request wins").hasSize(1);
            assertThat(List.of(a, b)).filteredOn(IdempotencyConflictException.class::isInstance).as("the other conflicts").hasSize(1);
            assertThat(owner.count("select count(*) from refunds where tenant_id = ?", w.money().tenant().id().value())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void onlyASucceededPaymentCanBeRefunded() {
        PayWorld w = worlds.direct(0);
        Payment open = startTopUp(w, 5000);

        assertThatThrownBy(() -> requestRefund(w, open, null, "r-1")).isInstanceOf(ConflictException.class);
    }

    @Test
    void aRefundMadeInTheStripeDashboardIsFlaggedNotSilentlyApplied() {
        PayWorld w = worlds.direct(0);
        Payment p = settledPayment(w, 5000);

        deliver(w, StripeEvents.refund(StripeEvents.newEventId(), null, "re_dashboard", p.paymentIntentId(), null, "succeeded", 1000));

        assertThat(balance(w)).as("Billing does not guess which wallet movement a foreign refund implies").isEqualTo(5000);
        assertThat(scopes.inTenant(w.money().tenant().id(), () -> incidentQueries.list(ListQueries.of(IncidentListing.SCHEMA,
                "filter[resolved_at][is_null]", "true", "limit", "10")).data())).extracting(i -> i.kind().name())
                .contains("RECONCILIATION_MISMATCH");
    }
}
