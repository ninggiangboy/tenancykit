package dev.ngb.booking.booking.adapter.waitlist.persistence;

import static dev.ngb.booking_jooq.Tables.WAITLIST_ENTRIES;
import static dev.ngb.booking_jooq.Tables.WAITLIST_WAKEUPS;

import dev.ngb.booking.booking.application.waitlist.port.WaitlistRepository;
import dev.ngb.booking.booking.domain.waitlist.WaitlistEntry;
import dev.ngb.booking.booking.domain.waitlist.WaitlistStatus;
import dev.ngb.booking_jooq.tables.records.WaitlistEntriesRecord;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

/** Stores waiting entries; row-level security narrows every statement to the current tenant. */
public class JooqWaitlistRepository implements WaitlistRepository {

    private static final String WAITING = WaitlistStatus.WAITING.name();

    private final DSLContext dsl;
    private final TenantContext tenantContext;
    private final Clock clock;

    public JooqWaitlistRepository(DSLContext dsl, TenantContext tenantContext, Clock clock) {
        this.dsl = dsl;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Override
    public boolean insertIfAbsent(WaitlistEntry entry) {
        return dsl.insertInto(WAITLIST_ENTRIES)
                        .set(WAITLIST_ENTRIES.TENANT_ID, entry.tenantId().value())
                        .set(WAITLIST_ENTRIES.ID, entry.id())
                        .set(WAITLIST_ENTRIES.OFFERING_ID, entry.offeringId())
                        .set(WAITLIST_ENTRIES.CUSTOMER_REF, entry.customerRef())
                        .set(WAITLIST_ENTRIES.SESSION_ID, entry.sessionId())
                        .set(WAITLIST_ENTRIES.POOL_CODE, entry.poolCode())
                        .set(WAITLIST_ENTRIES.DESIRED_STARTS_AT, entry.desiredStartsAt())
                        .set(WAITLIST_ENTRIES.DESIRED_ENDS_AT, entry.desiredEndsAt())
                        .set(WAITLIST_ENTRIES.QTY, entry.qty())
                        .set(WAITLIST_ENTRIES.RESOURCE_ID, entry.resourceId())
                        .set(WAITLIST_ENTRIES.STATUS, entry.status().name())
                        .set(WAITLIST_ENTRIES.CREATED_AT, entry.createdAt())
                        .set(WAITLIST_ENTRIES.UPDATED_AT, entry.updatedAt())
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }

    @Override
    public Optional<WaitlistEntry> lockById(UUID entryId) {
        return dsl.selectFrom(WAITLIST_ENTRIES)
                .where(WAITLIST_ENTRIES.ID.eq(entryId))
                .forUpdate()
                .fetchOptional(JooqWaitlistRepository::toEntry);
    }

    @Override
    public List<WaitlistEntry> lockWaiting(String scope, int limit) {
        return dsl.selectFrom(WAITLIST_ENTRIES)
                .where(scopeCondition(scope))
                .and(WAITLIST_ENTRIES.STATUS.eq(WAITING))
                .orderBy(WAITLIST_ENTRIES.CREATED_AT, WAITLIST_ENTRIES.ID)
                .limit(limit)
                .forUpdate()
                .fetch(JooqWaitlistRepository::toEntry);
    }

    @Override
    public List<String> waitingScopes(int limit) {
        Field<String> scope = scopeField();
        return dsl.select(scope)
                .from(WAITLIST_ENTRIES)
                .where(WAITLIST_ENTRIES.STATUS.eq(WAITING))
                .groupBy(scope)
                .orderBy(DSL.min(WAITLIST_ENTRIES.CREATED_AT))
                .limit(limit)
                .fetch(scope);
    }

    @Override
    public int waitingAhead(WaitlistEntry entry) {
        return dsl.fetchCount(
                WAITLIST_ENTRIES,
                scopeCondition(entry.scope())
                        .and(WAITLIST_ENTRIES.STATUS.eq(WAITING))
                        .and(WAITLIST_ENTRIES.CREATED_AT.lt(entry.createdAt())
                                .or(WAITLIST_ENTRIES.CREATED_AT.eq(entry.createdAt())
                                        .and(WAITLIST_ENTRIES.ID.lt(entry.id())))));
    }

    @Override
    public int waitingCount(String scope) {
        return dsl.fetchCount(
                WAITLIST_ENTRIES, scopeCondition(scope).and(WAITLIST_ENTRIES.STATUS.eq(WAITING)));
    }

    @Override
    public void update(WaitlistEntry entry) {
        dsl.update(WAITLIST_ENTRIES)
                .set(WAITLIST_ENTRIES.STATUS, entry.status().name())
                .set(WAITLIST_ENTRIES.OFFERED_BOOKING_ID, entry.offeredBookingId())
                .set(WAITLIST_ENTRIES.OFFERED_UNTIL, entry.offeredUntil())
                .set(WAITLIST_ENTRIES.UPDATED_AT, entry.updatedAt())
                .where(WAITLIST_ENTRIES.ID.eq(entry.id()))
                .execute();
    }

    @Override
    public Optional<WaitlistEntry> lockByOfferedBooking(UUID bookingId) {
        return dsl.selectFrom(WAITLIST_ENTRIES)
                .where(WAITLIST_ENTRIES.OFFERED_BOOKING_ID.eq(bookingId))
                .forUpdate()
                .fetchOptional(JooqWaitlistRepository::toEntry);
    }

    @Override
    public void wakeup(String scope) {
        dsl.insertInto(WAITLIST_WAKEUPS)
                .set(WAITLIST_WAKEUPS.TENANT_ID, tenantContext.requireTenant().value())
                .set(WAITLIST_WAKEUPS.ID, UUID.randomUUID())
                .set(WAITLIST_WAKEUPS.SCOPE, scope)
                .set(WAITLIST_WAKEUPS.CREATED_AT, clock.now())
                .execute();
    }

    @Override
    public List<String> claimWakeups(int limit) {
        List<UUID> ids = dsl.select(WAITLIST_WAKEUPS.ID)
                .from(WAITLIST_WAKEUPS)
                .orderBy(WAITLIST_WAKEUPS.CREATED_AT)
                .limit(limit)
                .forUpdate()
                .skipLocked()
                .fetch(WAITLIST_WAKEUPS.ID);
        if (ids.isEmpty()) {
            return List.of();
        }
        return dsl.deleteFrom(WAITLIST_WAKEUPS)
                .where(WAITLIST_WAKEUPS.ID.in(ids))
                .returning(WAITLIST_WAKEUPS.SCOPE)
                .fetch(WAITLIST_WAKEUPS.SCOPE);
    }

    private static Field<String> scopeField() {
        return DSL.field(
                "case when {0} is not null then 'session:' || {0} else 'offering:' || {1} end",
                String.class,
                WAITLIST_ENTRIES.SESSION_ID,
                WAITLIST_ENTRIES.OFFERING_ID);
    }

    private static org.jooq.Condition scopeCondition(String scope) {
        String value = scope.startsWith("session:") ? scope.substring("session:".length()) : null;
        if (value != null) {
            return WAITLIST_ENTRIES.SESSION_ID.eq(UUID.fromString(value));
        }
        return WAITLIST_ENTRIES.OFFERING_ID.eq(UUID.fromString(scope.substring("offering:".length())))
                .and(WAITLIST_ENTRIES.SESSION_ID.isNull());
    }

    private static WaitlistEntry toEntry(WaitlistEntriesRecord row) {
        return WaitlistEntry.restore(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getOfferingId(),
                row.getCustomerRef(),
                row.getSessionId(),
                row.getPoolCode(),
                row.getDesiredStartsAt(),
                row.getDesiredEndsAt(),
                row.getQty(),
                row.getResourceId(),
                WaitlistStatus.valueOf(row.getStatus()),
                row.getOfferedBookingId(),
                row.getOfferedUntil(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
