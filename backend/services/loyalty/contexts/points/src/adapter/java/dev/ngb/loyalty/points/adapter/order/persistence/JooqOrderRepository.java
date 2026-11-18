package dev.ngb.loyalty.points.adapter.order.persistence;

import static dev.ngb.loyalty_jooq.Tables.ORDERS;
import static dev.ngb.loyalty_jooq.Tables.ORDER_EARNS;
import static dev.ngb.loyalty_jooq.Tables.ORDER_LINES;
import static dev.ngb.loyalty_jooq.Tables.ORDER_RETURNS;
import static dev.ngb.loyalty_jooq.Tables.ORDER_RETURN_LINES;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.points.application.order.port.OrderRepository;
import dev.ngb.loyalty.points.application.order.port.ReturnLineRow;
import dev.ngb.loyalty.points.application.order.port.StoredOrder;
import dev.ngb.loyalty.points.application.order.port.StoredReturn;
import dev.ngb.loyalty.points.domain.order.OrderLine;
import dev.ngb.loyalty_jooq.tables.records.OrderEarnsRecord;
import dev.ngb.loyalty_jooq.tables.records.OrderReturnsRecord;
import dev.ngb.loyalty_jooq.tables.records.OrdersRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;

/**
 * The orders, their lines, their returns and the earn record that makes a return reproducible.
 *
 * <p>The snapshot is written once, when the order is recorded, and read back on every return: nothing here ever reads the
 * program to recompute an old order.
 */
public class JooqOrderRepository implements OrderRepository {

    private final DSLContext dsl;
    private final TenantContext tenantContext;
    private final EarnSnapshotJson snapshots;

    public JooqOrderRepository(DSLContext dsl, TenantContext tenantContext, EarnSnapshotJson snapshots) {
        this.dsl = dsl;
        this.tenantContext = tenantContext;
        this.snapshots = snapshots;
    }

    @Override
    public Optional<StoredOrder> findByRef(String orderRef) {
        return dsl.select(ORDERS.asterisk(), ORDER_EARNS.GRANTED_POINTS, ORDER_EARNS.SPEND_AMOUNT, ORDER_EARNS.SNAPSHOT)
                .from(ORDERS)
                .join(ORDER_EARNS)
                .on(ORDER_EARNS.ORDER_ID.eq(ORDERS.ID))
                .where(ORDERS.ORDER_REF.eq(orderRef))
                .fetchOptional(this::toOrder);
    }

    @Override
    public Optional<StoredOrder> lockByRef(String orderRef) {
        return dsl.selectFrom(ORDERS)
                .where(ORDERS.ORDER_REF.eq(orderRef))
                .forUpdate()
                .fetchOptional()
                .flatMap(row -> Optional.ofNullable(dsl.selectFrom(ORDER_EARNS)
                                        .where(ORDER_EARNS.ORDER_ID.eq(row.getId()))
                                        .fetchOne())
                        .map(earn -> toOrder(row, earn)));
    }

    @Override
    public Optional<StoredReturn> findReturn(String returnRef) {
        return dsl.selectFrom(ORDER_RETURNS)
                .where(ORDER_RETURNS.RETURN_REF.eq(returnRef))
                .fetchOptional(JooqOrderRepository::toReturn);
    }

    @Override
    public List<StoredReturn> returns(UUID orderId) {
        return dsl.selectFrom(ORDER_RETURNS)
                .where(ORDER_RETURNS.ORDER_ID.eq(orderId))
                .orderBy(ORDER_RETURNS.CREATED_AT, ORDER_RETURNS.ID)
                .fetch(JooqOrderRepository::toReturn);
    }

    @Override
    public List<OrderLine> lines(UUID orderId) {
        return dsl.selectFrom(ORDER_LINES)
                .where(ORDER_LINES.ORDER_ID.eq(orderId))
                .orderBy(ORDER_LINES.LINE_NO)
                .fetch(row -> new OrderLine(
                        row.getLineNo(),
                        row.getSku(),
                        row.getCategory(),
                        row.getQuantity(),
                        row.getAmount(),
                        row.getEarnEligible(),
                        row.getReturnedQuantity(),
                        row.getReturnedAmount()));
    }

