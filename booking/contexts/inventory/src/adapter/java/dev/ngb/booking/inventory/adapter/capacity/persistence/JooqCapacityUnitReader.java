package dev.ngb.booking.inventory.adapter.capacity.persistence;

import static dev.ngb.booking_jooq.Tables.CAPACITY_UNITS;

import dev.ngb.booking.inventory.application.capacity.port.CapacityUnitReader;
import dev.ngb.booking.inventory.domain.capacity.UnitStatus;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/**
 * Counts a pool's places straight out of the rows; row-level security narrows every count to the current tenant.
 *
 * <p>A place is free exactly when its row is {@code AVAILABLE} and committed when it is not, so the numbers cannot
 * disagree with a claim over what the pool is doing.
 */
public class JooqCapacityUnitReader implements CapacityUnitReader {

    private static final String AVAILABLE = UnitStatus.AVAILABLE.name();

    private final DSLContext dsl;

    public JooqCapacityUnitReader(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public int remaining(UUID sessionId, String poolCode) {
        return dsl.fetchCount(CAPACITY_UNITS, pool(sessionId, poolCode).and(CAPACITY_UNITS.STATUS.eq(AVAILABLE)));
    }

    @Override
    public int total(UUID sessionId, String poolCode) {
        return dsl.fetchCount(CAPACITY_UNITS, pool(sessionId, poolCode));
    }

    @Override
    public int committed(UUID sessionId, String poolCode) {
        return dsl.fetchCount(CAPACITY_UNITS, pool(sessionId, poolCode).and(CAPACITY_UNITS.STATUS.ne(AVAILABLE)));
    }

    private static Condition pool(UUID sessionId, String poolCode) {
        return CAPACITY_UNITS.SESSION_ID.eq(sessionId).and(CAPACITY_UNITS.POOL_CODE.eq(poolCode));
    }
}
