package dev.ngb.workflow.boot.config;

import dev.ngb.core.runtime.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The bounded purge itself: finished instances and their rows older than the retention, and inbound events past their
 * own expiry. Only a finished instance is eligible; an open one is never deleted, whatever its age.
 *
 * <p>It is the one place that removes another context's rows (a task, an inbound event), because retention is a
 * deployment rule over the whole Tenant rather than a fact of one context. The statements are plain SQL for that reason:
 * the wiring layer reaches no context's generated table type, which is what keeps {@code context-uses-only-own-tables}
 * meaningful.
 */
public class RetentionSweep {

    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final int batchSize;
    private final Duration retention;

    public RetentionSweep(DSLContext dsl, UnitOfWork unitOfWork, int batchSize, Duration retention) {
        this.dsl = dsl;
        this.unitOfWork = unitOfWork;
        this.batchSize = batchSize;
        this.retention = retention;
    }

    /** Purges what is eligible as of {@code now}, and answers how many instances it removed. */
    public int purge(Instant now) {
        Instant cutoff = now.minus(retention);
        return unitOfWork.transactional(() -> {
            List<UUID> finished = dsl.fetch(
                            """
                            select id from workflow_instance
                            where finished_at < ? and status in
                                ('COMPLETED', 'FAILED', 'FAILED_STUCK', 'CANCELLED', 'COMPENSATION_FAILED')
                            order by finished_at limit ?
                            """,
                            java.sql.Timestamp.from(cutoff),
                            batchSize)
                    .getValues(0, UUID.class);
            for (UUID id : finished) {
                dsl.execute(
                        "delete from workflow_task_assignee where task_id in "
                                + "(select id from workflow_task where instance_id = ?)",
                        id);
                dsl.execute("delete from workflow_task where instance_id = ?", id);
                dsl.execute("delete from workflow_compensation_run where instance_id = ?", id);
                dsl.execute("delete from workflow_join_state where instance_id = ?", id);
                dsl.execute("delete from workflow_loop_state where instance_id = ?", id);
                dsl.execute("delete from workflow_timer where instance_id = ?", id);
                dsl.execute("delete from workflow_wait_subscription where instance_id = ?", id);
                dsl.execute("delete from workflow_step_run where instance_id = ?", id);
                dsl.execute("delete from workflow_token where instance_id = ?", id);
                dsl.execute("delete from workflow_instance where id = ?", id);
            }
            dsl.execute(
                    "delete from workflow_inbound_event where expires_at < ?",
                    java.sql.Timestamp.from(now));
            return finished.size();
        });
    }
}
