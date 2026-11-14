package dev.ngb.core.replica;

import java.time.Instant;
import java.time.ZoneOffset;
import org.jooq.DSLContext;

/**
 * The replica's own rebuild state: one row recording when a full rebuild last finished every stream.
 *
 * <p>A rebuild that fails after its first stream writes some rows but no marker, so the next boot sees "not rebuilt"
 * and runs the whole rebuild again. Counting rows per stream cannot make that distinction: a partially filled replica
 * and a complete one look the same through any single stream's row count.
 */
public class ReplicaState {

    private final DSLContext dsl;

    public ReplicaState(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** True when a full rebuild has finished at least once. */
    public boolean rebuilt() {
        return dsl.fetchCount(ReplicaTables.REPLICA_STATE) > 0;
    }

    /** Records that every stream finished; the caller runs this in its own transaction. */
    public void markRebuilt(Instant at) {
        dsl.insertInto(ReplicaTables.REPLICA_STATE)
                .set(ReplicaTables.REPLICA_STATE_ID, (short) 1)
                .set(ReplicaTables.REPLICA_STATE_REBUILT_AT, at.atOffset(ZoneOffset.UTC))
                .onConflict(ReplicaTables.REPLICA_STATE_ID)
                .doUpdate()
                .set(ReplicaTables.REPLICA_STATE_REBUILT_AT, at.atOffset(ZoneOffset.UTC))
                .execute();
    }
}
