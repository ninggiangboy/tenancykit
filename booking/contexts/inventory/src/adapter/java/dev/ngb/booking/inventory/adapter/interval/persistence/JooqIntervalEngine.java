package dev.ngb.booking.inventory.adapter.interval.persistence;

import static dev.ngb.booking_jooq.Tables.INTERVAL_CLAIMS;

import dev.ngb.booking.inventory.api.engine.ClaimOutcome;
import dev.ngb.booking.inventory.api.engine.ClaimRequest;
import dev.ngb.booking.inventory.api.engine.Claimed;
import dev.ngb.booking.inventory.api.engine.Conflict;
import dev.ngb.booking.inventory.api.engine.IntervalClaim;
import dev.ngb.booking.inventory.api.engine.InventoryEngine;
import dev.ngb.booking.inventory.application.interval.service.IntervalClaimRules;
import dev.ngb.booking.inventory.domain.interval.ClaimRange;
import dev.ngb.core.tenancy.TenantContext;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Claims a half-open range on one resource, with no two claims of a resource overlapping.
 *
 * <p>The promise is the table's exclusion constraint; this class only folds the buffers in, clears the same resource's
 * lapsed holds over the range before it inserts, and turns the constraint's refusal into the {@code slot-taken} a
 * caller can act on. The lapsed-hold deletion and the insert run in the caller's one tenant-scoped transaction, so a
 * conflicting insert rolls the deletion back with it.
 */
@SuppressWarnings("deprecation")
public class JooqIntervalEngine implements InventoryEngine {

    private static final String EXCLUSION_VIOLATION = "23P01";

    private final DSLContext dsl;
    private final TenantContext tenantContext;
    private final IntervalClaimRules rules;

    public JooqIntervalEngine(DSLContext dsl, TenantContext tenantContext, IntervalClaimRules rules) {
        this.dsl = dsl;
        this.tenantContext = tenantContext;
        this.rules = rules;
    }

    @Override
    public Capability capability() {
        return Capability.INTERVAL_NO_OVERLAP;
    }

    @Override
    public ClaimOutcome claim(ClaimRequest request) {
        if (!(request instanceof IntervalClaim claim)) {
            throw new IllegalArgumentException("the interval engine claims a range on one resource");
        }
        ClaimRange range = rules.bufferedRange(
                claim.start(), claim.end(), claim.bufferBeforeSeconds(), claim.bufferAfterSeconds());
        UUID id = UUID.randomUUID();
        try {
            dsl.deleteFrom(INTERVAL_CLAIMS)
                    .where(INTERVAL_CLAIMS.RESOURCE_ID.eq(claim.resourceId()))
                    .and(INTERVAL_CLAIMS.EXPIRES_AT.isNotNull())
                    .and(INTERVAL_CLAIMS.EXPIRES_AT.lt(claim.now().minusSeconds(claim.graceSeconds())))
                    .and(overlaps(range.start(), range.end()))
                    .execute();
            dsl.execute(
                    """
                    insert into interval_claims
                        (tenant_id, id, resource_id, booking_id, during, expires_at, created_at)
                    values (?, ?, ?, ?, tstzrange(?::timestamptz, ?::timestamptz, '[)'), ?::timestamptz, ?::timestamptz)
                    """,
                    tenantContext.requireTenant().value(),
                    id,
                    claim.resourceId(),
                    claim.bookingId(),
                    OffsetDateTime.ofInstant(range.start(), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(range.end(), ZoneOffset.UTC),
                    claim.expiresAt() == null
                            ? null
                            : OffsetDateTime.ofInstant(claim.expiresAt(), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(claim.now(), ZoneOffset.UTC));
        } catch (DataIntegrityViolationException e) {
            if (!isExclusionViolation(e)) {
                throw e;
            }
            return new Conflict(Conflict.SLOT_TAKEN, "The range is already claimed.", null);
        }
        return new Claimed(claim.bookingId(), id, claim.expiresAt());
    }

    @Override
    public void confirm(UUID bookingId) {
        dsl.update(INTERVAL_CLAIMS)
                .set(INTERVAL_CLAIMS.EXPIRES_AT, (Instant) null)
                .where(INTERVAL_CLAIMS.BOOKING_ID.eq(bookingId))
                .execute();
    }

    @Override
    public void release(UUID bookingId) {
        dsl.deleteFrom(INTERVAL_CLAIMS)
                .where(INTERVAL_CLAIMS.BOOKING_ID.eq(bookingId))
                .execute();
    }

    @Override
    public void extend(UUID bookingId, Instant until) {
        dsl.update(INTERVAL_CLAIMS)
                .set(INTERVAL_CLAIMS.EXPIRES_AT, until)
                .where(INTERVAL_CLAIMS.BOOKING_ID.eq(bookingId))
                .execute();
    }

    /** Whether {@code during} intersects {@code [start, end)}. */
    private static Condition overlaps(Instant start, Instant end) {
        return DSL.field(
                        "{0} && tstzrange({1}::timestamptz, {2}::timestamptz, '[)')",
                        Boolean.class,
                        INTERVAL_CLAIMS.DURING,
                        bound(start),
                        bound(end))
                .eq(true);
    }

    private static Field<OffsetDateTime> bound(Instant instant) {
        return DSL.val(OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    private static boolean isExclusionViolation(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
