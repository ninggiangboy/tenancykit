package dev.ngb.billing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.billing.payment.PaymentTestWorld.PayWorld;
import dev.ngb.billing.payment.application.incident.query.IncidentListing;
import dev.ngb.billing.payment.application.payment.port.PaymentProvider;
import dev.ngb.billing.payment.application.stripe.command.ReconcileStripeUseCase;
import dev.ngb.billing.payment.application.stripe.query.StripeEventListing;
import dev.ngb.billing.payment.domain.payment.Payment;
import dev.ngb.billing.payment.domain.payment.PaymentStatus;
import dev.ngb.billing.payment.domain.refund.Refund;
import dev.ngb.billing.payment.domain.refund.RefundStatus;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.support.EventSchemas;
import dev.ngb.billing.support.ListQueries;
import dev.ngb.billing.support.StripeEvents;
import dev.ngb.core.error.NotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Reconciliation against Stripe's own view, isolation between tenants, and the published event contract: a lost
 * webhook is applied through the same verified path, a mismatch is flagged and never undone, and another tenant's
 * rows stay invisible.
 */
class ReconciliationIntegrationTest extends PaymentWorld {

    @Test
    void reconciliationCarriesOnPastAnItemItCannotExamine() {
        PayWorld w = worlds.direct(0);
        Payment unreadable = startTopUp(w, 900);
        Payment fine = startTopUp(w, 5000);
        stripe.recentCheckouts.add(new PaymentProvider.Checkout(unreadable.checkoutSessionId(), "pi_unknown_to_stripe" + sfx, unreadable.id().toString(),
                PaymentProvider.CheckoutStatus.COMPLETE, PaymentProvider.CheckoutPayment.UNPAID, 900, "usd"));
        stripe.recentCheckouts.add(new PaymentProvider.Checkout(fine.checkoutSessionId(), "pi_fine" + sfx, fine.id().toString(),
                PaymentProvider.CheckoutStatus.COMPLETE, PaymentProvider.CheckoutPayment.PAID, 5000, "usd"));

        ReconcileStripeUseCase.Report report = scopes.inTenant(w.money().tenant().id(), reconcile::runOnce);

        assertThat(report.corrected()).isEqualTo(1);
        assertThat(balance(w)).isEqualTo(5000);
        assertThat(scopes.inTenant(w.money().tenant().id(), () -> incidentQueries.list(ListQueries.of(IncidentListing.SCHEMA,
                "filter[resolved_at][is_null]", "true", "limit", "10")).data())).extracting(i -> i.subject())
                .anyMatch(subject -> subject.startsWith("checkout/" + unreadable.checkoutSessionId()));
    }

