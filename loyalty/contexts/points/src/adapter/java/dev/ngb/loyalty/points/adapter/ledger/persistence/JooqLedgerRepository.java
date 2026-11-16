package dev.ngb.loyalty.points.adapter.ledger.persistence;

import static dev.ngb.loyalty_jooq.Tables.LEDGER_ENTRIES;
import static dev.ngb.loyalty_jooq.Tables.LOT_ALLOCATIONS;
import static dev.ngb.loyalty_jooq.Tables.POINT_LOTS;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.points.application.ledger.port.LedgerEntryRow;
import dev.ngb.loyalty.points.application.ledger.port.LedgerRepository;
import dev.ngb.loyalty.points.application.ledger.port.LiveLot;
import dev.ngb.loyalty.points.application.ledger.port.LotAllocation;
import dev.ngb.loyalty.points.application.ledger.port.PointLotRow;
import dev.ngb.loyalty.points.domain.ledger.LedgerKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * The append-only ledger and the lots it fills.
 *
 * <p>An entry is inserted, never updated, and a lot's `remaining` is lowered by the entry that consumes it, with one
 * allocation row per lot so a clawback can be explained lot by lot. A balance is summed over the unexpired lots, which is
 * why nothing here keeps a total.
 */
public class JooqLedgerRepository implements LedgerRepository {

    private final DSLContext dsl;
    private final TenantContext tenantContext;

    public JooqLedgerRepository(DSLContext dsl, TenantContext tenantContext) {
        this.dsl = dsl;
        this.tenantContext = tenantContext;
    }

    @Override
    public void addEntry(LedgerEntryRow entry) {
        dsl.insertInto(LEDGER_ENTRIES)
                .set(LEDGER_ENTRIES.ID, entry.id())
                .set(LEDGER_ENTRIES.TENANT_ID, tenantId().value())
                .set(LEDGER_ENTRIES.MEMBER_ID, entry.memberId())
                .set(LEDGER_ENTRIES.KIND, entry.kind().name())
                .set(LEDGER_ENTRIES.POINTS, entry.points())
                .set(LEDGER_ENTRIES.ORDER_ID, entry.orderId())
                .set(LEDGER_ENTRIES.RETURN_ID, entry.returnId())
                .set(LEDGER_ENTRIES.REDEMPTION_ID, entry.redemptionId())
                .set(LEDGER_ENTRIES.CREATED_AT, entry.createdAt())
                .execute();
    }

    @Override
    public void addLot(PointLotRow lot) {
        dsl.insertInto(POINT_LOTS)
                .set(POINT_LOTS.TENANT_ID, tenantId().value())
                .set(POINT_LOTS.ID, lot.id())
                .set(POINT_LOTS.MEMBER_ID, lot.memberId())
                .set(POINT_LOTS.ENTRY_ID, lot.entryId())
                .set(POINT_LOTS.ORDER_ID, lot.orderId())
                .set(POINT_LOTS.EARNED, lot.earned())
                .set(POINT_LOTS.REMAINING, lot.earned())
                .set(POINT_LOTS.EARNED_AT, lot.earnedAt())
                .set(POINT_LOTS.EXPIRES_AT, lot.expiresAt())
                .execute();
    }

    @Override
    public void addAllocation(UUID entryId, UUID lotId, long points) {
        dsl.insertInto(LOT_ALLOCATIONS)
                .set(LOT_ALLOCATIONS.TENANT_ID, tenantId().value())
                .set(LOT_ALLOCATIONS.ENTRY_ID, entryId)
                .set(LOT_ALLOCATIONS.LOT_ID, lotId)
                .set(LOT_ALLOCATIONS.POINTS, points)
                .execute();
    }

