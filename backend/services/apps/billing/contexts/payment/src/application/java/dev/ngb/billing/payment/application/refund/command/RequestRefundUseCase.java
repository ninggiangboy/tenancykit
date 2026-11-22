package dev.ngb.billing.payment.application.refund.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.billing.payment.application.payment.port.PaymentProvider;
import dev.ngb.billing.payment.application.payment.service.PaymentSettlement;
import dev.ngb.billing.payment.application.refund.port.RefundRepository;
import dev.ngb.billing.payment.application.refund.service.RefundGateway;
import dev.ngb.billing.payment.application.refund.service.RefundRequests;
import dev.ngb.billing.payment.application.stripe.exception.ProviderRejectedException;
import dev.ngb.billing.payment.domain.payment.PaymentId;
import dev.ngb.billing.payment.domain.refund.Refund;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.idempotency.Fingerprint;
import java.time.Duration;
import java.util.UUID;

/**
 * Sends money back to the customer's card, in full or in part. Three rules protect the books:
 * refunds never add up to more than was captured; the unspent balance is held before Stripe is asked, so a
 * refund cannot drive a Prepaid wallet negative; and the ledger changes only when Stripe reports the refund
 * succeeded (see {@link PaymentSettlement}), never because the request was accepted.
 */
public class RequestRefundUseCase {

    /** Well inside the 24 hours Stripe remembers an idempotency key for. */
    private static final Duration RETRY_WINDOW = Duration.ofHours(12);

    public record Command(PaymentId paymentId, Money amount, String reason, String idempotencyKey, UUID caller, Audit audit) {}

    public record Result(Refund refund, boolean replayed) {}

    private final UnitOfWork unitOfWork;
    private final RefundRepository refunds;
    private final RefundGateway gateway;
    private final RefundRequests requests;
    private final PaymentSettlement settlement;
    private final Clock clock;

    public RequestRefundUseCase(
            UnitOfWork unitOfWork, RefundRepository refunds, RefundGateway gateway, RefundRequests requests, PaymentSettlement settlement,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.refunds = refunds;
        this.gateway = gateway;
        this.requests = requests;
        this.settlement = settlement;
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
        RefundRequests.Prepared prepared = unitOfWork.transactional(() -> requests.prepare(
                new RefundRequests.Request(
                        command.paymentId(), command.amount(), command.reason(), command.caller(), command.audit()), key, hash));
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
}
