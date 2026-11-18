package dev.ngb.billing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.billing.money.MoneyTestWorld.World;
import dev.ngb.billing.payment.PaymentTestWorld.PayWorld;
import dev.ngb.billing.payment.application.incident.query.IncidentListing;
import dev.ngb.billing.payment.application.payment.port.PaymentProvider;
import dev.ngb.billing.payment.application.stripe.command.ReceiveStripeWebhookUseCase;
import dev.ngb.billing.payment.application.stripe.command.ReceiveStripeWebhookUseCase.Disposition;
import dev.ngb.billing.payment.application.stripe.command.RetryStripeEventsUseCase;
import dev.ngb.billing.payment.application.stripe.query.StripeEventListing;
import dev.ngb.billing.payment.application.stripe.query.StripeEventView;
import dev.ngb.billing.payment.domain.payment.Payment;
import dev.ngb.billing.payment.domain.payment.PaymentId;
import dev.ngb.billing.payment.domain.payment.PaymentStatus;
import dev.ngb.billing.payment.domain.refund.Refund;
import dev.ngb.billing.payment.domain.refund.RefundStatus;
import dev.ngb.billing.payment.domain.stripe.EventStatus;
import dev.ngb.billing.payment.domain.stripe.StripeEventId;
import dev.ngb.billing.support.ListQueries;
import dev.ngb.billing.support.StripeEvents;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Verified Stripe results and the retry claims behind them: an event settles a payment once, a result that
 * contradicts a final state needs a person, and an unreadable payment is deferred rather than lost.
 */
class StripeEventIntegrationTest extends PaymentWorld {

