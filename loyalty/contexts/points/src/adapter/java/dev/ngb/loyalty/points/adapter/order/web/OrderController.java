package dev.ngb.loyalty.points.adapter.order.web;

import dev.ngb.core.idempotency.RepeatableCreate;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.loyalty.points.application.order.command.RecordOrderUseCase;
import dev.ngb.loyalty.points.application.order.command.RecordReturnUseCase;
import dev.ngb.loyalty.points.application.order.query.OrderQueries;
import dev.ngb.loyalty.points.application.order.query.OrderView;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Orders and their returns, as the Tenant's own system records them.
 *
 * <p>Both writes are idempotent by the reference in the path: the same reference with the same body answers the stored
 * result with 200, and the same reference with a different body is a conflict. An order is immutable; a mistake is
 * corrected by a return, which recomputes with the order's stored snapshot and never with today's rules.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class OrderController {

    /** The order body in the shared order shape, carrying only the fields loyalty reads. */
    public record OrderRequest(
            String externalRef,
            String currency,
            Instant occurredAt,
            String storeCode,
            String channel,
            List<OrderLineRequest> lines) {

        public record OrderLineRequest(
                int lineNo, String sku, String category, BigDecimal quantity, long amount, Boolean earnEligible) {}
    }

    public record ReturnRequest(Instant occurredAt, List<ReturnLineRequest> lines) {

        public record ReturnLineRequest(int lineNo, BigDecimal quantity, long amount) {}
    }

    public record SnapshotLineResponse(int lineNo, BigDecimal multiplier, List<String> promotionIds) {}

    public record EarnSnapshotResponse(
            long unitAmount,
            String currency,
            String tierCode,
            BigDecimal tierMultiplier,
            List<SnapshotLineResponse> lines) {}

    public record OrderLineResponse(
            int lineNo,
            String sku,
            String category,
            BigDecimal quantity,
            long amount,
            boolean earnEligible,
            BigDecimal returnedQuantity,
            long returnedAmount) {}

    public record OrderReturnResponse(
            String returnRef, Instant occurredAt, long pointsClawedBack, long debtAdded) {}

    public record OrderResponse(
            String orderRef,
            String externalRef,
            String currency,
            Instant occurredAt,
            String storeCode,
            String channel,
            long pointsGranted,
            long spendAmount,
            long available,
            long debt,
            EarnSnapshotResponse earnSnapshot,
            List<OrderLineResponse> lines,
            List<OrderReturnResponse> returns) {

        static OrderResponse of(RecordOrderUseCase.Result result) {
            return new OrderResponse(
                    result.orderRef(),
                    result.memberExternalRef(),
                    result.currency(),
                    result.occurredAt(),
                    result.storeCode(),
                    result.channel(),
                    result.pointsGranted(),
                    result.spendAmount(),
                    result.available(),
                    result.debt(),
                    null,
                    result.lines().stream()
                            .map(line -> new OrderLineResponse(line.lineNo(), line.sku(), line.category(),
                                    line.quantity(), line.amount(), line.earnEligible(), line.returnedQuantity(),
                                    line.returnedAmount()))
                            .toList(),
                    result.returns().stream()
                            .map(taken -> new OrderReturnResponse(taken.returnRef(), taken.occurredAt(),
                                    taken.pointsClawedBack(), taken.debtAdded()))
                            .toList());
        }

        static OrderResponse ofView(OrderView view) {
            return new OrderResponse(
                    view.orderRef(),
                    view.externalRef(),
                    view.currency(),
                    view.occurredAt(),
                    view.storeCode(),
                    view.channel(),
                    view.pointsGranted(),
                    view.spendAmount(),
                    view.available(),
                    view.debt(),
                    view.earnSnapshot() == null
                            ? null
                            : new EarnSnapshotResponse(
                                    view.earnSnapshot().unitAmount(),
                                    view.earnSnapshot().currency(),
                                    view.earnSnapshot().tierCode(),
                                    view.earnSnapshot().tierMultiplier(),
                                    view.earnSnapshot().lines().stream()
                                            .map(line -> new SnapshotLineResponse(
                                                    line.lineNo(), line.multiplier(), line.promotionIds()))
                                            .toList()),
                    view.lines().stream()
                            .map(line -> new OrderLineResponse(line.lineNo(), line.sku(), line.category(),
                                    line.quantity(), line.amount(), line.earnEligible(), line.returnedQuantity(),
                                    line.returnedAmount()))
                            .toList(),
                    view.returns().stream()
                            .map(taken -> new OrderReturnResponse(taken.returnRef(), taken.occurredAt(),
                                    taken.pointsClawedBack(), taken.debtAdded()))
                            .toList());
        }
    }

    private final RecordOrderUseCase recordOrder;
    private final RecordReturnUseCase recordReturn;
    private final OrderQueries orders;

    public OrderController(
            RecordOrderUseCase recordOrder, RecordReturnUseCase recordReturn, OrderQueries orders) {
        this.recordOrder = recordOrder;
        this.recordReturn = recordReturn;
        this.orders = orders;
    }

    @PutMapping("/orders/{orderRef}")
    @RequiresPermission(LoyaltyPermissions.ORDER_RECORD)
    @RepeatableCreate
    public ResponseEntity<OrderResponse> putOrder(
            @PathVariable String orderRef, @RequestBody OrderRequest request) {
        RecordOrderUseCase.Result result = recordOrder.execute(new RecordOrderUseCase.Command(
                orderRef,
                request.externalRef(),
                request.currency(),
                request.occurredAt(),
                request.storeCode(),
                request.channel(),
                request.lines() == null
                        ? List.of()
                        : request.lines().stream()
                                .map(line -> new RecordOrderUseCase.Command.Line(line.lineNo(), line.sku(),
                                        line.category(), line.quantity(), line.amount(), line.earnEligible()))
                                .toList()));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(OrderResponse.of(result));
    }

    @PutMapping("/orders/{orderRef}/returns/{returnRef}")
    @RequiresPermission(LoyaltyPermissions.ORDER_RECORD)
    @RepeatableCreate
    public ResponseEntity<OrderResponse> putOrderReturn(
            @PathVariable String orderRef, @PathVariable String returnRef, @RequestBody ReturnRequest request) {
        RecordOrderUseCase.Result result = recordReturn.execute(new RecordReturnUseCase.Command(
                orderRef,
                returnRef,
                request.occurredAt(),
                request.lines() == null
                        ? List.of()
                        : request.lines().stream()
                                .map(line -> new RecordReturnUseCase.Command.Line(
                                        line.lineNo(), line.quantity(), line.amount()))
                                .toList()));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(OrderResponse.of(result));
    }

    @GetMapping("/orders/{orderRef}")
    @RequiresPermission(LoyaltyPermissions.MEMBER_READ)
    public OrderResponse getOrder(@PathVariable String orderRef) {
        return OrderResponse.ofView(orders.get(orderRef));
    }
}
