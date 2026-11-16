package dev.ngb.crm.orders.adapter.order.persistence;

import static dev.ngb.crm_jooq.Tables.ORDERS;
import static dev.ngb.crm_jooq.Tables.ORDER_LINES;
import static dev.ngb.crm_jooq.Tables.ORDER_RETURNS;
import static dev.ngb.crm_jooq.Tables.ORDER_RETURN_LINES;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.query.Page;
import dev.ngb.core.query.PageInfo;
import dev.ngb.crm.customers.api.customer.CustomerDirectory;
import dev.ngb.crm.orders.application.order.query.OrderLineView;
import dev.ngb.crm.orders.application.order.query.OrderReadRepository;
import dev.ngb.crm.orders.application.order.query.OrderReturnView;
import dev.ngb.crm.orders.application.order.query.OrderView;
import dev.ngb.crm.orders.application.order.query.ReturnLineView;
import dev.ngb.crm.orders.domain.order.OrderStatus;
import dev.ngb.crm_jooq.tables.records.OrderLinesRecord;
import dev.ngb.crm_jooq.tables.records.OrderReturnsRecord;
import dev.ngb.crm_jooq.tables.records.OrderReturnLinesRecord;
import dev.ngb.crm_jooq.tables.records.OrdersRecord;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Selects orders straight into the view; row-level security narrows every statement to the current tenant.
 *
 * <p>A page reads its orders in one statement and their lines and returns in three more for the whole page, so the
 * number of queries does not grow with the page size. The customer's reference is resolved through the customers
 * context's api, which is the only part of that context an order may name.
 *
 * <p>The customer's order history is paged by a keyset of {@code (occurredAt, id)} rather than an offset, so a page
 * stays correct while newer orders are recorded: the cursor names the last row served and the next page starts after
 * it. It is opaque and not signed — a forged one only moves the start of a read within rows the caller may already
 * see, because the customer condition is applied by the query itself.
 */
public class JooqOrderReadRepository implements OrderReadRepository {

    private final DSLContext dsl;
    private final CustomerDirectory customers;

    public JooqOrderReadRepository(DSLContext dsl, CustomerDirectory customers) {
        this.dsl = dsl;
        this.customers = customers;
    }

    @Override
    public Optional<OrderView> findByOrderRef(String orderRef) {
        return dsl.selectFrom(ORDERS)
                .where(ORDERS.ORDER_REF.eq(orderRef))
                .fetchOptional()
                .map(row -> toViews(List.of(row)).getFirst());
    }

    @Override
    public Page<OrderView> findByCustomer(UUID customerId, int limit, String cursor) {
        var query = dsl.selectFrom(ORDERS).where(ORDERS.CUSTOMER_ID.eq(customerId));
        if (cursor != null && !cursor.isBlank()) {
            Position position = decode(cursor);
            query = query.and(ORDERS.OCCURRED_AT.lt(position.occurredAt())
                    .or(ORDERS.OCCURRED_AT.eq(position.occurredAt()).and(ORDERS.ID.lt(position.id()))));
        }
        List<OrdersRecord> rows = query
                .orderBy(ORDERS.OCCURRED_AT.desc(), ORDERS.ID.desc())
                .limit(limit + 1)
                .fetch();
        boolean more = rows.size() > limit;
        List<OrdersRecord> page = more ? rows.subList(0, limit) : rows;
        String next = null;
        if (more && !page.isEmpty()) {
            OrdersRecord last = page.getLast();
            next = encode(last.getOccurredAt(), last.getId());
        }
        return new Page<>(toViews(page), new PageInfo(limit, null, null, next));
    }

