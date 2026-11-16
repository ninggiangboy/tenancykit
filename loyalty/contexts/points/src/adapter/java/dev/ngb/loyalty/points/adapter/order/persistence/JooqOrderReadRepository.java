package dev.ngb.loyalty.points.adapter.order.persistence;

import static dev.ngb.loyalty_jooq.Tables.MEMBERS;
import static dev.ngb.loyalty_jooq.Tables.ORDERS;
import static dev.ngb.loyalty_jooq.Tables.ORDER_EARNS;
import static dev.ngb.loyalty_jooq.Tables.ORDER_LINES;
import static dev.ngb.loyalty_jooq.Tables.ORDER_RETURNS;
import static dev.ngb.loyalty_jooq.Tables.POINT_LOTS;

import dev.ngb.loyalty.points.application.order.query.OrderReadRepository;
import dev.ngb.loyalty.points.application.order.query.OrderView;
import dev.ngb.loyalty.points.domain.earn.EarnSnapshot;
import dev.ngb.loyalty_jooq.tables.records.OrderEarnsRecord;
import dev.ngb.loyalty_jooq.tables.records.OrdersRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * Selects one order with its stored earn, its lines and its returns, and the member's balance at the moment of the read.
 *
 * <p>The balance is summed from the lots here too: nothing in this context stores a total.
 */
public class JooqOrderReadRepository implements OrderReadRepository {

    private final DSLContext dsl;
    private final EarnSnapshotJson snapshots;

    public JooqOrderReadRepository(DSLContext dsl, EarnSnapshotJson snapshots) {
        this.dsl = dsl;
        this.snapshots = snapshots;
    }

    @Override
    public Optional<OrderView> find(String orderRef, Instant now) {
        Optional<OrdersRecord> stored = dsl.selectFrom(ORDERS)
                .where(ORDERS.ORDER_REF.eq(orderRef))
                .fetchOptional();
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        OrdersRecord order = stored.get();
        OrderEarnsRecord earn = dsl.selectFrom(ORDER_EARNS)
                .where(ORDER_EARNS.ORDER_ID.eq(order.getId()))
                .fetchOne();
        BigDecimal balance = dsl.select(DSL.sum(POINT_LOTS.REMAINING))
                .from(POINT_LOTS)
                .where(POINT_LOTS.MEMBER_ID.eq(order.getMemberId()))
                .and(POINT_LOTS.EXPIRES_AT.gt(now))
                .fetchOne(0, BigDecimal.class);
        var member = dsl.select(MEMBERS.EXTERNAL_REF, MEMBERS.DEBT)
                .from(MEMBERS)
                .where(MEMBERS.ID.eq(order.getMemberId()))
                .fetchOne();
        long debt = member == null ? 0L : member.get(MEMBERS.DEBT);
        EarnSnapshot snapshot = earn == null ? null : snapshots.read(earn.getSnapshot().data());
        return Optional.of(new OrderView(
                order.getOrderRef(),
                member == null ? null : member.get(MEMBERS.EXTERNAL_REF),
                order.getCurrency(),
                order.getOccurredAt(),
                order.getStoreCode(),
                order.getChannel(),
                earn == null ? 0L : earn.getGrantedPoints(),
                earn == null ? 0L : earn.getSpendAmount(),
                (balance == null ? 0L : balance.longValue()) - debt,
                debt,
                snapshot(earn, snapshot),
                lines(order.getId()),
                returns(order.getId())));
    }

    private List<OrderView.LineView> lines(UUID orderId) {
        return dsl.selectFrom(ORDER_LINES)
                .where(ORDER_LINES.ORDER_ID.eq(orderId))
                .orderBy(ORDER_LINES.LINE_NO)
                .fetch(row -> new OrderView.LineView(
                        row.getLineNo(),
                        row.getSku(),
                        row.getCategory(),
                        row.getQuantity(),
                        row.getAmount(),
                        row.getEarnEligible(),
                        row.getReturnedQuantity(),
                        row.getReturnedAmount()));
    }

    private List<OrderView.ReturnView> returns(UUID orderId) {
        return dsl.selectFrom(ORDER_RETURNS)
                .where(ORDER_RETURNS.ORDER_ID.eq(orderId))
                .orderBy(ORDER_RETURNS.CREATED_AT, ORDER_RETURNS.ID)
                .fetch(row -> new OrderView.ReturnView(
                        row.getReturnRef(),
                        row.getOccurredAt(),
                        row.getPointsClawedBack(),
                        row.getDebtAdded()));
    }

    private static OrderView.EarnSnapshotView snapshot(OrderEarnsRecord earn, EarnSnapshot snapshot) {
        if (earn == null || snapshot == null) {
            return null;
        }
        return new OrderView.EarnSnapshotView(
                snapshot.unitAmount(),
                snapshot.currency(),
                snapshot.tierCode(),
                snapshot.tierMultiplier(),
                snapshot.lines().stream()
                        .map(line -> new OrderView.SnapshotLineView(
                                line.lineNo(),
                                line.multiplier(),
                                line.promotionIds().stream().map(UUID::toString).toList()))
                        .toList());
    }
}
