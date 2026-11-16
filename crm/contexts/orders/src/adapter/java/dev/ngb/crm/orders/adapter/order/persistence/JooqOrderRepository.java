package dev.ngb.crm.orders.adapter.order.persistence;

import static dev.ngb.crm_jooq.Tables.ORDERS;
import static dev.ngb.crm_jooq.Tables.ORDER_LINES;
import static dev.ngb.crm_jooq.Tables.ORDER_RETURNS;
import static dev.ngb.crm_jooq.Tables.ORDER_RETURN_LINES;

import dev.ngb.core.id.TenantId;
import dev.ngb.crm.orders.application.order.port.OrderRepository;
import dev.ngb.crm.orders.domain.order.Order;
import dev.ngb.crm.orders.domain.order.OrderId;
import dev.ngb.crm.orders.domain.order.OrderLine;
import dev.ngb.crm.orders.domain.order.OrderStatus;
import dev.ngb.crm.orders.domain.returns.OrderReturn;
import dev.ngb.crm.orders.domain.returns.ReturnId;
import dev.ngb.crm.orders.domain.returns.ReturnLine;
import dev.ngb.crm_jooq.tables.records.OrderLinesRecord;
import dev.ngb.crm_jooq.tables.records.OrderReturnsRecord;
import dev.ngb.crm_jooq.tables.records.OrdersRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Stores orders with their lines and the returns recorded against them.
 *
 * <p>The unique key of an order is the Tenant's reference, so a first-time record that loses the race against a
 * concurrent one inserts nothing instead of failing and the caller re-reads the row that won. A return is written
 * after the caller has locked the order row, which is what serialises two returns of one order; the update of the
 * order's own row is guarded by the version the caller read, so a missing lock fails loudly instead of overwriting.
 *
 * <p>Every statement is narrowed to the current tenant by row-level security rather than by a condition here, so a
 * missing tenant scope reads nothing instead of reading every tenant's orders.
 */
public class JooqOrderRepository implements OrderRepository {

    private final DSLContext dsl;

    public JooqOrderRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Order> findByOrderRef(String orderRef) {
        return dsl.selectFrom(ORDERS)
                .where(ORDERS.ORDER_REF.eq(orderRef))
                .fetchOptional()
                .map(this::toDomain);
    }

    @Override
    public Optional<Order> lockByOrderRef(String orderRef) {
        return dsl.selectFrom(ORDERS)
                .where(ORDERS.ORDER_REF.eq(orderRef))
                .forUpdate()
                .fetchOptional()
                .map(this::toDomain);
    }