    @Test
    void aVerifiedSuccessCreditsTheWalletOnceAndAnnouncesIt() {
        PayWorld w = worlds.direct(0);
        Payment p = startTopUp(w, 5000);

        ReceiveStripeWebhookUseCase.Receipt receipt = paid(w, p, StripeEvents.newEventId(), "pi_1" + sfx);

        assertThat(receipt.disposition()).isEqualTo(Disposition.PROCESSED);
        assertThat(balance(w)).isEqualTo(5000);
        assertThat(ledgerEntries(w)).isEqualTo(1);
        var settled = reload(w, p);
        assertThat(settled.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(settled.ledgerEntryId()).isNotNull();
        assertThat(settled.paymentIntentId()).isEqualTo("pi_1" + sfx);
        assertThat(outbox(w, "payment.succeeded")).isEqualTo(1);
        assertThat(outbox(w, "wallet.credited")).isEqualTo(1);
    }

    @Test
    void replayingTheSameEventCreditsTheWalletOnce() {
        PayWorld w = worlds.direct(0);
        Payment p = startTopUp(w, 5000);
        String eventId = StripeEvents.newEventId();

        assertThat(paid(w, p, eventId, "pi_1" + sfx).disposition()).isEqualTo(Disposition.PROCESSED);
        assertThat(paid(w, p, eventId, "pi_1" + sfx).disposition()).isEqualTo(Disposition.DUPLICATE);
        assertThat(paid(w, p, eventId, "pi_1" + sfx).disposition()).isEqualTo(Disposition.DUPLICATE);

        assertThat(balance(w)).isEqualTo(5000);
        assertThat(ledgerEntries(w)).isEqualTo(1);
        assertThat(outbox(w, "payment.succeeded")).isEqualTo(1);
        assertThat(owner.count("select count(*) from stripe_events where tenant_id = ?", w.money().tenant().id().value())).isEqualTo(1);
    }

    @Test
    void twoDifferentEventsAboutTheSamePaymentStillCreditOnce() {
        PayWorld w = worlds.direct(0);
        Payment p = startTopUp(w, 5000);

        paid(w, p, StripeEvents.newEventId(), "pi_1" + sfx);
        ReceiveStripeWebhookUseCase.Receipt second = deliver(w, StripeEvents.intent(StripeEvents.newEventId(), "payment_intent.succeeded", null, "pi_1" + sfx,
                p.id().toString(), "succeeded", 5000, null, null));

        assertThat(second.disposition()).isEqualTo(Disposition.IGNORED);
        assertThat(balance(w)).isEqualTo(5000);
        assertThat(ledgerEntries(w)).isEqualTo(1);
    }

    @Test
    void anIntentSucceededEventBeforeTheSessionCompletedStillCreditsOnce() {
        PayWorld w = worlds.direct(0);
        Payment p = startTopUp(w, 5000);

        assertThat(deliver(w, StripeEvents.intent(StripeEvents.newEventId(), "payment_intent.succeeded", null, "pi_early" + sfx, p.id().toString(), "succeeded",
                5000, null, null)).disposition()).isEqualTo(Disposition.PROCESSED);
        assertThat(paid(w, p, StripeEvents.newEventId(), "pi_early" + sfx).disposition()).isEqualTo(Disposition.IGNORED);

        assertThat(balance(w)).isEqualTo(5000);
        assertThat(ledgerEntries(w)).isEqualTo(1);
    }

    @Test
    void aFailureReportedAfterSuccessLeavesThePaymentSucceeded() {
        PayWorld w = worlds.direct(0);
        Payment p = startTopUp(w, 5000);
        paid(w, p, StripeEvents.newEventId(), "pi_1" + sfx);

        ReceiveStripeWebhookUseCase.Receipt late = deliver(w, StripeEvents.intent(StripeEvents.newEventId(), "payment_intent.payment_failed", null, "pi_1" + sfx,
                p.id().toString(), "requires_payment_method", 5000, "insufficient_funds", "Your card has insufficient funds."));

        assertThat(late.disposition()).isEqualTo(Disposition.IGNORED);
        assertThat(reload(w, p).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(balance(w)).isEqualTo(5000);
        assertThat(outbox(w, "payment.failed")).isZero();
    }

    @Test
    void aSuccessContradictingAFailedPaymentIsNotAppliedAndNeedsAPerson() {
        PayWorld w = worlds.direct(0);
        Payment p = startTopUp(w, 5000);
        deliver(w, StripeEvents.checkout(StripeEvents.newEventId(), "checkout.session.async_payment_failed", null, p.checkoutSessionId(), "pi_1" + sfx,
                p.id().toString(), "complete", "unpaid", 5000, "usd"));
        assertThat(reload(w, p).status()).isEqualTo(PaymentStatus.FAILED);

        ReceiveStripeWebhookUseCase.Receipt contradiction = paid(w, p, StripeEvents.newEventId(), "pi_1" + sfx);

        assertThat(contradiction.disposition()).isEqualTo(Disposition.DEAD);
        assertThat(reload(w, p).status()).as("a final state never regresses").isEqualTo(PaymentStatus.FAILED);
        assertThat(balance(w)).isZero();
        assertThat(owner.count("select count(*) from stripe_incidents where tenant_id = ? and kind = 'EVENT_REJECTED' and resolved_at is null",
                w.money().tenant().id().value())).isEqualTo(1);
    }

    @Test
    void anEventForAPaymentNotYetStoredWaitsAndIsAppliedOnceItExists() {
        PayWorld w = worlds.direct(0);
        PaymentId future = new PaymentId(UUID.randomUUID());
        String body = StripeEvents.paidCheckout(StripeEvents.newEventId(), null, "cs_ahead", "pi_ahead" + sfx, future.toString(), 3000);

        ReceiveStripeWebhookUseCase.Receipt early = deliver(w, body);

        assertThat(early.disposition()).isEqualTo(Disposition.DEFERRED);
        assertThat(balance(w)).isZero();
        assertThat(owner.count("select count(*) from stripe_events where tenant_id = ? and status = 'FAILED' and attempts = 1", w.money().tenant().id().value()))
                .isEqualTo(1);

        worlds.openPayment(w, future, 3000);
        owner.jdbc().update("update stripe_events set next_attempt_at = now() - interval '1 second' where tenant_id = ?", w.money().tenant().id().value());
        RetryStripeEventsUseCase.Round round = scopes.inTenant(w.money().tenant().id(), retry::runOnce);

        assertThat(round.processed()).isEqualTo(1);
        assertThat(balance(w)).isEqualTo(3000);
        assertThat(owner.count("select count(*) from stripe_event_attempts where tenant_id = ?", w.money().tenant().id().value()))
                .as("both attempts are kept as history").isEqualTo(2);
    }

    @Test
    void anEventThatNeverFindsItsPaymentGivesUpAndIsVisible() {
        PayWorld w = worlds.direct(0);
        String body = StripeEvents.paidCheckout(StripeEvents.newEventId(), null, "cs_ghost", "pi_ghost" + sfx, UUID.randomUUID().toString(), 3000);
        deliver(w, body);

        for (int i = 0; i < 3; i++) {
            owner.jdbc().update("update stripe_events set next_attempt_at = now() - interval '1 second' where tenant_id = ? and status = 'FAILED'",
                    w.money().tenant().id().value());
            scopes.inTenant(w.money().tenant().id(), retry::runOnce);
        }

        List<StripeEventView> dead = scopes.inTenant(w.money().tenant().id(), () -> eventQueries.list(
                ListQueries.of(StripeEventListing.SCHEMA, "filter[status][eq]", "DEAD", "limit", "10")).data());
        assertThat(dead).hasSize(1);
        assertThat(dead.get(0).attempts()).isEqualTo(3);
        assertThat(dead.get(0).lastError()).contains("no payment");
        assertThat(scopes.inTenant(w.money().tenant().id(), () -> incidentQueries.list(ListQueries.of(IncidentListing.SCHEMA,
                "filter[resolved_at][is_null]", "true", "limit", "10")).data())).hasSize(1);
    }

    @Test
    void aDeadEventCanBeRetriedByAPersonOnceItsCauseIsFixed() {
        PayWorld w = worlds.direct(0);
        PaymentId late = new PaymentId(UUID.randomUUID());
        deliver(w, StripeEvents.paidCheckout(StripeEvents.newEventId(), null, "cs_late", "pi_late" + sfx, late.toString(), 2000));
        for (int i = 0; i < 2; i++) {
            owner.jdbc().update("update stripe_events set next_attempt_at = now() - interval '1 second' where tenant_id = ? and status = 'FAILED'",
                    w.money().tenant().id().value());
            scopes.inTenant(w.money().tenant().id(), retry::runOnce);
        }
        var dead = scopes.inTenant(w.money().tenant().id(), () -> eventQueries.list(
                ListQueries.of(StripeEventListing.SCHEMA, "filter[status][eq]", "DEAD", "limit", "10")).data());
        assertThat(dead).hasSize(1);

        worlds.openPayment(w, late, 2000);
        scopes.inTenant(w.money().tenant().id(), () -> {
            eventAdmin.retry(AUDIT, new StripeEventId(dead.get(0).id()));
            return null;
        });
        var detail = scopes.inTenant(w.money().tenant().id(), () -> eventQueries.get(dead.get(0).id()));

        assertThat(detail.event().status()).isEqualTo(EventStatus.PROCESSED);
        assertThat(detail.attempts()).extracting(a -> a.outcome()).contains("DEFERRED", "PROCESSED");
        assertThat(balance(w)).isEqualTo(2000);
    }

    @Test
    void aDeclinedAttemptIsRecordedAndThePaymentStaysOpenForAnotherTry() {
        PayWorld w = worlds.direct(0);
        Payment p = startTopUp(w, 5000);

        ReceiveStripeWebhookUseCase.Receipt declined = deliver(w, StripeEvents.intent(StripeEvents.newEventId(), "payment_intent.payment_failed", null,
                "pi_1" + sfx, p.id().toString(), "requires_payment_method", 5000, "insufficient_funds", "Your card has insufficient funds."));

        assertThat(declined.disposition()).isEqualTo(Disposition.PROCESSED);
        var open = reload(w, p);
        assertThat(open.status()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
        assertThat(scopes.inTenant(w.money().tenant().id(), () -> queries.payment(p.id().value()).attempts())).hasSize(1);
        assertThat(outbox(w, "payment.failed")).isZero();

        // The customer tries a second card and it works.
        assertThat(paid(w, p, StripeEvents.newEventId(), "pi_1" + sfx).disposition()).isEqualTo(Disposition.PROCESSED);
        assertThat(balance(w)).isEqualTo(5000);
    }

    @Test
    void anExpiredSessionAfterADeclineFailsThePaymentAndOneWithoutCancelsIt() {
        PayWorld w = worlds.direct(0);
        Payment declined = startTopUp(w, 5000);
        deliver(w, StripeEvents.intent(StripeEvents.newEventId(), "payment_intent.payment_failed", null, "pi_d" + sfx, declined.id().toString(),
                "requires_payment_method", 5000, "card_declined", "Your card was declined."));
        Payment abandoned = startTopUp(w, 700);

        deliver(w, StripeEvents.checkout(StripeEvents.newEventId(), "checkout.session.expired", null, declined.checkoutSessionId(), null,
                declined.id().toString(), "expired", "unpaid", 5000, "usd"));
        deliver(w, StripeEvents.checkout(StripeEvents.newEventId(), "checkout.session.expired", null, abandoned.checkoutSessionId(), null,
                abandoned.id().toString(), "expired", "unpaid", 700, "usd"));

        assertThat(reload(w, declined).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(reload(w, declined).failureReason()).isEqualTo("Your card was declined.");
        assertThat(reload(w, abandoned).status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(outbox(w, "payment.failed")).isEqualTo(1);
        assertThat(balance(w)).isZero();
    }

    @Test
    void aDelayedPaymentMethodIsProcessingUntilItSettles() {
        PayWorld w = worlds.direct(0);
        Payment p = startTopUp(w, 5000);
        stripe.intents.put("pi_bank" + sfx, new PaymentProvider.Intent("pi_bank" + sfx, p.id().toString(), PaymentProvider.IntentState.PROCESSING, 5000, "usd", null, null));

        deliver(w, StripeEvents.checkout(StripeEvents.newEventId(), "checkout.session.completed", null, p.checkoutSessionId(), "pi_bank" + sfx, p.id().toString(),
                "complete", "unpaid", 5000, "usd"));
        assertThat(reload(w, p).status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(balance(w)).isZero();

        deliver(w, StripeEvents.checkout(StripeEvents.newEventId(), "checkout.session.async_payment_succeeded", null, p.checkoutSessionId(), "pi_bank" + sfx,
                p.id().toString(), "complete", "paid", 5000, "usd"));
        assertThat(reload(w, p).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(balance(w)).isEqualTo(5000);
    }

    @Test
    void aDelayedPaymentThatFailsFailsThePaymentWithoutCrediting() {
        PayWorld w = worlds.direct(0);
        Payment p = startTopUp(w, 5000);

        deliver(w, StripeEvents.checkout(StripeEvents.newEventId(), "checkout.session.async_payment_failed", null, p.checkoutSessionId(), "pi_bank" + sfx,
                p.id().toString(), "complete", "unpaid", 5000, "usd"));

        assertThat(reload(w, p).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(balance(w)).isZero();
        assertThat(outbox(w, "payment.failed")).isEqualTo(1);
    }

    @Test
    void anAmountThatDiffersFromTheRequestedOneCreditsNothing() {
        PayWorld w = worlds.direct(0);
        Payment p = startTopUp(w, 5000);

        ReceiveStripeWebhookUseCase.Receipt receipt = deliver(w, StripeEvents.paidCheckout(StripeEvents.newEventId(), null, p.checkoutSessionId(), "pi_1" + sfx,
                p.id().toString(), 9999));

        assertThat(receipt.disposition()).isEqualTo(Disposition.DEAD);
        assertThat(balance(w)).isZero();
        assertThat(reload(w, p).status()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
    }

    @Test
    void anEventNamingAPaymentOfAnotherTenantCannotTouchIt() {
        PayWorld attacker = worlds.direct(0);
        PayWorld victim = worlds.direct(0);
        Payment victimPayment = startTopUp(victim, 5000);

        // Validly signed for the attacker's own endpoint, but naming the victim's payment.
        ReceiveStripeWebhookUseCase.Receipt receipt = deliver(attacker, StripeEvents.paidCheckout(StripeEvents.newEventId(), null, "cs_x", "pi_x" + sfx,
                victimPayment.id().toString(), 5000));

        assertThat(receipt.disposition()).isEqualTo(Disposition.DEFERRED);
        assertThat(balance(victim)).isZero();
        assertThat(reload(victim, victimPayment).status()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
    }

    @Test
    void aDisconnectedConfigurationStillSettlesWhatItStarted() {
        PayWorld w = worlds.direct(0);
        Payment p = startTopUp(w, 5000);
        scopes.runInTenant(w.money().tenant().id(), () -> configService.disconnect(AUDIT));

        // The customer finishes paying on a Checkout page that was still open.
        assertThat(paid(w, p, StripeEvents.newEventId(), "pi_late" + sfx).disposition()).isEqualTo(Disposition.PROCESSED);

        assertThat(balance(w)).isEqualTo(5000);
        assertThat(scopes.inTenant(w.money().tenant().id(), () -> configService.current())).isEmpty();
    }

    @Test
    void anotherTenantCannotTakeAnAccountWhosePaymentsAreStillInFlight() {
        PayWorld w = worlds.direct(0);
        scopes.runInTenant(w.money().tenant().id(), () -> configService.disconnect(AUDIT));
        PayWorld other = worlds.direct(0);
        String keyForSameAccount = "sk_test_other_tenant_" + w.configuration().accountId().substring(w.configuration().accountId().length() - 8);
        stripe.accountOverride = w.configuration().accountId();

        assertThatThrownBy(() -> scopes.inTenant(other.money().tenant().id(), () -> configService.connectDirect(AUDIT, keyForSameAccount, null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void replacingTheKeyOfTheSameAccountKeepsPaymentsAndRefundsWorking() {
        World money = worlds.tenantWithoutStripe(0);
        String account = "acct_fake_ROTATE" + sfx.substring(1, 3);
        PayWorld first = worlds.directFor(money, account);
        Payment p = startTopUp(first, 5000);
        String rotatedKey = "sk_test_rotated_key_" + account.substring(account.length() - 8);
        var second = scopes.inTenant(money.tenant().id(), () -> configService.connectDirect(AUDIT, rotatedKey, null, "whsec_second" + sfx));
        PayWorld rotated = new PayWorld(money, second, "whsec_second" + sfx);

        assertThat(second.id()).isNotEqualTo(first.configuration().id());
        assertThatThrownBy(() -> paid(first, p, StripeEvents.newEventId(), "pi_x" + sfx)).as("the old endpoint is gone")
                .isInstanceOf(NotFoundException.class);
        assertThat(paid(rotated, p, StripeEvents.newEventId(), "pi_rot" + sfx).disposition()).as("the new endpoint settles the old payment")
                .isEqualTo(Disposition.PROCESSED);
        assertThat(balance(rotated)).isEqualTo(5000);

        Refund requested = requestRefund(rotated, p, 1000L, "rot-refund");
        assertThat(requested.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(stripe.refunds.get(0).access().secretKey()).as("refunded through the current key").isEqualTo(rotatedKey);
    }

    @Test
    void stripeActivityBillingDidNotStartIsIgnoredWithoutRetriesOrIncidents() {
        PayWorld w = worlds.direct(0);

        var session = deliver(w, StripeEvents.checkout(StripeEvents.newEventId(), "checkout.session.completed", null, "cs_other_app", "pi_other_app" + sfx, null,
                "complete", "paid", 100, "usd"));
        var intent = deliver(w, StripeEvents.intent(StripeEvents.newEventId(), "payment_intent.succeeded", null, "pi_foreign" + sfx, null, "succeeded", 100,
                null, null));
        var refund = deliver(w, StripeEvents.refund(StripeEvents.newEventId(), null, "re_foreign", "pi_foreign" + sfx, null, "succeeded", 100));

        assertThat(List.of(session.disposition(), intent.disposition(), refund.disposition())).containsOnly(Disposition.IGNORED);
        assertThat(owner.count("select count(*) from stripe_events where tenant_id = ? and status = 'IGNORED'", w.money().tenant().id().value())).isEqualTo(3);
        assertThat(owner.count("select count(*) from stripe_incidents where tenant_id = ?", w.money().tenant().id().value())).isZero();
        assertThat(balance(w)).isZero();
    }

    @Test
    void anEventLeftUnprocessedByAStoppedProcessIsPickedUpByTheRetryJob() {
        PayWorld w = worlds.direct(0);
        PaymentId late = new PaymentId(UUID.randomUUID());
        deliver(w, StripeEvents.paidCheckout(StripeEvents.newEventId(), null, "cs_stuck" + sfx, "pi_stuck" + sfx, late.toString(), 1200));
        worlds.openPayment(w, late, 1200);
        owner.jdbc().update("update stripe_events set status = 'RECEIVED', next_attempt_at = null, received_at = now() - interval '10 minutes' "
                + "where tenant_id = ?", w.money().tenant().id().value());

        RetryStripeEventsUseCase.Round round = scopes.inTenant(w.money().tenant().id(), retry::runOnce);

        assertThat(round.processed()).isEqualTo(1);
        assertThat(balance(w)).isEqualTo(1200);
    }

    @Test
    void aClaimedEventIsInvisibleToAnotherWorkerUntilTheClaimPasses() {
        PayWorld w = worlds.direct(0);
        TenantId tenant = w.money().tenant().id();
        for (int i = 0; i < 2; i++) {
            deliver(w, StripeEvents.paidCheckout(StripeEvents.newEventId(), null, "cs_claim" + i + sfx, "pi_claim" + i + sfx,
                    UUID.randomUUID().toString(), 1000));
        }
        makeFailedEventsDue(tenant);

        List<StripeEventId> first = claim(tenant);
        List<StripeEventId> second = claim(tenant);

        assertThat(first).hasSize(2);
        assertThat(second).as("another worker takes nothing while the claim holds").isEmpty();
        assertThat(owner.count("select count(*) from stripe_events where tenant_id = ? and status = 'FAILED' and next_attempt_at <= now()",
                tenant.value())).as("taking a claim does not move the retry schedule").isEqualTo(2);

        owner.jdbc().update("update stripe_events set claimed_until = now() - interval '1 second' where tenant_id = ?", tenant.value());

        assertThat(claim(tenant)).as("a worker that died hands its events back").containsExactlyInAnyOrderElementsOf(first);
    }

    @Test
    void severalWorkersRetryingAtOnceNeverTryTheSameEventTwice() throws Exception {
        PayWorld w = worlds.direct(0);
        TenantId tenant = w.money().tenant().id();
        int events = 12;
        for (int i = 0; i < events; i++) {
            deliver(w, StripeEvents.paidCheckout(StripeEvents.newEventId(), null, "cs_many" + i + sfx, "pi_many" + i + sfx,
                    UUID.randomUUID().toString(), 1000));
        }
        makeFailedEventsDue(tenant);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        int tried = 0;
        try {
            List<CompletableFuture<Integer>> workers = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                workers.add(CompletableFuture.supplyAsync(() -> scopes.inTenant(tenant, retry::runOnce).tried(), pool));
            }
            for (CompletableFuture<Integer> worker : workers) {
                tried += worker.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(tried).as("every event is claimed by exactly one worker").isEqualTo(events);
        assertThat(owner.count("select count(*) from stripe_event_attempts where tenant_id = ?", tenant.value()))
                .as("one stored attempt and exactly one retry attempt per event").isEqualTo(2 * events);
    }
}
