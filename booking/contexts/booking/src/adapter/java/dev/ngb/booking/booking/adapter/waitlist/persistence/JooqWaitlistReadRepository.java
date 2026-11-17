package dev.ngb.booking.booking.adapter.waitlist.persistence;

import static dev.ngb.booking_jooq.Tables.WAITLIST_ENTRIES;

import dev.ngb.booking.booking.application.waitlist.query.WaitlistReadRepository;
import dev.ngb.booking.booking.application.waitlist.query.WaitlistView;
import dev.ngb.booking_jooq.tables.records.WaitlistEntriesRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Condition;

/** Selects waiting entries straight into their views; a row-level policy narrows every statement to the tenant. */
public class JooqWaitlistReadRepository implements WaitlistReadRepository {

    private static final String WAITING = "WAITING";

    private final DSLContext dsl;

    public JooqWaitlistReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<WaitlistView> list() {
        return dsl.selectFrom(WAITLIST_ENTRIES)
                .orderBy(WAITLIST_ENTRIES.CREATED_AT.desc(), WAITLIST_ENTRIES.ID.desc())
                .fetch(this::toView);
    }

    @Override
    public Optional<WaitlistView> findById(UUID entryId) {
        return dsl.selectFrom(WAITLIST_ENTRIES)
                .where(WAITLIST_ENTRIES.ID.eq(entryId))
                .fetchOptional(this::toView);
    }

    private WaitlistView toView(WaitlistEntriesRecord row) {
        return new WaitlistView(
                row.getId(),
                row.getOfferingId(),
                row.getCustomerRef(),
                row.getQty(),
                row.getStatus(),
                position(row),
                row.getSessionId(),
                row.getPoolCode(),
                row.getDesiredStartsAt(),
                row.getDesiredEndsAt(),
                row.getOfferedBookingId(),
                row.getOfferedUntil(),
                row.getCreatedAt());
    }

    /** How many waiting entries of the same target were created before this one. */
    private int position(WaitlistEntriesRecord row) {
        if (!WAITING.equals(row.getStatus())) {
            return 0;
        }
        return dsl.fetchCount(
                WAITLIST_ENTRIES,
                sameTarget(row)
                        .and(WAITLIST_ENTRIES.STATUS.eq(WAITING))
                        .and(WAITLIST_ENTRIES.CREATED_AT.lt(row.getCreatedAt())
                                .or(WAITLIST_ENTRIES.CREATED_AT.eq(row.getCreatedAt())
                                        .and(WAITLIST_ENTRIES.ID.lt(row.getId())))));
    }

    private static Condition sameTarget(WaitlistEntriesRecord row) {
        if (row.getSessionId() != null) {
            return WAITLIST_ENTRIES.SESSION_ID.eq(row.getSessionId());
        }
        return WAITLIST_ENTRIES.OFFERING_ID.eq(row.getOfferingId())
                .and(WAITLIST_ENTRIES.SESSION_ID.isNull());
    }
}
