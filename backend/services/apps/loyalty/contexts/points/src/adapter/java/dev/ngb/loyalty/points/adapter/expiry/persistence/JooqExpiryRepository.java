package dev.ngb.loyalty.points.adapter.expiry.persistence;

import static dev.ngb.loyalty_jooq.Tables.POINT_LOTS;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.points.application.expiry.port.DueLot;
import dev.ngb.loyalty.points.application.expiry.port.ExpiryRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * The lots the scheduled runs act on.
 *
 * <p>The two sweeps group by member and order by the earliest lot that member has, so a bounded batch is the work that is
 * most overdue rather than an arbitrary slice. The lot reads take their rows under a lock in ascending id, and the write
 * is conditional on the lot still holding points, so a lot another flow emptied between the sweep and the lock is simply
 * skipped instead of being counted twice.
 */
public class JooqExpiryRepository implements ExpiryRepository {

    private final DSLContext dsl;
    private final TenantContext tenantContext;

    public JooqExpiryRepository(DSLContext dsl, TenantContext tenantContext) {
        this.dsl = dsl;
        this.tenantContext = tenantContext;
    }

    @Override
    public List<UUID> dueMemberIds(Instant now, int limit) {
        return dsl.select(POINT_LOTS.MEMBER_ID)
                .from(POINT_LOTS)
                .where(POINT_LOTS.REMAINING.gt(0L))
                .and(POINT_LOTS.EXPIRES_AT.le(now))
                .groupBy(POINT_LOTS.MEMBER_ID)
                .orderBy(DSL.min(POINT_LOTS.EXPIRES_AT), POINT_LOTS.MEMBER_ID)
                .limit(limit)
                .fetch(POINT_LOTS.MEMBER_ID);
    }

    @Override
    public List<DueLot> lockDueLots(UUID memberId, Instant now) {
        return dsl.select(POINT_LOTS.ID, POINT_LOTS.REMAINING, POINT_LOTS.EXPIRES_AT)
                .from(POINT_LOTS)
                .where(POINT_LOTS.MEMBER_ID.eq(memberId))
                .and(POINT_LOTS.REMAINING.gt(0L))
                .and(POINT_LOTS.EXPIRES_AT.le(now))
                .orderBy(POINT_LOTS.ID)
                .forUpdate()
                .fetch(row -> new DueLot(row.value1(), row.value2(), row.value3()));
    }

    @Override
    public boolean writeOff(UUID lotId) {
        return dsl.update(POINT_LOTS)
                        .set(POINT_LOTS.REMAINING, 0L)
                        .where(POINT_LOTS.ID.eq(lotId))
                        .and(POINT_LOTS.TENANT_ID.eq(tenantId().value()))
                        .and(POINT_LOTS.REMAINING.gt(0L))
                        .execute()
                == 1;
    }

    @Override
    public List<UUID> noticeMemberIds(Instant now, Instant until, int limit) {
        return dsl.select(POINT_LOTS.MEMBER_ID)
                .from(POINT_LOTS)
                .where(POINT_LOTS.REMAINING.gt(0L))
                .and(POINT_LOTS.EXPIRY_NOTICED_AT.isNull())
                .and(POINT_LOTS.EXPIRES_AT.gt(now))
                .and(POINT_LOTS.EXPIRES_AT.le(until))
                .groupBy(POINT_LOTS.MEMBER_ID)
                .orderBy(DSL.min(POINT_LOTS.EXPIRES_AT), POINT_LOTS.MEMBER_ID)
                .limit(limit)
                .fetch(POINT_LOTS.MEMBER_ID);
    }

    @Override
    public List<DueLot> lockNoticeLots(UUID memberId, Instant now, Instant until) {
        return dsl.select(POINT_LOTS.ID, POINT_LOTS.REMAINING, POINT_LOTS.EXPIRES_AT)
                .from(POINT_LOTS)
                .where(POINT_LOTS.MEMBER_ID.eq(memberId))
                .and(POINT_LOTS.REMAINING.gt(0L))
                .and(POINT_LOTS.EXPIRY_NOTICED_AT.isNull())
                .and(POINT_LOTS.EXPIRES_AT.gt(now))
                .and(POINT_LOTS.EXPIRES_AT.le(until))
                .orderBy(POINT_LOTS.ID)
                .forUpdate()
                .fetch(row -> new DueLot(row.value1(), row.value2(), row.value3()));
    }

    @Override
    public void markNoticed(List<UUID> lotIds, Instant at) {
        if (lotIds.isEmpty()) {
            return;
        }
        dsl.update(POINT_LOTS)
                .set(POINT_LOTS.EXPIRY_NOTICED_AT, at)
                .where(POINT_LOTS.ID.in(lotIds))
                .and(POINT_LOTS.TENANT_ID.eq(tenantId().value()))
                .and(POINT_LOTS.EXPIRY_NOTICED_AT.isNull())
                .execute();
    }

    private TenantId tenantId() {
        return tenantContext.requireTenant();
    }
}
