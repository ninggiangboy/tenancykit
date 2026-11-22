package dev.ngb.customer.orders.adapter.order.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.idempotency.RepeatableCreate;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.customer.orders.application.order.command.RecordOrderUseCase;
import dev.ngb.customer.orders.application.order.command.RecordOrdersBatchUseCase;
import dev.ngb.customer.orders.application.order.query.OrderLineView;
import dev.ngb.customer.orders.application.order.query.OrderQueries;
import dev.ngb.customer.orders.application.order.query.OrderReturnView;
import dev.ngb.customer.orders.application.order.query.OrderView;
import dev.ngb.customer.orders.application.order.query.ReturnLineView;
import dev.ngb.customer.orders.application.returns.command.RecordOrderReturnUseCase;
import dev.ngb.customer.shared.CustomerPermissions;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Tenant's orders, keyed by the reference its own system chose, and the returns recorded against them.
 *
 * <p>{@code PUT} records an order once: {@code 201} the first time, {@code 200} when the same body is sent again, and
 * {@code 409} when the same reference arrives with a different body. An order is never edited — a correction is a
 * return — so there is no PATCH here. {@code PUT …/returns/{returnRef}} takes part of an order back under the same
 * rule, and both carry the order's version as the {@code ETag}, which is also the {@code orderVersion} of the webhooks
 * the two calls queue.
 *
 * <p>The batch is {@code 200} even when some orders failed: a backfill must not restart from the beginning over one
 * bad order, and every order is idempotent by its reference, so repeating the batch is safe.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class OrderController {

    /** One line as the Tenant sends it. A line's number is its position, so it is not sent. */
    public record OrderLineRequest(
            String sku, String name, String category, BigDecimal quantity, Long amount, Boolean earnEligible) {}

    /**
     * One order as the Tenant records it. {@code orderRef} is read from the body in a batch and ignored on a single
     * call, where the path names it; {@code createCustomer} asks for an unknown customer to be created rather than
     * refused.
     */
    public record OrderUpsertRequest(
            String orderRef,
            String externalRef,
            String currency,
            Instant occurredAt,
            String storeCode,
            String channel,
            Long total,
            Boolean createCustomer,
            List<OrderLineRequest> lines) {}

    public record ReturnLineRequest(Integer lineNo, BigDecimal quantity, Long amount) {}

    public record ReturnRequest(Instant occurredAt, List<ReturnLineRequest> lines) {}

    public record OrderBatchRequest(List<OrderUpsertRequest> orders) {}

    public record OrderLineResponse(
            int lineNo,
            String sku,
            String name,
            String category,
            BigDecimal quantity,
            long amount,
            boolean earnEligible,
            BigDecimal returnedQuantity,
            long returnedAmount) {

        static OrderLineResponse of(OrderLineView line) {
            return new OrderLineResponse(
                    line.lineNo(),
                    line.sku(),
                    line.name(),
                    line.category(),
                    line.quantity(),
                    line.amount(),
                    line.earnEligible(),
                    line.returnedQuantity(),
                    line.returnedAmount());
        }
    }

    public record ReturnLineResponse(int lineNo, BigDecimal quantity, long amount) {

        static ReturnLineResponse of(ReturnLineView line) {
            return new ReturnLineResponse(line.lineNo(), line.quantity(), line.amount());
        }
    }

    public record ReturnResponse(String returnRef, Instant occurredAt, List<ReturnLineResponse> lines) {

        static ReturnResponse of(OrderReturnView recorded) {
            return new ReturnResponse(
                    recorded.returnRef(),
                    recorded.occurredAt(),
                    recorded.lines().stream().map(ReturnLineResponse::of).toList());
        }
    }

    /** One recorded order: its lines with their cumulative returned figures, its returns and its version. */
    public record OrderResponse(
            String id,
            String orderRef,
            String customerId,
            String externalRef,
            String currency,
            long total,
            Instant occurredAt,
            String storeCode,
            String channel,
            String status,
            long orderVersion,
            Instant createdAt,
            List<OrderLineResponse> lines,
            List<ReturnResponse> returns) {

        static OrderResponse of(OrderView order) {
            return new OrderResponse(
                    order.id().toString(),
                    order.orderRef(),
                    order.customerId().toString(),
                    order.externalRef(),
                    order.currency(),
                    order.total(),
                    order.occurredAt(),
                    order.storeCode(),
                    order.channel(),
                    order.status().name(),
                    order.version(),
                    order.createdAt(),
                    order.lines().stream().map(OrderLineResponse::of).toList(),
                    order.returns().stream().map(ReturnResponse::of).toList());
        }
    }

    /** One order's outcome in a batch, keyed by its position and its reference. */
    public record BatchItemResult(int index, String ref, String outcome, String code) {}

    public record OrderBatchResponse(List<BatchItemResult> results) {}

    private final RecordOrderUseCase record;
    private final RecordOrderReturnUseCase recordReturn;
    private final RecordOrdersBatchUseCase batch;
    private final OrderQueries orderQueries;

    public OrderController(
            RecordOrderUseCase record,
            RecordOrderReturnUseCase recordReturn,
            RecordOrdersBatchUseCase batch,
            OrderQueries orderQueries) {
        this.record = record;
        this.recordReturn = recordReturn;
        this.batch = batch;
        this.orderQueries = orderQueries;
    }

    /** Records the order, or replays the one the same body already recorded under this reference. */
    @PutMapping("/orders/{orderRef}")
    @RepeatableCreate
    @RequiresPermission(CustomerPermissions.ORDER_RECORD)
    public ResponseEntity<OrderResponse> putOrder(
            @PathVariable String orderRef,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody(required = false) OrderUpsertRequest request) {
        RecordOrderUseCase.Result result = record.execute(command(orderRef, request));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .eTag(ExpectedVersion.etag(result.order().version()))
                .body(OrderResponse.of(orderQueries.get(result.order().orderRef())));
    }

    /** Reads one order with its lines, the cumulative returned figures and every return recorded against it. */
    @GetMapping("/orders/{orderRef}")
    @RequiresPermission(CustomerPermissions.ORDER_READ)
    public ResponseEntity<OrderResponse> getOrder(@PathVariable String orderRef) {
        OrderView order = orderQueries.get(orderRef);
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(order.version()))
                .body(OrderResponse.of(order));
    }

    /**
     * Records a return against the order, or replays the one the same body already recorded under this reference. The
     * order's version rises by one, and the {@code ETag} is the version the return produced.
     */
    @PutMapping("/orders/{orderRef}/returns/{returnRef}")
    @RepeatableCreate
    @RequiresPermission(CustomerPermissions.ORDER_RECORD)
    public ResponseEntity<OrderResponse> putReturn(
            @PathVariable String orderRef,
            @PathVariable String returnRef,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody(required = false) ReturnRequest request) {
        RecordOrderReturnUseCase.Result result = recordReturn.execute(
                orderRef,
                new RecordOrderReturnUseCase.Command(
                        returnRef,
                        request == null ? null : request.occurredAt(),
                        request == null || request.lines() == null
                                ? null
                                : request.lines().stream()
                                        .map(OrderController::returnLine)
                                        .toList()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .eTag(ExpectedVersion.etag(result.order().version()))
                .body(OrderResponse.of(orderQueries.get(result.order().orderRef())));
    }

    /** Records up to a hundred orders, each in its own transaction and reported on its own. */
    @PostMapping("/orders/batch")
    @RequiresPermission(CustomerPermissions.ORDER_RECORD)
    public OrderBatchResponse recordOrders(
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody(required = false) OrderBatchRequest request) {
        List<RecordOrderUseCase.Command> orders =
                request == null || request.orders() == null ? null : request.orders().stream()
                        .map(item -> command(item == null ? null : item.orderRef(), item))
                        .toList();
        List<RecordOrdersBatchUseCase.Result> results = batch.execute(orders);
        List<BatchItemResult> items = new ArrayList<>(results.size());
        for (int index = 0; index < results.size(); index++) {
            RecordOrdersBatchUseCase.Result result = results.get(index);
            items.add(new BatchItemResult(index, result.orderRef(), result.outcome(), result.code()));
        }
        return new OrderBatchResponse(List.copyOf(items));
    }

    /** A page of the customer's orders, newest first, continued by the {@code next_cursor} of the previous page. */
    @GetMapping("/customers/{externalRef}/orders")
    @RequiresPermission(CustomerPermissions.ORDER_READ)
    public Page<OrderResponse> listCustomerOrders(
            @PathVariable String externalRef,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return orderQueries.forCustomer(externalRef, limit, cursor).map(OrderResponse::of);
    }

    private static RecordOrderUseCase.Command command(String orderRef, OrderUpsertRequest request) {
        if (request == null) {
            return new RecordOrderUseCase.Command(orderRef, null, null, null, null, null, null, false, null);
        }
        return new RecordOrderUseCase.Command(
                orderRef,
                request.externalRef(),
                request.currency(),
                request.occurredAt(),
                request.storeCode(),
                request.channel(),
                request.total(),
                Boolean.TRUE.equals(request.createCustomer()),
                request.lines() == null
                        ? null
                        : request.lines().stream()
                                .map(OrderController::soldLine)
                                .toList());
    }

    private static RecordOrderUseCase.Line soldLine(OrderLineRequest line) {
        if (line == null) {
            return null;
        }
        return new RecordOrderUseCase.Line(
                line.sku(), line.name(), line.category(), line.quantity(), line.amount(), line.earnEligible());
    }

    private static RecordOrderReturnUseCase.Line returnLine(ReturnLineRequest line) {
        if (line == null) {
            return null;
        }
        return new RecordOrderReturnUseCase.Line(line.lineNo(), line.quantity(), line.amount());
    }
}