    @Test
    void reconciliationAppliesAPaymentWhoseWebhookWasLostThroughTheVerifiedPath() {
        PayWorld w = worlds.direct(0);
        Payment p = startTopUp(w, 5000);
        stripe.recentCheckouts.add(new PaymentProvider.Checkout(p.checkoutSessionId(), "pi_lost" + sfx, p.id().toString(),
                PaymentProvider.CheckoutStatus.COMPLETE, PaymentProvider.CheckoutPayment.PAID, 5000, "usd"));

        ReconcileStripeUseCase.Report report = scopes.inTenant(w.money().tenant().id(), reconcile::runOnce);

        assertThat(report.corrected()).isEqualTo(1);
        assertThat(reload(w, p).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(balance(w)).isEqualTo(5000);
        assertThat(outbox(w, "payment.succeeded")).isEqualTo(1);
        assertThat(scopes.inTenant(w.money().tenant().id(), () -> incidentQueries.list(ListQueries.of(IncidentListing.SCHEMA,
                "filter[resolved_at][is_null]", "true", "limit", "10")).data())).hasSize(1);

        ReconcileStripeUseCase.Report again = scopes.inTenant(w.money().tenant().id(), reconcile::runOnce);
        assertThat(again.corrected()).isZero();
        assertThat(balance(w)).as("a second run changes nothing").isEqualTo(5000);
    }

    @Test
    void reconciliationFlagsButNeverUndoesAPaymentStripeDoesNotConfirm() {
        PayWorld w = worlds.direct(0);
        Payment p = settledPayment(w, 5000);
        stripe.recentCheckouts.add(new PaymentProvider.Checkout(p.checkoutSessionId(), p.paymentIntentId(), p.id().toString(),
                PaymentProvider.CheckoutStatus.EXPIRED, PaymentProvider.CheckoutPayment.UNPAID, 5000, "usd"));

        ReconcileStripeUseCase.Report report = scopes.inTenant(w.money().tenant().id(), reconcile::runOnce);

        assertThat(report.mismatches()).isEqualTo(1);
        assertThat(report.corrected()).isZero();
        assertThat(balance(w)).isEqualTo(5000);
        assertThat(reload(w, p).status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void reconciliationFinishesARefundWhoseWebhookWasLost() {
        PayWorld w = worlds.direct(0);
        Payment p = settledPayment(w, 5000);
        Refund requested = requestRefund(w, p, 2000L, "r-1");
        stripe.recentRefunds.add(new PaymentProvider.RefundObject(requested.stripeRefundId(), p.paymentIntentId(), requested.id().toString(),
                PaymentProvider.RefundState.SUCCEEDED, 2000, "usd", null));

        ReconcileStripeUseCase.Report report = scopes.inTenant(w.money().tenant().id(), reconcile::runOnce);

        assertThat(report.corrected()).isEqualTo(1);
        assertThat(reloadRefund(w, requested).status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(balance(w)).isEqualTo(3000);
        assertThat(held(w)).isZero();
    }

    @Test
    void reconciliationLeavesAlonePaymentsThatAreNotBillingsOwn() {
        PayWorld w = worlds.direct(0);
        stripe.recentCheckouts.add(new PaymentProvider.Checkout("cs_other_app", "pi_other" + sfx, UUID.randomUUID().toString(),
                PaymentProvider.CheckoutStatus.COMPLETE, PaymentProvider.CheckoutPayment.PAID, 100, "usd"));
        stripe.recentCheckouts.add(new PaymentProvider.Checkout("cs_no_ref", null, null, PaymentProvider.CheckoutStatus.COMPLETE,
                PaymentProvider.CheckoutPayment.PAID, 100, "usd"));

        ReconcileStripeUseCase.Report report = scopes.inTenant(w.money().tenant().id(), reconcile::runOnce);

        assertThat(report.corrected()).isZero();
        assertThat(report.mismatches()).isZero();
        assertThat(balance(w)).isZero();
    }

    private static dev.ngb.core.query.ListQuery everything() {
        return dev.ngb.core.query.QueryParser.parse(dev.ngb.billing.payment.application.payment.query.PaymentListing.SCHEMA, java.util.Map.of());
    }

    @Test
    void aTenantCannotSeeAnotherTenantsPaymentsOrEvents() {
        PayWorld a = worlds.direct(0);
        PayWorld b = worlds.direct(0);
        Payment p = startTopUp(a, 5000);
        paid(a, p, StripeEvents.newEventId(), "pi_1" + sfx);

        assertThat(scopes.inTenant(b.money().tenant().id(), () -> queries.list(everything(), null).data())).isEmpty();
        assertThat(scopes.inTenant(b.money().tenant().id(), () -> eventQueries.list(
                ListQueries.of(StripeEventListing.SCHEMA, "limit", "50")).data())).isEmpty();
        assertThatThrownBy(() -> scopes.inTenant(b.money().tenant().id(), () -> queries.payment(p.id().value())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void thePaymentEventsMatchTheirPublishedSchemas() {
        PayWorld w = worlds.direct(0);
        Payment ok = startTopUp(w, 5000);
        paid(w, ok, StripeEvents.newEventId(), "pi_ok" + sfx);
        Payment bad = startTopUp(w, 700);
        deliver(w, StripeEvents.checkout(StripeEvents.newEventId(), "checkout.session.async_payment_failed", null, bad.checkoutSessionId(), "pi_bad" + sfx,
                bad.id().toString(), "complete", "unpaid", 700, "usd"));
        Refund r = requestRefund(w, ok, 1000L, "r-1");
        refundEvent(w, ok, r, "succeeded");

        Map<String, String> payloads = new LinkedHashMap<>();
        owner.jdbc().query("select type, payload::text as payload from outbox where tenant_id = ? and type in ('payment.succeeded','payment.failed','refund.succeeded')",
                rs -> {
                    payloads.put(rs.getString("type"), rs.getString("payload"));
                }, w.money().tenant().id().value());

        assertThat(payloads).containsOnlyKeys("payment.succeeded", "payment.failed", "refund.succeeded");
        payloads.forEach((type, json) -> assertThat(EventSchemas.validate(type, json)).as(type).isEmpty());
    }
}
