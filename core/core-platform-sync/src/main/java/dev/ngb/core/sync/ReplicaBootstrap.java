package dev.ngb.core.sync;

import dev.ngb.core.replica.ReplicaWriter;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
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
 */
public class ReplicaBootstrap implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ReplicaBootstrap.class);

    /** Starts the listener container; the boot layer points it at the platform-events queue. */
    public interface ListenerContainer {

        void start();
    }

    private final ReplicaProperties properties;
    private final ReplicaWriter writer;
    private final SnapshotClient snapshots;
    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final ListenerContainer listener;

    public ReplicaBootstrap(
            ReplicaProperties properties,
            ReplicaWriter writer,
            SnapshotClient snapshots,
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            ListenerContainer listener) {
        this.properties = properties;
        this.writer = writer;
        this.snapshots = snapshots;
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.listener = listener;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.replica().rebuildOnStart() && needsRebuild()) {
            rebuild();
        }
        listener.start();
    }

    /** Rebuilds when the replica holds no tenant other than the platform pseudo-tenant. */
    public boolean needsRebuild() {
        return tenantContext.callAs(RequestScope.platform(), () -> unitOfWork.transactional(writer::tenantsMissing));
    }

    /** Pages every stream and upserts each row. Platform's own scope is bound, because the replica is global data. */
    public int rebuild() {
        int applied = 0;
        for (String stream : ReplicaWriter.streams()) {
            List<Map<String, Object>> rows = snapshots.fetchAll(stream, properties.replica().pageLimit());
            applied += tenantContext.callAs(RequestScope.platform(), () -> unitOfWork.transactional(() -> {
                int count = 0;
                for (Map<String, Object> row : rows) {
                    if (writer.apply(ReplicaWriter.eventTypeOf(stream), row)) {
                        count++;
                    }
                }
                return count;
            }));
            LOG.info("replica stream {} rebuilt: {} rows", stream, rows.size());
        }
        LOG.info("replica rebuild applied {} rows", applied);
        return applied;
    }
}
