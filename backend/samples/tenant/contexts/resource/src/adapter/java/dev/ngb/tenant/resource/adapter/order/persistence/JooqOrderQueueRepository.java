package dev.ngb.tenant.resource.adapter.order.persistence;

import static dev.ngb.tenant_jooq.Tables.EXECUTION_QUEUE;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.tenant.resource.api.execution.ExecutionQueue.Claimed;
import dev.ngb.tenant.resource.application.order.port.OrderQueueRepository;
import dev.ngb.tenant.resource.domain.order.QueueStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/** The queue this module owns; a claim is one statement that locks its rows with skip locked. */
public class JooqOrderQueueRepository implements OrderQueueRepository {

    private final DSLContext dsl;
    private final Clock clock;
    private final IdGenerator ids;

    public JooqOrderQueueRepository(DSLContext dsl, Clock clock, IdGenerator ids) {
        this.dsl = dsl;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    public void enqueue(UUID orderId, Instant enqueuedAt) {
        dsl.insertInto(EXECUTION_QUEUE)
                .columns(
                        EXECUTION_QUEUE.ID,
                        EXECUTION_QUEUE.ORDER_ID,
                        EXECUTION_QUEUE.STATUS,
                        EXECUTION_QUEUE.ENQUEUED_AT,
                        EXECUTION_QUEUE.ATTEMPTS)
                .values(ids.newId(), orderId, QueueStatus.PENDING.name(), enqueuedAt, 0)
                .execute();
    }

    @Override
    public List<Claimed> claim(int batchSize) {
        // skip locked: a row another worker is claiming is passed over instead of waited for.
        var next = dsl.select(EXECUTION_QUEUE.ID)
                .from(EXECUTION_QUEUE)
                .where(EXECUTION_QUEUE.STATUS.eq(QueueStatus.PENDING.name()))
                .orderBy(EXECUTION_QUEUE.ENQUEUED_AT)
                .limit(batchSize)
                .forUpdate()
                .skipLocked();
        return dsl.update(EXECUTION_QUEUE)
                .set(EXECUTION_QUEUE.STATUS, QueueStatus.RUNNING.name())
                .set(EXECUTION_QUEUE.CLAIMED_AT, clock.now())
                .set(EXECUTION_QUEUE.ATTEMPTS, EXECUTION_QUEUE.ATTEMPTS.plus(1))
                .where(EXECUTION_QUEUE.ID.in(next))
                .returning(EXECUTION_QUEUE.ORDER_ID, EXECUTION_QUEUE.ATTEMPTS)
                .fetch(row -> new Claimed(row.getOrderId(), row.getAttempts()));
    }

    @Override
    public void finish(UUID orderId) {
        int finished = dsl.update(EXECUTION_QUEUE)
                .set(EXECUTION_QUEUE.STATUS, QueueStatus.DONE.name())
                .set(EXECUTION_QUEUE.FINISHED_AT, clock.now())
                .where(EXECUTION_QUEUE.ORDER_ID.eq(orderId))
                .and(EXECUTION_QUEUE.STATUS.eq(QueueStatus.RUNNING.name()))
                .execute();
        requireClaimed(finished, orderId);
    }

    @Override
    public void requeue(UUID orderId) {
        int requeued = dsl.update(EXECUTION_QUEUE)
                .set(EXECUTION_QUEUE.STATUS, QueueStatus.PENDING.name())
                .setNull(EXECUTION_QUEUE.CLAIMED_AT)
                .where(EXECUTION_QUEUE.ORDER_ID.eq(orderId))
                .and(EXECUTION_QUEUE.STATUS.eq(QueueStatus.RUNNING.name()))
                .execute();
        requireClaimed(requeued, orderId);
    }

    private static void requireClaimed(int updated, UUID orderId) {
        if (updated != 1) {
            throw new IllegalStateException("the queue row of order " + orderId + " is not the one this worker claimed");
        }
    }
}
