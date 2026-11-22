package dev.ngb.customer.orders.application.order.command;

import dev.ngb.core.idempotency.Fingerprint;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.customer.customers.api.customer.CustomerDirectory;
import dev.ngb.customer.customers.api.customer.CustomerRef;
import dev.ngb.customer.orders.application.order.exception.CustomerUnknownException;
import dev.ngb.customer.orders.application.order.exception.OrderCustomerErasedException;
import dev.ngb.customer.orders.application.order.port.OrderRepository;
import dev.ngb.customer.orders.application.order.service.OrderEventPublisher;
import dev.ngb.customer.orders.application.order.service.OrderRules;
import dev.ngb.customer.orders.domain.order.Order;
import dev.ngb.customer.orders.domain.order.OrderId;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code PUT /orders/{orderRef}}: the Tenant's POS records an order with its lines, once.
 *
 * <p>The reference is the natural key, so the call is safe to repeat: the same body replays the stored order and
 * announces nothing, while another body under the same reference is a conflict. An order is never edited afterwards —
 * a correction is a return — which is why there is no update path here.
 *
 * <p>The customer is resolved through the customers context: an unknown reference is refused unless the body asks for
 * the customer to be created, and a creation happens in this same transaction, so a refused order leaves no customer
 * behind and an accepted one commits the customer, its announcement and the order together.
 */
public class RecordOrderUseCase {

    /** What the call did: recorded the order, or found the identical one already stored. */
    public enum Outcome {
        CREATED,
        REPLAYED
    }

    /**
     * One line as the Tenant sent it. A null {@code earnEligible} means the default, which is true; a {@code lineNo} is
     * never sent, because a line's number is its position in the order.
     */
    public record Line(
            String sku, String name, String category, BigDecimal quantity, Long amount, Boolean earnEligible) {}

    /** One order as the Tenant sent it, with the reference the call is made under. */
    public record Command(
            String orderRef,
            String externalRef,
            String currency,
            Instant occurredAt,
            String storeCode,
            String channel,
            Long total,
            boolean createCustomer,
            List<Line> lines) {}

    public record Result(Order order, Outcome outcome) {

        public boolean created() {
            return outcome == Outcome.CREATED;
        }
    }

    private final UnitOfWork unitOfWork;
    private final OrderRepository orders;
    private final CustomerDirectory customers;
    private final OrderEventPublisher events;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;
    private final int maxLines;
    private final Duration maxFutureSkew;

    public RecordOrderUseCase(
            UnitOfWork unitOfWork,
            OrderRepository orders,
            CustomerDirectory customers,
            OrderEventPublisher events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock,
            int maxLines,
            Duration maxFutureSkew) {
        this.unitOfWork = unitOfWork;
        this.orders = orders;
        this.customers = customers;
        this.events = events;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
        this.maxLines = maxLines;
        this.maxFutureSkew = maxFutureSkew;
    }

    public Result execute(Command command) {
        Instant now = clock.now();
        OrderRules.Planned planned = OrderRules.plan(command, now, maxLines, maxFutureSkew);
        return unitOfWork.transactional(() -> {
            Optional<Order> existing = orders.findByOrderRef(planned.orderRef());
            if (existing.isPresent()) {
                return replay(existing.get(), planned);
            }
            CustomerRef customer = customer(planned.externalRef(), command.createCustomer());
            Order order = Order.record(
                    new OrderId(ids.newId()),
                    tenantContext.requireTenant(),
                    planned.orderRef(),
                    customer.customerId(),
                    planned.currency(),
                    planned.total(),
                    planned.occurredAt(),
                    planned.storeCode(),
                    planned.channel(),
                    planned.fingerprint(),
                    planned.lines(),
                    now);
            if (!orders.insertIfAbsent(order)) {
                // The insert lost the race with a concurrent first-time record of the same reference; the row that won
                // is the order this call meant to write, so its body decides whether this is a replay or a conflict.
                Order winner = orders
                        .findByOrderRef(planned.orderRef())
                        .orElseThrow(() -> new IllegalStateException(
                                "the order that won the insert of this reference is not readable"));
                return replay(winner, planned);
            }
            events.recorded(order);
            return new Result(order, Outcome.CREATED);
        });
    }

    /** The customer the order belongs to, created with nothing but its reference when the body asks for that. */
    private CustomerRef customer(String externalRef, boolean createCustomer) {
        Optional<CustomerRef> found = customers.findByExternalRef(externalRef);
        if (found.isPresent()) {
            if (found.get().erased()) {
                throw new OrderCustomerErasedException(
                        "The customer of this order was erased; use a new reference for a new customer.");
            }
            return found.get();
        }
        if (!createCustomer) {
            throw new CustomerUnknownException(
                    "No customer exists here with that reference. Send createCustomer to record the order anyway.");
        }
        return customers
                .createIfAbsent(externalRef)
                .filter(ref -> !ref.erased())
                .orElseThrow(() -> new OrderCustomerErasedException(
                        "The customer of this order was erased; use a new reference for a new customer."));
    }

    /** The same reference and the same body: the stored order is the answer, and nothing is written or announced. */
    private static Result replay(Order stored, OrderRules.Planned planned) {
        Fingerprint.requireSameRequest("order", stored.orderRef(), stored.fingerprint(), planned.fingerprint());
        return new Result(stored, Outcome.REPLAYED);
    }
}
