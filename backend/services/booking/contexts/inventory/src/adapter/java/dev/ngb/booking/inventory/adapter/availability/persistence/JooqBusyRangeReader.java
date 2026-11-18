package dev.ngb.booking.inventory.adapter.availability.persistence;

import static dev.ngb.booking_jooq.Tables.INTERVAL_CLAIMS;

import dev.ngb.booking.inventory.api.availability.BusyRange;
import dev.ngb.booking.inventory.api.availability.BusyRangeReader;
import dev.ngb.core.runtime.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * Reads the claim rows of one resource that still block. A claim with no expiry is committed and blocks forever; a
 * held one blocks until its expiry plus the grace, after which a later claim reclaims it and it is ignored here. Every
 * statement is tenant-scoped by row-level security.
 */
@SuppressWarnings("deprecation")
public class JooqBusyRangeReader implements BusyRangeReader {

    /** The tenant default grace: a hold lapses this long after its expiry before it stops blocking. */
    private static final long GRACE_SECONDS = 60L;

    private final DSLContext dsl;
    private final Clock clock;

    public JooqBusyRangeReader(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    @Override
    public List<BusyRange> busy(UUID resourceId, Instant from, Instant to) {
        Instant cutoff = clock.now().minusSeconds(GRACE_SECONDS);
        return dsl.select(lower(), upper())
                .from(INTERVAL_CLAIMS)
                .where(INTERVAL_CLAIMS.RESOURCE_ID.eq(resourceId))
                .and(overlaps(from, to))
                .and(INTERVAL_CLAIMS.EXPIRES_AT.isNull().or(INTERVAL_CLAIMS.EXPIRES_AT.ge(cutoff)))
                .fetch(row -> new BusyRange(row.value1().toInstant(), row.value2().toInstant()));
    }

    private static Field<OffsetDateTime> lower() {
        return DSL.field("lower({0})", OffsetDateTime.class, INTERVAL_CLAIMS.DURING);
    }

    private static Field<OffsetDateTime> upper() {
        return DSL.field("upper({0})", OffsetDateTime.class, INTERVAL_CLAIMS.DURING);
    }

    /** Whether {@code during} intersects {@code [from, to)}. */
    private static Condition overlaps(Instant from, Instant to) {
        return DSL.field(
                        "{0} && tstzrange({1}::timestamptz, {2}::timestamptz, '[)')",
                        Boolean.class,
                        INTERVAL_CLAIMS.DURING,
                        bound(from),
                        bound(to))
                .eq(true);
    }

    private static Field<OffsetDateTime> bound(Instant instant) {
        return DSL.val(OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
    }
}