    @Override
    public boolean insertIfAbsent(Order order) {
        int inserted = dsl.insertInto(ORDERS)
                .set(ORDERS.TENANT_ID, order.tenantId().value())
                .set(ORDERS.ID, order.id().value())
                .set(ORDERS.ORDER_REF, order.orderRef())
                .set(ORDERS.CUSTOMER_ID, order.customerId())
                .set(ORDERS.CURRENCY, order.currency())
                .set(ORDERS.TOTAL, order.total())
                .set(ORDERS.OCCURRED_AT, order.occurredAt())
                .set(ORDERS.STORE_CODE, order.storeCode())
                .set(ORDERS.CHANNEL, order.channel())
                .set(ORDERS.STATUS, order.status().name())
                .set(ORDERS.VERSION, order.version())
                .set(ORDERS.FINGERPRINT, order.fingerprint())
                .set(ORDERS.CREATED_AT, order.createdAt())
                .onConflict(ORDERS.TENANT_ID, ORDERS.ORDER_REF)
                .doNothing()
                .execute();
        if (inserted != 1) {
            return false;
        }
        var insert = dsl.insertInto(ORDER_LINES)
                .columns(
                        ORDER_LINES.TENANT_ID,
                        ORDER_LINES.ORDER_ID,
                        ORDER_LINES.LINE_NO,
                        ORDER_LINES.SKU,
                        ORDER_LINES.NAME,
                        ORDER_LINES.CATEGORY,
                        ORDER_LINES.QUANTITY,
                        ORDER_LINES.AMOUNT,
                        ORDER_LINES.EARN_ELIGIBLE,
                        ORDER_LINES.RETURNED_QUANTITY,
                        ORDER_LINES.RETURNED_AMOUNT);
        for (OrderLine line : order.lines()) {
            insert = insert.values(
                    order.tenantId().value(),
                    order.id().value(),
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
        insert.execute();
        return true;
    }

    @Override
    public void applyReturn(Order order, OrderReturn recorded, String fingerprint, Instant recordedAt) {
        int updated = dsl.update(ORDERS)
                .set(ORDERS.STATUS, order.status().name())
                .set(ORDERS.VERSION, order.version())
                .where(ORDERS.ID.eq(order.id().value()))
                .and(ORDERS.VERSION.eq(order.version() - 1))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException(
                    "order " + order.orderRef() + " was not updated: version " + order.version() + " is not the next one");
        }
        for (OrderLine line : order.lines()) {
            dsl.update(ORDER_LINES)
                    .set(ORDER_LINES.RETURNED_QUANTITY, line.returnedQuantity())
                    .set(ORDER_LINES.RETURNED_AMOUNT, line.returnedAmount())
                    .where(ORDER_LINES.ORDER_ID.eq(order.id().value()))
                    .and(ORDER_LINES.LINE_NO.eq(line.lineNo()))
                    .execute();
        }
        dsl.insertInto(ORDER_RETURNS)
                .set(ORDER_RETURNS.TENANT_ID, order.tenantId().value())
                .set(ORDER_RETURNS.ID, recorded.id().value())
                .set(ORDER_RETURNS.ORDER_ID, order.id().value())
                .set(ORDER_RETURNS.RETURN_REF, recorded.returnRef())
                .set(ORDER_RETURNS.OCCURRED_AT, recorded.occurredAt())
                .set(ORDER_RETURNS.VERSION, recorded.version())
                .set(ORDER_RETURNS.FINGERPRINT, fingerprint)
                .set(ORDER_RETURNS.CREATED_AT, recordedAt)
                .execute();
        var insert = dsl.insertInto(ORDER_RETURN_LINES)
                .columns(
                        ORDER_RETURN_LINES.TENANT_ID,
                        ORDER_RETURN_LINES.RETURN_ID,
                        ORDER_RETURN_LINES.LINE_NO,
                        ORDER_RETURN_LINES.QUANTITY,
                        ORDER_RETURN_LINES.AMOUNT);
        for (ReturnLine line : recorded.lines()) {
            insert = insert.values(
                    order.tenantId().value(), recorded.id().value(), line.lineNo(), line.quantity(), line.amount());
        }
        insert.execute();
    }

    @Override
    public Optional<StoredReturn> findReturn(String returnRef) {
        return dsl.select(ORDER_RETURNS.ID, ORDER_RETURNS.ORDER_ID, ORDER_RETURNS.RETURN_REF, ORDER_RETURNS.FINGERPRINT)
                .from(ORDER_RETURNS)
                .where(ORDER_RETURNS.RETURN_REF.eq(returnRef))
                .fetchOptional(row -> new StoredReturn(
                        row.get(ORDER_RETURNS.ID),
                        row.get(ORDER_RETURNS.ORDER_ID),
                        row.get(ORDER_RETURNS.RETURN_REF),
                        row.get(ORDER_RETURNS.FINGERPRINT)));
    }

    private Order toDomain(OrdersRecord row) {
        UUID orderId = row.getId();
        return Order.restore(
                new OrderId(orderId),
                new TenantId(row.getTenantId()),
                row.getOrderRef(),
                row.getCustomerId(),
                row.getCurrency(),
                row.getTotal(),
                row.getOccurredAt(),
                row.getStoreCode(),
                row.getChannel(),
                row.getFingerprint(),
                row.getCreatedAt(),
                linesOf(orderId),
                returnsOf(orderId),
                OrderStatus.valueOf(row.getStatus()),
                row.getVersion());
    }

    private List<OrderLine> linesOf(UUID orderId) {
        List<OrderLinesRecord> rows = dsl.selectFrom(ORDER_LINES)
                .where(ORDER_LINES.ORDER_ID.eq(orderId))
                .orderBy(ORDER_LINES.LINE_NO)
                .fetch();
        List<OrderLine> lines = new ArrayList<>(rows.size());
        for (OrderLinesRecord row : rows) {
            lines.add(new OrderLine(
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
        return List.copyOf(lines);
    }

    private List<OrderReturn> returnsOf(UUID orderId) {
        List<OrderReturnsRecord> rows = dsl.selectFrom(ORDER_RETURNS)
                .where(ORDER_RETURNS.ORDER_ID.eq(orderId))
                .orderBy(ORDER_RETURNS.VERSION)
                .fetch();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ReturnLine>> linesByReturn = returnLinesOf(rows.stream()
                .map(OrderReturnsRecord::getId)
                .toList());
        List<OrderReturn> returns = new ArrayList<>(rows.size());
        for (OrderReturnsRecord row : rows) {
            returns.add(new OrderReturn(
                    new ReturnId(row.getId()),
                    row.getReturnRef(),
                    row.getOccurredAt(),
                    row.getVersion(),
                    linesByReturn.getOrDefault(row.getId(), List.of())));
        }
        return List.copyOf(returns);
    }

    private Map<UUID, List<ReturnLine>> returnLinesOf(List<UUID> returnIds) {
        Map<UUID, List<ReturnLine>> byReturn = new LinkedHashMap<>();
        List<dev.ngb.crm_jooq.tables.records.OrderReturnLinesRecord> rows = dsl.selectFrom(ORDER_RETURN_LINES)
                .where(ORDER_RETURN_LINES.RETURN_ID.in(returnIds))
                .orderBy(ORDER_RETURN_LINES.RETURN_ID, ORDER_RETURN_LINES.LINE_NO)
                .fetch();
        for (var row : rows) {
            byReturn
                    .computeIfAbsent(row.getReturnId(), id -> new ArrayList<>())
                    .add(new ReturnLine(row.getLineNo(), row.getQuantity(), row.getAmount()));
        }
        Map<UUID, List<ReturnLine>> copy = new HashMap<>();
        byReturn.forEach((id, lines) -> copy.put(id, List.copyOf(lines)));
        return copy;
    }
}
