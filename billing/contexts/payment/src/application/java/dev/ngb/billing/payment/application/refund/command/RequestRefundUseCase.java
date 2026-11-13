package dev.ngb.billing.payment.application.refund.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.billing.money.api.reservation.ReservationResult;
import dev.ngb.billing.money.api.reservation.ReserveBudget;
import dev.ngb.billing.money.api.reservation.ReserveBudgetCommand;
import dev.ngb.billing.money.api.wallet.InsufficientFundsException;
import dev.ngb.billing.payment.application.payment.port.PaymentProvider;
import dev.ngb.billing.payment.application.payment.port.PaymentRepository;
import dev.ngb.billing.payment.application.payment.service.PaymentSettings;
import dev.ngb.billing.payment.application.payment.service.PaymentSettlement;
import dev.ngb.billing.payment.application.refund.port.RefundRepository;
import dev.ngb.billing.payment.application.refund.service.RefundGateway;
import dev.ngb.billing.payment.application.stripe.exception.ProviderRejectedException;
import dev.ngb.billing.payment.domain.payment.Payment;
import dev.ngb.billing.payment.domain.payment.PaymentId;
import dev.ngb.billing.payment.domain.payment.PaymentStatus;
import dev.ngb.billing.payment.domain.refund.Refund;
import dev.ngb.billing.payment.domain.refund.RefundId;
import dev.ngb.billing.payment.domain.stripe.StripeConfiguration;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.idempotency.Fingerprint;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.billing.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Optional;

/**
 * Sends money back to the customer's card, in full or in part. Three rules protect the books:
 * refunds never add up to more than was captured; the unspent balance is held before Stripe is asked, so a
 * refund cannot drive a Prepaid wallet negative; and the ledger changes only when Stripe reports the refund
 * succeeded (see {@link PaymentSettlement}), never because the request was accepted.
 */
public class RequestRefundUseCase {

    /** Well inside the 24 hours Stripe remembers an idempotency key for. */
    private static final java.time.Duration RETRY_WINDOW = java.time.Duration.ofHours(12);

    public record Command(PaymentId paymentId, Money amount, String reason, String idempotencyKey, Audit audit) {}

    public record Result(Refund refund, boolean replayed) {}

    private record Prepared(Refund refund, Payment payment, StripeConfiguration configuration, boolean replayed) {}

    private final UnitOfWork unitOfWork;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final ReserveBudget reserve;
    private final PaymentSettlement settlement;
    private final RefundGateway gateway;
    private final PaymentSettings settings;
    private final AuditLog auditLog;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public RequestRefundUseCase(
            UnitOfWork unitOfWork, PaymentRepository payments, RefundRepository refunds, ReserveBudget reserve, PaymentSettlement settlement,
            RefundGateway gateway, PaymentSettings settings, AuditLog auditLog, TenantContext tenantContext, IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.payments = payments;
        this.refunds = refunds;
        this.reserve = reserve;
        this.settlement = settlement;
        this.gateway = gateway;
        this.settings = settings;
        this.auditLog = auditLog;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    /** @param command {@code amount} null means everything still refundable */
    public Result execute(Command command) {
        String key = Fingerprint.requireKey(command.idempotencyKey());
        if (command.amount() != null && !command.amount().isPositive()) {
            throw new IllegalArgumentException("a refund must be positive");
        }
        String hash = Fingerprint.of(command.paymentId().toString(), command.amount() == null ? "all" : command.amount().toString(),
                String.valueOf(command.reason()));
        Prepared prepared = unitOfWork.transactional(() -> prepare(command, key, hash));
        Refund refund = prepared.refund();
        if (refund.stripeRefundId() != null || refund.status().isFinal()) {
            return new Result(refund, prepared.replayed());
        }
        if (prepared.replayed() && refund.createdAt().isBefore(clock.now().minus(RETRY_WINDOW))) {
            // Stripe forgets an idempotency key after a day, so asking again could refund twice. The reconciliation
            // job finds the refund by its reference and settles it; until then there is nothing safe to repeat.
            throw new ConflictException("the outcome of this refund is unknown and will be settled by reconciliation; it cannot be repeated now");
        }
        PaymentProvider.RefundObject created;
        try {
            created = gateway.refund(prepared.configuration(), refund, prepared.payment());
        } catch (ProviderRejectedException rejected) {
            unitOfWork.transactional(() -> {
                Refund locked = refunds.lockById(refund.id()).orElseThrow(() -> new NotFoundException("no refund " + refund.id()));
                settlement.releaseHold(locked);
                locked.fail(rejected.getMessage(), clock.now());
                refunds.update(locked);
            });
            throw rejected;
        }
        // The outcome of an unreachable Stripe is unknown: the refund stays PENDING and the same key retries it safely.
        Refund attached = unitOfWork.transactional(() -> {
            Refund locked = refunds.lockById(refund.id()).orElseThrow(() -> new NotFoundException("no refund " + refund.id()));
            locked.attachStripeRefund(created.refundId(), clock.now());
            refunds.update(locked);
            return locked;
        });
        return new Result(attached, false);
    }

    private Prepared prepare(Command command, String key, String hash) {
        // Lock order is payment, then refund, then wallet, everywhere. The key is read after the payment lock,
        // so a concurrent duplicate waits here and then sees the refund the first request wrote.
        Payment payment = payments.lockById(command.paymentId()).orElseThrow(() -> new NotFoundException("payment not found"));
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
        Money amount = command.amount() == null ? remaining : command.amount();
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
            hold = reserve.reserve(new ReserveBudgetCommand(payment.walletId(), amount, settings.refundHold(), "refund/" + id, "refund-hold:" + id, null));
        } catch (InsufficientFundsException unspent) {
            throw new ConflictException("only the unspent balance of a top-up can be refunded; the wallet cannot cover " + amount);
        }
        Refund refund = Refund.request(id, payment, amount, command.reason(), hold.id().value(), command.audit().actor(), key, hash, now);
        refunds.add(refund);
        auditLog.record(AuditEntry.tenant(tenantContext.requireTenant(), command.audit().actor(), "refund.requested", "refund/" + id,
                command.audit().requestId(), now));
        return new Prepared(refund, payment, configuration, false);
    }
}
