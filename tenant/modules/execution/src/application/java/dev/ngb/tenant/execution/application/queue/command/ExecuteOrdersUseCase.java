package dev.ngb.tenant.execution.application.queue.command;

import dev.ngb.core.names.IdempotencyKeys;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.tenant.billing.api.client.BillingCharges;
import dev.ngb.tenant.billing.api.model.Charge;
import dev.ngb.tenant.billing.api.model.ChargeUsageRequest;
import dev.ngb.tenant.execution.api.queue.ExecuteOrders;
import dev.ngb.tenant.execution.api.queue.ExecuteOrders.Report;
import dev.ngb.tenant.resource.api.execution.ExecutionOrders;
import dev.ngb.tenant.resource.api.execution.ExecutionQueue;
import dev.ngb.tenant.resource.api.order.OrderStatus;
import dev.ngb.tenant.resource.api.order.PricingModel;
import dev.ngb.tenant.usage.api.recording.UsageRecorder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Scenarios 5, 6 and 7: run a claimed order, record usage during the run, charge the cost from the
 * reservation, then release what is left. The order is marked running first, so a failed attempt is
 * requeued from a known state, and the settlement itself is one unit of work: usage, charge, release
 * and the terminal status commit together or not at all. A failure is requeued up to a bound so a
 * Billing outage does not lose the order.
 */
public class ExecuteOrdersUseCase implements ExecuteOrders {

    private final ExecutionQueue queue;
    private final ExecutionOrders orders;
    private final UsageRecorder usage;
    private final BillingCharges charges;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final int maxAttempts;

    public ExecuteOrdersUseCase(
            ExecutionQueue queue,
            ExecutionOrders orders,
            UsageRecorder usage,
            BillingCharges charges,
            UnitOfWork unitOfWork,
            Clock clock,
            int maxAttempts) {
        this.queue = queue;
        this.orders = orders;
        this.usage = usage;
        this.charges = charges;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public Report run(int batchSize) {
        List<ExecutionQueue.Claimed> claimed = queue.claim(batchSize);
        int completed = 0;
        int failed = 0;
        int retried = 0;
        for (ExecutionQueue.Claimed item : claimed) {
            try {
                if (execute(item.orderId())) {
                    completed++;
                } else {
                    failed++;
                }
            } catch (RuntimeException e) {
                if (item.attempts() < maxAttempts) {
                    requeue(item.orderId());
                    retried++;
                } else {
                    fail(item.orderId(), e);
                    failed++;
                }
            }
        }
        return new Report(claimed.size(), completed, failed, retried);
    }

    private void requeue(UUID orderId) {
        unitOfWork.transactional(() -> {
            orders.markQueued(orderId);
            queue.requeue(orderId);
            return null;
        });
    }

    private void fail(UUID orderId, RuntimeException e) {
        String reason = message(e);
        unitOfWork.transactional(() -> {
            orders.markFailed(orderId, reason);
            queue.finish(orderId);
            return null;
        });
    }

    private boolean execute(UUID orderId) {
        ExecutionOrders.ExecutionOrder order = orders.find(orderId).orElse(null);
        if (order == null || order.status() != OrderStatus.QUEUED) {
            queue.finish(orderId);
            return false;
        }
        orders.markRunning(orderId);
        return unitOfWork.transactional(() -> settle(order));
    }

    private boolean settle(ExecutionOrders.ExecutionOrder order) {
        UUID orderId = order.id();
        if (order.pricingModel() != PricingModel.SUBSCRIPTION) {
            usage.record(new UsageRecorder.UsageCommand(
                    order.id(),
                    order.billingCustomerId(),
                    order.metric(),
                    order.usageQuantity(),
                    order.unit(),
                    order.reference(),
                    clock.now()));
        }
        if (order.pricingModel() == PricingModel.HYBRID) {
            rateUsage(order);
        }

        Charge charge = charge(order);
        if (charge != null && "FAILED".equals(charge.status())) {
            release(order);
            orders.markFailed(orderId, charge.failureReason());
            queue.finish(orderId);
            return false;
        }
        release(order);
        orders.markCompleted(orderId);
        queue.finish(orderId);
        return true;
    }

    private void rateUsage(ExecutionOrders.ExecutionOrder order) {
        Instant to = clock.now();
        charges.chargeUsage(new ChargeUsageRequest(
                order.billingCustomerId(),
                order.walletId(),
                order.billingPlanId(),
                null,
                to.minusSeconds(86_400),
                to));
    }

    private Charge charge(ExecutionOrders.ExecutionOrder order) {
        if (order.estimatedMinorUnits() <= 0) {
            return null;
        }
        if (order.reservationId() != null) {
            return charges.capture(
                    "capture:" + order.id(),
                    order.reservationId(),
                    new BillingCharges.CaptureRequest(
                            order.estimatedMinorUnits(), order.currency(), order.reference()));
        }
        return charges.charge(
                IdempotencyKeys.charge(order.id()),
                new BillingCharges.ChargeRequest(
                        order.walletId(), order.estimatedMinorUnits(), order.currency(), order.reference(), order.reference()));
    }

    private void release(ExecutionOrders.ExecutionOrder order) {
        if (order.reservationId() != null) {
            charges.release(order.reservationId());
        }
    }

    private static String message(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() <= 300 ? message : message.substring(0, 300);
    }
}
