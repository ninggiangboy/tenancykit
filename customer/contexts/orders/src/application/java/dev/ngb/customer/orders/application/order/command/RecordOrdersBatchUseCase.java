package dev.ngb.customer.orders.application.order.command;

import dev.ngb.core.error.IdempotencyConflictException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.customer.orders.application.order.exception.InvalidOrderBatchException;
import dev.ngb.customer.orders.application.order.exception.OrderProblem;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code POST /orders/batch}: the same recording, once per order, each in its own transaction and in the order sent.
 *
 * <p>An order the service refuses fails alone and reports its code; the batch as a whole is never rolled back because
 * of it, and repeating the whole batch is safe because every order is idempotent by its reference. Only the envelope
 * itself — no orders, more orders than one call may hold, or more lines than the batch may carry together — fails the
 * call, because a caller that got the shape wrong should learn it once rather than read a hundred results.
 */
public class RecordOrdersBatchUseCase {

    /** The outcome of an order the service refused; every other outcome is the single call's own. */
    public static final String FAILED = "FAILED";

    /**
     * One order's result, in the order the orders were sent.
     *
     * @param orderRef the order's reference as sent, or null when it carried none
     * @param outcome recorded, replayed or failed
     * @param code the failure code when the order failed, null otherwise
     */
    public record Result(String orderRef, String outcome, String code) {}

    private final UnitOfWork unitOfWork;
    private final RecordOrderUseCase record;
    private final int maxOrders;
    private final int maxLines;

    public RecordOrdersBatchUseCase(
            UnitOfWork unitOfWork, RecordOrderUseCase record, int maxOrders, int maxLines) {
        this.unitOfWork = unitOfWork;
        this.record = record;
        this.maxOrders = maxOrders;
        this.maxLines = maxLines;
    }

    /** @return one result per order, in the order sent */
    public List<Result> execute(List<RecordOrderUseCase.Command> orders) {
        if (orders == null || orders.isEmpty()) {
            throw new InvalidOrderBatchException(
                    InvalidOrderBatchException.INVALID_REQUEST, "The request must carry at least one order.");
        }
        if (orders.size() > maxOrders) {
            throw new InvalidOrderBatchException(
                    InvalidOrderBatchException.BATCH_LIMIT_EXCEEDED,
                    "An order batch carries at most " + maxOrders + " orders.");
        }
        long lines = 0;
        for (RecordOrderUseCase.Command order : orders) {
            lines += order == null || order.lines() == null ? 0 : order.lines().size();
        }
        if (lines > maxLines) {
            throw new InvalidOrderBatchException(
                    InvalidOrderBatchException.BATCH_LIMIT_EXCEEDED,
                    "An order batch carries at most " + maxLines + " lines together.");
        }
        List<Result> results = new ArrayList<>(orders.size());
        for (RecordOrderUseCase.Command order : orders) {
            results.add(apply(order));
        }
        return List.copyOf(results);
    }

    private Result apply(RecordOrderUseCase.Command order) {
        String orderRef = order == null ? null : reference(order.orderRef());
        try {
            // One transaction per order: a failure rolls that order back and leaves the ones before and after it stored.
            RecordOrderUseCase.Result recorded = unitOfWork.transactional(() -> record.execute(order));
            return new Result(orderRef, recorded.outcome().name(), null);
        } catch (OrderProblem refused) {
            return new Result(orderRef, FAILED, refused.code());
        } catch (IdempotencyConflictException conflict) {
            return new Result(orderRef, FAILED, "IDEMPOTENCY_CONFLICT");
        }
    }

    private static String reference(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.strip();
    }
}
