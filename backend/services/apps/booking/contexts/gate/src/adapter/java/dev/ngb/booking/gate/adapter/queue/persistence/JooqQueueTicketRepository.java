package dev.ngb.booking.gate.adapter.queue.persistence;

import static dev.ngb.booking_jooq.Tables.QUEUE_STATE;
import static dev.ngb.booking_jooq.Tables.QUEUE_TICKETS;

import dev.ngb.booking.gate.application.queue.port.QueueTicketRepository;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** The insert-only tickets and the one watermark row per target; row-level security narrows both to the tenant. */
public class JooqQueueTicketRepository implements QueueTicketRepository {

    private final DSLContext dsl;
    private final TenantContext tenantContext;

    public JooqQueueTicketRepository(DSLContext dsl, TenantContext tenantContext) {
        this.dsl = dsl;
        this.tenantContext = tenantContext;
    }

    @Override
    public long nextTicketNo() {
        Long value = dsl.select(DSL.field("nextval('queue_ticket_seq')", Long.class))
                .fetchOne(0, Long.class);
        return value == null ? 0 : value;
    }

    @Override
    public boolean insert(String targetId, long ticketNo, String customerRef, Instant now) {
        return dsl.insertInto(QUEUE_TICKETS)
                        .set(QUEUE_TICKETS.TENANT_ID, tenantContext.requireTenant().value())
                        .set(QUEUE_TICKETS.ID, UUID.randomUUID())
                        .set(QUEUE_TICKETS.TARGET_ID, targetId)
                        .set(QUEUE_TICKETS.TICKET_NO, ticketNo)
                        .set(QUEUE_TICKETS.CUSTOMER_REF, customerRef)
                        .set(QUEUE_TICKETS.STATUS, "WAITING")
                        .set(QUEUE_TICKETS.ISSUED_AT, now)
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }

    @Override
    public Optional<State> state(String targetId) {
        return dsl.selectFrom(QUEUE_STATE)
                .where(QUEUE_STATE.TARGET_ID.eq(targetId))
                .fetchOptional(row -> new State(
                        row.getTargetId(),
                        row.getWatermarkTicketNo(),
                        row.getAdmitPerSecond(),
                        row.getAdmitWindowSeconds(),
                        row.getUpdatedAt()));
    }

    @Override
    public void ensureState(String targetId, int admitPerSecond, int admitWindowSeconds, Instant now) {
        dsl.insertInto(QUEUE_STATE)
                .set(QUEUE_STATE.TENANT_ID, tenantContext.requireTenant().value())
                .set(QUEUE_STATE.TARGET_ID, targetId)
                .set(QUEUE_STATE.WATERMARK_TICKET_NO, 0L)
                .set(QUEUE_STATE.ADMIT_PER_SECOND, admitPerSecond)
                .set(QUEUE_STATE.ADMIT_WINDOW_SECONDS, admitWindowSeconds)
                .set(QUEUE_STATE.UPDATED_AT, now)
                .onConflictDoNothing()
                .execute();
    }

    @Override
    public int admit(String targetId, int limit, Instant now) {
        List<Long> due = dsl.select(QUEUE_TICKETS.TICKET_NO)
                .from(QUEUE_TICKETS)
                .where(QUEUE_TICKETS.TARGET_ID.eq(targetId))
                .and(QUEUE_TICKETS.STATUS.eq("WAITING"))
                .orderBy(QUEUE_TICKETS.TICKET_NO)
                .limit(limit)
                .forUpdate()
                .skipLocked()
                .fetch(QUEUE_TICKETS.TICKET_NO);
        if (due.isEmpty()) {
            return 0;
        }
        int admitted = dsl.update(QUEUE_TICKETS)
                .set(QUEUE_TICKETS.STATUS, "ADMITTED")
                .set(QUEUE_TICKETS.ADMITTED_AT, now)
                .where(QUEUE_TICKETS.TARGET_ID.eq(targetId))
                .and(QUEUE_TICKETS.TICKET_NO.in(due))
                .and(QUEUE_TICKETS.STATUS.eq("WAITING"))
                .execute();
        long highest = due.stream().mapToLong(Long::longValue).max().orElse(0);
        dsl.update(QUEUE_STATE)
                .set(QUEUE_STATE.WATERMARK_TICKET_NO, highest)
                .set(QUEUE_STATE.UPDATED_AT, now)
                .where(QUEUE_STATE.TARGET_ID.eq(targetId))
                .execute();
        return admitted;
    }

    @Override
    public Optional<State> admittedState(String targetId, Instant now) {
        return state(targetId);
    }

    @Override
    public int expireAdmitted(String targetId, Instant cutoff, Instant now) {
        return dsl.update(QUEUE_TICKETS)
                .set(QUEUE_TICKETS.STATUS, "EXPIRED")
                .where(QUEUE_TICKETS.TARGET_ID.eq(targetId))
                .and(QUEUE_TICKETS.STATUS.eq("ADMITTED"))
                .and(QUEUE_TICKETS.ADMITTED_AT.lt(cutoff))
                .execute();
    }

    @Override
    public boolean markUsedIfAdmitted(String targetId, long ticketNo, Instant cutoff) {
        return dsl.update(QUEUE_TICKETS)
                        .set(QUEUE_TICKETS.STATUS, "USED")
                        .where(QUEUE_TICKETS.TARGET_ID.eq(targetId))
                        .and(QUEUE_TICKETS.TICKET_NO.eq(ticketNo))
                        .and(QUEUE_TICKETS.STATUS.eq("ADMITTED"))
                        .and(QUEUE_TICKETS.ADMITTED_AT.ge(cutoff))
                        .execute()
                == 1;
    }

    @Override
    public void revertUsed(String targetId, long ticketNo) {
        dsl.update(QUEUE_TICKETS)
                .set(QUEUE_TICKETS.STATUS, "ADMITTED")
                .where(QUEUE_TICKETS.TARGET_ID.eq(targetId))
                .and(QUEUE_TICKETS.TICKET_NO.eq(ticketNo))
                .and(QUEUE_TICKETS.STATUS.eq("USED"))
                .execute();
    }

    @Override
    public void markLeft(String targetId, long ticketNo) {
        dsl.update(QUEUE_TICKETS)
                .set(QUEUE_TICKETS.STATUS, "LEFT")
                .where(QUEUE_TICKETS.TARGET_ID.eq(targetId))
                .and(QUEUE_TICKETS.TICKET_NO.eq(ticketNo))
                .and(QUEUE_TICKETS.STATUS.in("WAITING", "ADMITTED"))
                .execute();
    }

    @Override
    public long watermark(String targetId) {
        return dsl.select(QUEUE_STATE.WATERMARK_TICKET_NO)
                .from(QUEUE_STATE)
                .where(QUEUE_STATE.TARGET_ID.eq(targetId))
                .fetchOptional(QUEUE_STATE.WATERMARK_TICKET_NO)
                .orElse(0L);
    }

    @Override
    public int purgeBefore(Instant cutoff) {
        return dsl.deleteFrom(QUEUE_TICKETS)
                .where(QUEUE_TICKETS.ISSUED_AT.lt(cutoff))
                .execute();
    }

    @Override
    public List<String> targetsWithWaiting() {
        return dsl.selectDistinct(QUEUE_TICKETS.TARGET_ID)
                .from(QUEUE_TICKETS)
                .where(QUEUE_TICKETS.STATUS.eq("WAITING"))
                .fetch(QUEUE_TICKETS.TARGET_ID);
    }
}
