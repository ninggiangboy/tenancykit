package dev.ngb.booking.inventory.adapter.capacity.persistence;

import static dev.ngb.booking_jooq.Tables.CAPACITY_UNITS;

import dev.ngb.booking.catalog.api.session.SessionPlaces;
import dev.ngb.booking.inventory.application.capacity.service.CapacityPlanner;
import dev.ngb.booking.inventory.domain.capacity.UnitStatus;
import dev.ngb.core.tenancy.TenantContext;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.impl.DSL;

/**
 * The places of a session's pools, one row per place.
 *
 * <p>A pool gains its places in one statement, so a session of five hundred places is one insert rather than five
 * hundred, and growing a pool adds exactly the places it gained. Shrinking removes free rows only and refuses a
 * target below the places a booking holds or has booked, which is what keeps a resize from overselling a pool.
 */
public class CapacitySessionPlaces implements SessionPlaces {

    private static final String INSERT_PLACES = """
            insert into capacity_units (tenant_id, id, session_id, pool_code, status)
            select ?, gen_random_uuid(), ?, ?, 'AVAILABLE' from generate_series(1, ?)
            """;

    private static final String AVAILABLE = UnitStatus.AVAILABLE.name();

    private final DSLContext dsl;
    private final TenantContext tenantContext;
    private final CapacityPlanner planner;

    public CapacitySessionPlaces(DSLContext dsl, TenantContext tenantContext, CapacityPlanner planner) {
        this.dsl = dsl;
        this.tenantContext = tenantContext;
        this.planner = planner;
    }

    @Override
    public void createPlaces(UUID sessionId, String poolCode, int capacity) {
        if (capacity < 1) {
            return;
        }
        insertPlaces(sessionId, poolCode, capacity);
    }

    @Override
    public void resizePlaces(UUID sessionId, String poolCode, int capacity) {
        Counts counts = counts(sessionId, poolCode);
        CapacityPlanner.Plan plan = planner.plan(capacity, counts.total(), counts.committed());
        if (plan.insert() > 0) {
            insertPlaces(sessionId, poolCode, plan.insert());
        }
        if (plan.delete() > 0) {
            deleteFreePlaces(sessionId, poolCode, plan.delete());
        }
    }

    @Override
    public void deletePlaces(UUID sessionId, String poolCode) {
        dsl.deleteFrom(CAPACITY_UNITS)
                .where(CAPACITY_UNITS.SESSION_ID.eq(sessionId))
                .and(CAPACITY_UNITS.POOL_CODE.eq(poolCode))
                .and(CAPACITY_UNITS.STATUS.eq(AVAILABLE))
                .execute();
    }

    @Override
    public int remaining(UUID sessionId, String poolCode) {
        return dsl.fetchCount(CAPACITY_UNITS, CAPACITY_UNITS.SESSION_ID.eq(sessionId)
                .and(CAPACITY_UNITS.POOL_CODE.eq(poolCode))
                .and(CAPACITY_UNITS.STATUS.eq(AVAILABLE)));
    }

    private void insertPlaces(UUID sessionId, String poolCode, int places) {
        dsl.execute(
                INSERT_PLACES, tenantContext.requireTenant().value(), sessionId, poolCode, places);
    }

    /** How many places the pool has and how many of them a booking holds or has booked, in one statement. */
    private Counts counts(UUID sessionId, String poolCode) {
        int total = 0;
        int committed = 0;
        for (Record2<String, Integer> row : dsl.select(CAPACITY_UNITS.STATUS, DSL.count())
                .from(CAPACITY_UNITS)
                .where(CAPACITY_UNITS.SESSION_ID.eq(sessionId))
                .and(CAPACITY_UNITS.POOL_CODE.eq(poolCode))
                .groupBy(CAPACITY_UNITS.STATUS)
                .fetch()) {
            total += row.value2();
            if (!AVAILABLE.equals(row.value1())) {
                committed += row.value2();
            }
        }
        return new Counts(total, committed);
    }

    private void deleteFreePlaces(UUID sessionId, String poolCode, int places) {
        dsl.deleteFrom(CAPACITY_UNITS)
                .where(CAPACITY_UNITS.ID.in(dsl.select(CAPACITY_UNITS.ID)
                        .from(CAPACITY_UNITS)
                        .where(CAPACITY_UNITS.SESSION_ID.eq(sessionId))
                        .and(CAPACITY_UNITS.POOL_CODE.eq(poolCode))
                        .and(CAPACITY_UNITS.STATUS.eq(AVAILABLE))
                        .orderBy(CAPACITY_UNITS.ID)
                        .limit(places)))
                .execute();
    }

    private record Counts(int total, int committed) {}
}
