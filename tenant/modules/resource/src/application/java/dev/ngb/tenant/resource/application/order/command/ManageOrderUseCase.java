package dev.ngb.tenant.resource.application.order.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.tenant.billing.api.client.BillingSubscriptions;
import dev.ngb.tenant.customer.api.lookup.CustomerDirectory;
import dev.ngb.tenant.resource.application.order.port.OrderRepository;
import dev.ngb.tenant.resource.domain.order.Order;
import dev.ngb.tenant.resource.domain.order.OrderStatus;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.NotFoundException;
import java.util.UUID;

/**
 * Order changes that reach Billing: cancelling a subscription-backed order keeps its paid period.
 *
 * <p>The remote call stays outside every transaction, like {@link CreateOrderUseCase}: the order first moves to
 * {@code CANCELING} in its own unit of work, then Billing is called, then the order is closed in a second one. Writing
 * the intermediate state first is what makes a crash observable: an order left in {@code CANCELING} is finished by
 * {@link CreateOrderUseCase#reapStaleOrders()}, which retries the cancel. Billing's cancel answers {@code UNCHANGED} for
 * a subscription that is already cancelled, so that retry is safe.
 */
public class ManageOrderUseCase {

    private final OrderRepository orders;
    private final CustomerDirectory customers;
    private final BillingSubscriptions subscriptions;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public ManageOrderUseCase(
            OrderRepository orders,
            CustomerDirectory customers,
            BillingSubscriptions subscriptions,
            UnitOfWork unitOfWork,
            Clock clock) {
        this.orders = orders;
        this.customers = customers;
        this.subscriptions = subscriptions;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
    }

    public Order cancel(UUID orderId, UUID userId, boolean administrator) {
        Order canceling = unitOfWork.transactional(() -> {
            Order order = orders.findById(orderId).orElseThrow(() -> new NotFoundException("order not found"));
            if (!administrator && !order.customerId().equals(customers.byUserId(userId).id())) {
                throw new NotFoundException("order not found");
            }
            if (order.terminal()) {
                throw new BadRequestException("this order is already " + order.status());
            }
            Order next = order.canceling(clock.now());
            orders.save(next);
            return next;
        });

        if (canceling.subscriptionId() != null) {
            try {
                subscriptions.cancel(canceling.subscriptionId());
            } catch (RuntimeException e) {
                recordFailure(orderId, e);
                throw e;
            }
        }

        return unitOfWork.transactional(() -> {
            Order fresh = orders.findById(orderId).orElseThrow(() -> new NotFoundException("order not found"));
            Order canceled = fresh.canceled(clock.now());
            orders.save(canceled);
            return canceled;
        });
    }

    /**
     * Records why the remote cancel failed while leaving the order in {@code CANCELING}: the local row must not look
     * cancelled, because Billing may not have cancelled, and it must not return to its old state either, because
     * Billing may have. The reaper retries from this state.
     */
    private void recordFailure(UUID orderId, RuntimeException cause) {
        unitOfWork.transactional(() -> {
            orders.findById(orderId)
                    .filter(order -> order.status() == OrderStatus.CANCELING)
                    .ifPresent(order -> orders.save(order.cancelFailed(reason(cause), clock.now())));
            return null;
        });
    }

    private static String reason(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() <= 300 ? message : message.substring(0, 300);
    }
}
