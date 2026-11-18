package dev.ngb.core.sync;

import dev.ngb.core.replica.ReplicaState;
import dev.ngb.core.replica.ReplicaStream;
import dev.ngb.core.replica.ReplicaWriter;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Loads the snapshots a fresh replica needs, then starts the platform-events listener container.
 *
 * <p>The container is configured with {@code autoStartup=false} and is started here, after the rebuild. Messages that
 * arrive while the snapshot is being paged wait in the durable queue and are applied afterwards; the version guard
 * drops any the snapshot already covered. The rebuild is "upsert everything", never "truncate then load", because
 * Platform never hard-deletes these rows and the app's business tables reference them.
 *
 * <p>Each page is applied in its own transaction, so a long stream never holds one transaction (or one pooled
 * connection) for the whole rebuild, and a failure loses at most one page. Every stream is logged separately.
 *
 * <p>Whether a rebuild is needed is answered by the completion marker, not by the row count of any one stream. A
 * rebuild that fails after its first stream has already written rows, so "the tenants table is not empty" is true for
 * both a complete replica and one that stopped one stream in. The marker is written only after every stream finished,
 * so a failed rebuild is retried in full on the next boot; the upserts make a repeat harmless. The tenant count stays
 * as a secondary condition, for a replica whose rows were lost without the marker being cleared: deleting the
 * {@code replica_state} row is how an operator forces a rebuild after any partial restore.
 *
 * <p>The limitation is deliberate: a rebuild is not detected when only a child table loses its rows (say
 * {@code tenant_domains}) while {@code tenants} stays populated. Treating that as an operational incident to force a
 * rebuild on is cheaper than reading the whole replica on every boot.
 */
public class ReplicaBootstrap implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ReplicaBootstrap.class);

    /** Starts the listener container; the boot layer points it at the platform-events queue. */
    public interface ListenerContainer {

        void start();
    }

    private final ReplicaProperties properties;
    private final ReplicaWriter writer;
    private final ReplicaState state;
    private final SnapshotClient snapshots;
    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final ListenerContainer listener;
    private final Clock clock;

    public ReplicaBootstrap(
            ReplicaProperties properties,
            ReplicaWriter writer,
            ReplicaState state,
            SnapshotClient snapshots,
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            ListenerContainer listener,
            Clock clock) {
        this.properties = properties;
        this.writer = writer;
        this.state = state;
        this.snapshots = snapshots;
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.listener = listener;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.replica().rebuildOnStart() && needsRebuild()) {
            rebuild();
        }
        listener.start();
    }

    /** Rebuilds until a full rebuild has finished, or when the tenants table lost its non-platform rows. */
    public boolean needsRebuild() {
        return tenantContext.callAs(RequestScope.platform(), () -> !state.rebuilt()
                || unitOfWork.transactional(writer::tenantsMissing));
    }

    /** Pages every stream and upserts each page; Platform's own scope is bound, because the replica is global data. */
    public int rebuild() {
        AtomicInteger applied = new AtomicInteger();
        for (ReplicaStream stream : ReplicaStream.values()) {
            AtomicInteger rows = new AtomicInteger();
            int pages = snapshots.fetchPages(stream.streamName(), properties.replica().pageLimit(), page -> {
                rows.addAndGet(page.items().size());
                applied.addAndGet(applyPage(stream, page.items()));
            });
            LOG.info("replica stream {} rebuilt: {} page(s), {} row(s)", stream.streamName(), pages, rows.get());
        }
        markRebuilt();
        LOG.info("replica rebuild applied {} row(s)", applied.get());
        return applied.get();
    }

    /**
     * Records that every stream finished. It runs in its own transaction after the pages committed, so a rebuild that
     * throws above leaves no marker and is retried on the next boot.
     */
    private void markRebuilt() {
        tenantContext.callAs(RequestScope.platform(), () -> unitOfWork.transactional(() -> {
            state.markRebuilt(clock.now());
            return null;
        }));
    }

    /**
     * Upserts one page in its own transaction, so the rebuild never spans a stream. The writer's version guard still
     * drops a row the page already covered, whatever the order the page and the live messages arrived in.
     */
    private int applyPage(ReplicaStream stream, List<Map<String, Object>> rows) {
        return tenantContext.callAs(RequestScope.platform(), () -> unitOfWork.transactional(() -> {
            int count = 0;
            for (Map<String, Object> row : rows) {
                if (writer.apply(stream.eventType(), row)) {
                    count++;
                }
            }
            return count;
        }));
    }
}
