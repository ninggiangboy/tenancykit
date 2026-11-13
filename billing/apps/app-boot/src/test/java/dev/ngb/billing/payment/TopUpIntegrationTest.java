package dev.ngb.billing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.billing.payment.PaymentTestWorld.PayWorld;
import dev.ngb.billing.payment.application.payment.command.CreateTopUpUseCase;
import dev.ngb.billing.payment.application.stripe.exception.ProviderRejectedException;
import dev.ngb.billing.payment.application.stripe.exception.ProviderUnavailableException;
import dev.ngb.billing.payment.domain.payment.Payment;
import dev.ngb.billing.payment.domain.payment.PaymentId;
import dev.ngb.billing.payment.domain.payment.PaymentStatus;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.support.FakePaymentProvider;
import dev.ngb.billing.support.UncommittedRace;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.IdempotencyConflictException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Starting a top-up: one open payment per idempotency key, one Stripe Checkout call, and no balance movement yet. */
class TopUpIntegrationTest extends PaymentWorld {

    @Test
    void aTopUpCreatesAnOpenPaymentAndChangesNoBalance() {
        PayWorld w = worlds.direct(0);

        Payment p = startTopUp(w, 5000);

        assertThat(p.status()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
        assertThat(p.checkoutUrl()).startsWith("https://checkout.stripe.test/");
        assertThat(p.checkoutSessionId()).startsWith("cs_fake_");
        assertThat(balance(w)).isZero();
        assertThat(ledgerEntries(w)).isZero();
        assertThat(stripe.checkouts).hasSize(1);
        assertThat(stripe.checkouts.get(0).request().paymentId()).isEqualTo(p.id());
        assertThat(stripe.checkouts.get(0).access().connectedAccountId()).as("Direct mode charges the ReplicaFixtures.TenantSeed's own account").isNull();
    }

    @Test
    void theSameIdempotencyKeyGivesTheSamePaymentAndCallsStripeOnce() {
        PayWorld w = worlds.direct(0);

        CreateTopUpUseCase.Result first = startTopUp(w, 5000, "same-key");
        CreateTopUpUseCase.Result second = startTopUp(w, 5000, "same-key");

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.payment().id()).isEqualTo(first.payment().id());
        assertThat(stripe.checkouts).hasSize(1);
        assertThat(owner.count("select count(*) from payments where tenant_id = ?", w.money().tenant().id().value())).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRefused() {
        PayWorld w = worlds.direct(0);
        startTopUp(w, 5000, "reused");

        assertThatThrownBy(() -> startTopUp(w, 6000, "reused")).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void aTopUpArrivingWhileTheFirstIsUncommittedWaitsAndReplaysIt() throws Exception {
        PayWorld w = worlds.direct(0);

        var race = UncommittedRace.run(unitOfWork, scopes, owner, w.money().tenant().id(), "insert into %\"payments\"%",
                () -> startTopUp(w, 5000, "held-topup"), () -> startTopUp(w, 5000, "held-topup"));

        assertThat(race.first().replayed()).isFalse();
        assertThat(race.second().replayed()).as("the second insert found the key taken and replayed").isTrue();
        assertThat(race.second().payment().id()).isEqualTo(race.first().payment().id());
        assertThat(race.second().payment().checkoutUrl()).isEqualTo(race.first().payment().checkoutUrl());
        assertThat(owner.count("select count(*) from payments where tenant_id = ?", w.money().tenant().id().value())).isEqualTo(1);
        assertThat(stripe.checkoutSessions.get()).isEqualTo(1);
    }

    @Test
    void concurrentTopUpsWithOneKeyOpenOneCheckout() throws Exception {
        PayWorld w = worlds.direct(0);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<CreateTopUpUseCase.Result>> futures = IntStream.range(0, 8)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> startTopUp(w, 5000, "parallel-topup"), pool))
                    .toList();
            Set<PaymentId> ids = new HashSet<>();
            long firsts = 0;
            long replays = 0;
            for (var future : futures) {
                CreateTopUpUseCase.Result result = future.get(30, TimeUnit.SECONDS);
                ids.add(result.payment().id());
                if (result.replayed()) {
                    replays++;
                } else {
                    firsts++;
                }
            }
            assertThat(ids).hasSize(1);
            assertThat(firsts).isEqualTo(1);
            assertThat(replays).isEqualTo(7);
            assertThat(owner.count("select count(*) from payments where tenant_id = ?", w.money().tenant().id().value())).isEqualTo(1);
            assertThat(stripe.checkoutSessions.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRetryAfterStripeWasUnreachableCompletesTheSamePayment() {
        PayWorld w = worlds.direct(0);
        stripe.failNextCheckout = FakePaymentProvider.unreachable();

        assertThatThrownBy(() -> startTopUp(w, 5000, "retry-me")).isInstanceOf(ProviderUnavailableException.class);
        assertThat(owner.count("select count(*) from payments where tenant_id = ? and checkout_session_id is null", w.money().tenant().id().value()))
                .isEqualTo(1);

        CreateTopUpUseCase.Result retried = startTopUp(w, 5000, "retry-me");

        assertThat(retried.payment().checkoutUrl()).isNotNull();
        assertThat(owner.count("select count(*) from payments where tenant_id = ?", w.money().tenant().id().value())).isEqualTo(1);
        assertThat(stripe.checkouts).as("both calls used the same Stripe idempotency key").extracting(FakePaymentProvider.CheckoutCall::idempotencyKey)
                .containsOnly("topup-" + retried.payment().id());
    }

    @Test
    void stripeRefusingTheCheckoutClosesThePayment() {
        PayWorld w = worlds.direct(0);
        stripe.failNextCheckout = new ProviderRejectedException("Stripe rejected the request (amount_too_small)");

        assertThatThrownBy(() -> startTopUp(w, 5, "tiny")).isInstanceOf(ProviderRejectedException.class);

        assertThat(owner.count("select count(*) from payments where tenant_id = ? and status = 'FAILED'", w.money().tenant().id().value())).isEqualTo(1);
        assertThat(balance(w)).isZero();
    }

    @Test
    void aTopUpInAnotherCurrencyThanTheWalletIsRefused() {
        PayWorld w = worlds.direct(0);
        assertThatThrownBy(() -> scopes.inTenant(w.money().tenant().id(), () -> topUp.execute(new CreateTopUpUseCase.Command(
                w.money().wallet().id(), Money.of(100, Currency.of("EUR")), "k-eur", AUDIT))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTopUpNeedsAConfigurationThatCanTakePayments() {
        PayWorld pending = worlds.direct(0);
        owner.jdbc().update("update stripe_configurations set status = 'PENDING' where id = ?", pending.configuration().id().value());

        assertThatThrownBy(() -> startTopUp(pending, 100, "k-pending")).isInstanceOf(ConflictException.class);
        assertThat(stripe.checkouts).isEmpty();
    }
}