    @Override
    public List<LiveLot> liveLots(UUID memberId, Instant now) {
        return dsl.select(POINT_LOTS.ID, POINT_LOTS.ORDER_ID, POINT_LOTS.REMAINING, POINT_LOTS.EXPIRES_AT)
                .from(POINT_LOTS)
                .where(POINT_LOTS.MEMBER_ID.eq(memberId))
                .and(POINT_LOTS.REMAINING.gt(0L))
                .and(POINT_LOTS.EXPIRES_AT.gt(now))
                .orderBy(POINT_LOTS.EXPIRES_AT, POINT_LOTS.ID)
                .fetch(row -> new LiveLot(
                        row.value1(), row.value2(), row.value3(), row.value4()));
    }

    @Override
    public void consume(UUID lotId, long points) {
        int updated = dsl.update(POINT_LOTS)
                .set(POINT_LOTS.REMAINING, POINT_LOTS.REMAINING.minus(DSL.val(points)))
                .where(POINT_LOTS.ID.eq(lotId))
                .and(POINT_LOTS.TENANT_ID.eq(tenantId().value()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("lot " + lotId + " was not consumed");
        }
    }

    @Override
    public void restore(UUID lotId, long points) {
        int updated = dsl.update(POINT_LOTS)
                .set(POINT_LOTS.REMAINING, POINT_LOTS.REMAINING.plus(DSL.val(points)))
                .where(POINT_LOTS.ID.eq(lotId))
                .and(POINT_LOTS.TENANT_ID.eq(tenantId().value()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("lot " + lotId + " was not restored");
        }
    }

    @Override
    public Optional<UUID> entryOfRedemption(UUID redemptionId) {
        return dsl.select(LEDGER_ENTRIES.ID)
                .from(LEDGER_ENTRIES)
                .where(LEDGER_ENTRIES.REDEMPTION_ID.eq(redemptionId))
                .and(LEDGER_ENTRIES.KIND.eq(LedgerKind.REDEEM.name()))
                .fetchOptional(LEDGER_ENTRIES.ID);
    }

    @Override
    public List<LotAllocation> allocations(UUID entryId) {
        return dsl.select(LOT_ALLOCATIONS.LOT_ID, LOT_ALLOCATIONS.POINTS)
                .from(LOT_ALLOCATIONS)
                .where(LOT_ALLOCATIONS.ENTRY_ID.eq(entryId))
                .orderBy(LOT_ALLOCATIONS.LOT_ID)
                .fetch(row -> new LotAllocation(row.value1(), row.value2()));
    }

    @Override
    public long liveBalance(UUID memberId, Instant now) {
        BigDecimal sum = dsl.select(DSL.sum(POINT_LOTS.REMAINING))
                .from(POINT_LOTS)
                .where(POINT_LOTS.MEMBER_ID.eq(memberId))
                .and(POINT_LOTS.EXPIRES_AT.gt(now))
                .fetchOne(0, BigDecimal.class);
        return sum == null ? 0L : sum.longValue();
    }

    @Override
    public List<LiveLot> lotsHolding(UUID memberId) {
        return dsl.select(POINT_LOTS.ID, POINT_LOTS.ORDER_ID, POINT_LOTS.REMAINING, POINT_LOTS.EXPIRES_AT)
                .from(POINT_LOTS)
                .where(POINT_LOTS.MEMBER_ID.eq(memberId))
                .and(POINT_LOTS.REMAINING.gt(0L))
                .orderBy(POINT_LOTS.ID)
                .forUpdate()
                .fetch(row -> new LiveLot(row.value1(), row.value2(), row.value3(), row.value4()));
    }

    @Override
    public boolean zeroLot(UUID lotId) {
        return dsl.update(POINT_LOTS)
                        .set(POINT_LOTS.REMAINING, 0L)
                        .where(POINT_LOTS.ID.eq(lotId))
                        .and(POINT_LOTS.TENANT_ID.eq(tenantId().value()))
                        .and(POINT_LOTS.REMAINING.gt(0L))
                        .execute()
                == 1;
    }

    private TenantId tenantId() {
        return tenantContext.requireTenant();
    }
}