    private List<OrderView> toViews(List<OrdersRecord> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> orderIds = rows.stream().map(OrdersRecord::getId).toList();
        Map<UUID, List<OrderLineView>> lines = linesOf(orderIds);
        Map<UUID, List<OrderReturnView>> returns = returnsOf(orderIds);
        Map<UUID, String> references = new HashMap<>();
        List<OrderView> views = new ArrayList<>(rows.size());
        for (OrdersRecord row : rows) {
            String externalRef = references.computeIfAbsent(
                    row.getCustomerId(),
                    id -> customers
                            .findByCustomerId(id)
                            .map(ref -> ref.externalRef())
                            .orElse(null));
            views.add(new OrderView(
                    row.getId(),
                    row.getOrderRef(),
                    row.getCustomerId(),
                    externalRef,
                    row.getCurrency(),
                    row.getTotal(),
                    row.getOccurredAt(),
                    row.getStoreCode(),
                    row.getChannel(),
                    OrderStatus.valueOf(row.getStatus()),
                    row.getVersion(),
                    row.getCreatedAt(),
                    lines.getOrDefault(row.getId(), List.of()),
                    returns.getOrDefault(row.getId(), List.of())));
        }
        return List.copyOf(views);
    }

    private Map<UUID, List<OrderLineView>> linesOf(List<UUID> orderIds) {
        Map<UUID, List<OrderLineView>> byOrder = new LinkedHashMap<>();
        for (OrderLinesRecord row : dsl.selectFrom(ORDER_LINES)
                .where(ORDER_LINES.ORDER_ID.in(orderIds))
                .orderBy(ORDER_LINES.ORDER_ID, ORDER_LINES.LINE_NO)
                .fetch()) {
            byOrder
                    .computeIfAbsent(row.getOrderId(), id -> new ArrayList<>())
                    .add(new OrderLineView(
                            row.getLineNo(),
                            row.getSku(),
                            row.getName(),
                            row.getCategory(),
                            row.getQuantity(),
                            row.getAmount(),
                            row.getEarnEligible(),
                            row.getReturnedQuantity(),
                            row.getReturnedAmount()));
        }
        byOrder.replaceAll((id, lines) -> List.copyOf(lines));
        return byOrder;
    }

    private Map<UUID, List<OrderReturnView>> returnsOf(List<UUID> orderIds) {
        List<OrderReturnsRecord> rows = dsl.selectFrom(ORDER_RETURNS)
                .where(ORDER_RETURNS.ORDER_ID.in(orderIds))
                .orderBy(ORDER_RETURNS.ORDER_ID, ORDER_RETURNS.VERSION)
                .fetch();
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<ReturnLineView>> lines = returnLinesOf(
                rows.stream().map(OrderReturnsRecord::getId).toList());
        Map<UUID, List<OrderReturnView>> byOrder = new LinkedHashMap<>();
        for (OrderReturnsRecord row : rows) {
            byOrder
                    .computeIfAbsent(row.getOrderId(), id -> new ArrayList<>())
                    .add(new OrderReturnView(
                            row.getReturnRef(),
                            row.getOccurredAt(),
                            lines.getOrDefault(row.getId(), List.of())));
        }
        byOrder.replaceAll((id, returns) -> List.copyOf(returns));
        return byOrder;
    }

    private Map<UUID, List<ReturnLineView>> returnLinesOf(List<UUID> returnIds) {
        Map<UUID, List<ReturnLineView>> byReturn = new HashMap<>();
        for (OrderReturnLinesRecord row : dsl.selectFrom(ORDER_RETURN_LINES)
                .where(ORDER_RETURN_LINES.RETURN_ID.in(returnIds))
                .orderBy(ORDER_RETURN_LINES.RETURN_ID, ORDER_RETURN_LINES.LINE_NO)
                .fetch()) {
            byReturn
                    .computeIfAbsent(row.getReturnId(), id -> new ArrayList<>())
                    .add(new ReturnLineView(row.getLineNo(), row.getQuantity(), row.getAmount()));
        }
        byReturn.replaceAll((id, lines) -> List.copyOf(lines));
        return byReturn;
    }

    /** Where the previous page stopped: the order's instant and its id, which together are a total order. */
    private record Position(Instant occurredAt, UUID id) {}

    private static String encode(Instant occurredAt, UUID id) {
        String text = occurredAt.toEpochMilli() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Position decode(String token) {
        String text;
        try {
            text = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("The cursor is not valid.");
        }
        String[] parts = text.split("\\|", -1);
        if (parts.length != 2) {
            throw new BadRequestException("The cursor is not valid.");
        }
        try {
            return new Position(Instant.ofEpochMilli(Long.parseLong(parts[0])), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new BadRequestException("The cursor is not valid.");
        }
    }
}
