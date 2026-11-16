package dev.ngb.billing.payment.application.refund.service;

import dev.ngb.core.names.AggregateTargets;
import dev.ngb.core.names.IdempotencyKeys;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.audit.AuditPreamble;
import dev.ngb.core.idempotency.Fingerprint;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.billing.customer.api.lookup.CustomerLookup;
import dev.ngb.billing.customer.api.lookup.CustomerVisibility;
import dev.ngb.billing.money.api.reservation.ReservationResult;
import dev.ngb.billing.money.api.reservation.ReserveBudget;
import dev.ngb.billing.money.api.reservation.ReserveBudgetCommand;
import dev.ngb.billing.money.api.wallet.InsufficientFundsException;
import dev.ngb.billing.payment.application.payment.port.PaymentRepository;
import dev.ngb.billing.payment.application.payment.service.PaymentSettings;
import dev.ngb.billing.payment.application.refund.port.RefundRepository;
import dev.ngb.billing.payment.domain.payment.Payment;
import dev.ngb.billing.payment.domain.payment.PaymentId;
import dev.ngb.billing.payment.domain.payment.PaymentStatus;
import dev.ngb.billing.payment.domain.refund.Refund;
import dev.ngb.billing.payment.domain.refund.RefundId;
import dev.ngb.billing.payment.domain.stripe.StripeConfiguration;
import dev.ngb.billing.sharedkernel.idempotency.ReplayIdentity;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.NotFoundException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The local half of a refund request: what may be refunded and how much, the hold that protects the unspent
 * balance, the refund row and its audit entry. It must run inside the caller's transaction, and it never calls the
 * provider — {@link RefundGateway} does that outside the lock.
 */
public class RefundRequests {

    /** {@code caller} is the acting Tenant System, or null for a Tenant User. */
    public record Request(PaymentId paymentId, Money amount, String reason, UUID caller, Audit audit) {}

    /** The refund as it now stands, and the payment and configuration the caller will ask the provider with. */
    public record Prepared(Refund refund, Payment payment, StripeConfiguration configuration, boolean replayed) {}

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final ReserveBudget reserve;
    private final RefundGateway gateway;
    private final CustomerVisibility visibility;
    private final PaymentSettings settings;
    private final AuditPreamble audit;
    private final IdGenerator ids;
    private final Clock clock;

    public RefundRequests(
            PaymentRepository payments, RefundRepository refunds, ReserveBudget reserve, RefundGateway gateway, CustomerLookup customers,
            PaymentSettings settings, AuditPreamble audit, IdGenerator ids, Clock clock) {
        this.payments = Objects.requireNonNull(payments, "payments");
        this.refunds = Objects.requireNonNull(refunds, "refunds");
        this.reserve = Objects.requireNonNull(reserve, "reserve");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.visibility = new CustomerVisibility(Objects.requireNonNull(customers, "customers"));
        this.settings = Objects.requireNonNull(settings, "settings");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Prepared prepare(Request request, String key, String hash) {
        // Lock order is payment, then refund, then wallet, everywhere. The key is read after the payment lock,
        // so a concurrent duplicate waits here and then sees the refund the first request wrote.
        Payment payment = payments.lockById(request.paymentId()).orElseThrow(() -> new NotFoundException("payment not found"));
        visibility.requireVisible(payment.customerId(), request.caller(), () -> new NotFoundException("payment not found"));
        Optional<Refund> existing = refunds.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            Fingerprint.requireSameRequest("refund", key, existing.get().requestHash(), hash);
            return new Prepared(existing.get(), payment, gateway.usable(payment), true);
        }
        if (payment.status() != PaymentStatus.SUCCEEDED) {
            throw new ConflictException("only a succeeded payment can be refunded; this one is " + payment.status());
        }
        if (payment.isForInvoice()) {
            // Refunding it would bring the paid-off debt back while the invoice stays paid.
            throw new ConflictException("a payment of an invoice cannot be refunded through Billing");
        }
        if (payment.paymentIntentId() == null) {
            throw new ConflictException("the payment has no Stripe payment to refund");
        }
        StripeConfiguration configuration = gateway.usable(payment);
        Money inFlight = Money.of(refunds.liveAmount(payment.id()), payment.amount().currency());
        Money remaining = payment.amount().minus(inFlight);
        Money amount = request.amount() == null ? remaining : request.amount();
        if (!amount.currency().equals(payment.amount().currency())) {
            throw new IllegalArgumentException("the refund must be in " + payment.amount().currency());
        }
        if (!amount.isPositive() || amount.isGreaterThan(remaining)) {
            throw new ConflictException("at most " + remaining + " of this payment can still be refunded");
        }
        Instant now = clock.now();
        RefundId id = new RefundId(ids.newId());
        ReservationResult hold;
        try {
            hold = reserve.reserve(new ReserveBudgetCommand(payment.walletId(), amount, settings.refundHold(), AggregateTargets.refund(id),
                    IdempotencyKeys.refundHold(id), null));
        } catch (InsufficientFundsException unspent) {
            throw new ConflictException("only the unspent balance of a top-up can be refunded; the wallet cannot cover " + amount);
        }
        Refund refund = Refund.request(id, payment, amount, request.reason(), hold.id().value(), request.audit().actor(),
                new ReplayIdentity(key, hash), now);
        refunds.add(refund);
        audit.record(request.audit(), "refund.requested", AggregateTargets.refund(id), now);
        return new Prepared(refund, payment, configuration, false);
    }
}