    @Override
    public void add(StoredOrder order, List<OrderLine> lines) {
        dsl.insertInto(ORDERS)
                .set(ORDERS.TENANT_ID, tenantId().value())
                .set(ORDERS.ID, order.id())
                .set(ORDERS.ORDER_REF, order.orderRef())
                .set(ORDERS.MEMBER_ID, order.memberId())
                .set(ORDERS.CURRENCY, order.currency())
                .set(ORDERS.OCCURRED_AT, order.occurredAt())
                .set(ORDERS.STORE_CODE, order.storeCode())
                .set(ORDERS.CHANNEL, order.channel())
                .set(ORDERS.FINGERPRINT, order.fingerprint())
                .set(ORDERS.CREATED_AT, order.createdAt())
                .execute();
        for (OrderLine line : lines) {
            dsl.insertInto(ORDER_LINES)
                    .set(ORDER_LINES.TENANT_ID, tenantId().value())
                    .set(ORDER_LINES.ORDER_ID, order.id())
                    .set(ORDER_LINES.LINE_NO, line.lineNo())
                    .set(ORDER_LINES.SKU, line.sku())
                    .set(ORDER_LINES.CATEGORY, line.category())
                    .set(ORDER_LINES.QUANTITY, line.quantity())
                    .set(ORDER_LINES.AMOUNT, line.amount())
                    .set(ORDER_LINES.EARN_ELIGIBLE, line.earnEligible())
                    .set(ORDER_LINES.RETURNED_QUANTITY, line.returnedQuantity())
                    .set(ORDER_LINES.RETURNED_AMOUNT, line.returnedAmount())
                    .execute();
        }
        dsl.insertInto(ORDER_EARNS)
                .set(ORDER_EARNS.TENANT_ID, tenantId().value())
                .set(ORDER_EARNS.ORDER_ID, order.id())
                .set(ORDER_EARNS.MEMBER_ID, order.memberId())
                .set(ORDER_EARNS.SNAPSHOT, JSONB.valueOf(snapshots.write(order.snapshot())))
                .set(ORDER_EARNS.GRANTED_POINTS, order.grantedPoints())
                .set(ORDER_EARNS.SPEND_AMOUNT, order.spendAmount())
                .set(ORDER_EARNS.OCCURRED_AT, order.occurredAt())
                .execute();
    }

    @Override
    public void updateWorth(UUID orderId, long grantedPoints, long spendAmount) {
        dsl.update(ORDER_EARNS)
                .set(ORDER_EARNS.GRANTED_POINTS, grantedPoints)
                .set(ORDER_EARNS.SPEND_AMOUNT, spendAmount)
                .where(ORDER_EARNS.ORDER_ID.eq(orderId))
                .execute();
    }

    @Override
    public void updateReturned(UUID orderId, List<OrderLine> lines) {
        for (OrderLine line : lines) {
            dsl.update(ORDER_LINES)
                    .set(ORDER_LINES.RETURNED_QUANTITY, line.returnedQuantity())
                    .set(ORDER_LINES.RETURNED_AMOUNT, line.returnedAmount())
                    .where(ORDER_LINES.ORDER_ID.eq(orderId))
                    .and(ORDER_LINES.LINE_NO.eq(line.lineNo()))
                    .execute();
        }
    }

    @Override
    public void addReturn(UUID orderId, StoredReturn recorded, List<ReturnLineRow> lines) {
        dsl.insertInto(ORDER_RETURNS)
                .set(ORDER_RETURNS.TENANT_ID, tenantId().value())
                .set(ORDER_RETURNS.ID, recorded.id())
                .set(ORDER_RETURNS.ORDER_ID, orderId)
                .set(ORDER_RETURNS.RETURN_REF, recorded.returnRef())
                .set(ORDER_RETURNS.OCCURRED_AT, recorded.occurredAt())
                .set(ORDER_RETURNS.FINGERPRINT, recorded.fingerprint())
                .set(ORDER_RETURNS.POINTS_CLAWED_BACK, recorded.pointsClawedBack())
                .set(ORDER_RETURNS.DEBT_ADDED, recorded.debtAdded())
                .set(ORDER_RETURNS.CREATED_AT, recorded.createdAt())
                .execute();
        for (ReturnLineRow line : lines) {
            dsl.insertInto(ORDER_RETURN_LINES)
                    .set(ORDER_RETURN_LINES.TENANT_ID, tenantId().value())
                    .set(ORDER_RETURN_LINES.RETURN_ID, recorded.id())
                    .set(ORDER_RETURN_LINES.LINE_NO, line.lineNo())
                    .set(ORDER_RETURN_LINES.QUANTITY, line.quantity())
                    .set(ORDER_RETURN_LINES.AMOUNT, line.amount())
                    .execute();
        }
    }

    private TenantId tenantId() {
        return tenantContext.requireTenant();
    }

    private StoredOrder toOrder(Record row) {
        return new StoredOrder(
                row.get(ORDERS.ID),
                row.get(ORDERS.ORDER_REF),
                row.get(ORDERS.MEMBER_ID),
                row.get(ORDERS.CURRENCY),
                row.get(ORDERS.OCCURRED_AT),
                row.get(ORDERS.STORE_CODE),
                row.get(ORDERS.CHANNEL),
                row.get(ORDERS.FINGERPRINT),
                row.get(ORDERS.CREATED_AT),
                row.get(ORDER_EARNS.GRANTED_POINTS),
                row.get(ORDER_EARNS.SPEND_AMOUNT),
                snapshots.read(row.get(ORDER_EARNS.SNAPSHOT).data()));
    }

    private StoredOrder toOrder(OrdersRecord order, OrderEarnsRecord earn) {
        return new StoredOrder(
                order.getId(),
                order.getOrderRef(),
                order.getMemberId(),
                order.getCurrency(),
                order.getOccurredAt(),
                order.getStoreCode(),
                order.getChannel(),
                order.getFingerprint(),
                order.getCreatedAt(),
                earn.getGrantedPoints(),
                earn.getSpendAmount(),
                snapshots.read(earn.getSnapshot().data()));
    }

    private static StoredReturn toReturn(OrderReturnsRecord row) {
        return new StoredReturn(
                row.getId(),
                row.getOrderId(),
                row.getReturnRef(),
                row.getOccurredAt(),
                row.getFingerprint(),
                row.getPointsClawedBack(),
                row.getDebtAdded(),
                row.getCreatedAt());
    }
}
