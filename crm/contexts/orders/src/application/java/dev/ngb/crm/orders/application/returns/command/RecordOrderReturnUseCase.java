package dev.ngb.crm.orders.application.returns.command;

import dev.ngb.core.error.IdempotencyConflictException;
import dev.ngb.core.idempotency.Fingerprint;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.crm.orders.application.order.exception.OrderNotFoundException;
import dev.ngb.crm.orders.application.order.port.OrderRepository;
import dev.ngb.crm.orders.application.order.service.OrderEventPublisher;
import dev.ngb.crm.orders.application.order.service.OrderRules;
import dev.ngb.crm.orders.application.returns.service.ReturnRules;
import dev.ngb.crm.orders.domain.order.Order;
import dev.ngb.crm.orders.domain.returns.OrderReturn;
import dev.ngb.crm.orders.domain.returns.ReturnId;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code PUT /orders/{orderRef}/returns/{returnRef}}: the Tenant's POS takes part of an order back, once.
 *
 * <p>The order's row is locked first, so two returns of one order run one after the other and neither can take back a
 * unit the other already took. Under that lock the cumulative figures are checked against what was sold, the lines,
 * the status and the order's version move, and one {@code order.refunded} carrying the new state and this return is
 * appended to the outbox in the same transaction.
 *
 * <p>The reference is the natural key of the return and is unique per tenant: the same reference with the same body
 * replays the stored result and moves nothing, a different body is a conflict, and a reference that belongs to another
 * order is a conflict too.
 */
public class RecordOrderReturnUseCase {

    /** What the call did: recorded the return, or found the identical one already stored. */
    public enum Outcome {
        CREATED,
        REPLAYED
    }

    /** One returned line as the Tenant sent it. */
    public record Line(Integer lineNo, BigDecimal quantity, Long amount) {}

    /** The return's body; the order and the return's own reference are the call's path. */
    public record Command(String returnRef, Instant occurredAt, List<Line> lines) {}

    public record Result(Order order, Outcome outcome) {

        public boolean created() {
            return outcome == Outcome.CREATED;
        }
    }

    private final UnitOfWork unitOfWork;
    private final OrderRepository orders;
    private final OrderEventPublisher events;
    private final IdGenerator ids;
    private final Clock clock;
    private final Duration maxFutureSkew;

    public RecordOrderReturnUseCase(
            UnitOfWork unitOfWork,
            OrderRepository orders,
            OrderEventPublisher events,
            IdGenerator ids,
            Clock clock,
            Duration maxFutureSkew) {
        this.unitOfWork = unitOfWork;
        this.orders = orders;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
        this.maxFutureSkew = maxFutureSkew;
    }

    public Result execute(String orderRef, Command command) {
        Instant now = clock.now();
        String ref = OrderRules.reference(orderRef, OrderRules.MAX_ORDER_REF, "order");
        ReturnRules.Planned planned = ReturnRules.plan(ref, command, now, maxFutureSkew);
        return unitOfWork.transactional(() -> {
            Order order = orders.lockByOrderRef(ref).orElseThrow(OrderNotFoundException::new);
            Optional<OrderRepository.StoredReturn> stored = orders.findReturn(planned.returnRef());
            if (stored.isPresent()) {
                return replay(order, stored.get(), planned);
            }
            ReturnRules.againstSale(order, planned.lines());
            OrderReturn recorded = order.applyReturn(
                    new ReturnId(ids.newId()), planned.returnRef(), planned.occurredAt(), planned.lines(), now);
            orders.applyReturn(order, recorded, planned.fingerprint(), now);
            events.refunded(order, recorded);
            return new Result(order, Outcome.CREATED);
        });
    }

    /**
     * The reference was used before: the same body against the same order is a replay of the stored result, and
     * anything else is the same key with a different request.
     */
    private static Result replay(
            Order order, OrderRepository.StoredReturn stored, ReturnRules.Planned planned) {
        if (!stored.orderId().equals(order.id().value())) {
            throw new IdempotencyConflictException("return", planned.returnRef());
        }
        Fingerprint.requireSameRequest("return", planned.returnRef(), stored.fingerprint(), planned.fingerprint());
        return new Result(order, Outcome.REPLAYED);
    }
}
