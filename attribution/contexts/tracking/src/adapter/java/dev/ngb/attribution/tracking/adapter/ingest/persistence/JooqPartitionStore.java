package dev.ngb.attribution.tracking.adapter.ingest.persistence;

import dev.ngb.attribution.tracking.application.ingest.port.PartitionStore;
import java.time.LocalDate;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * The partition maintenance, driven through the owner's SECURITY DEFINER functions.
 *
 * <p>The app role cannot create a table, so it calls {@code attribution_ensure_partitions} and {@code
 * attribution_drop_partitions} instead; both are owned by the migration owner and check the table name against an allow
 * list, so the app can manage only the tables that were written for it.
 */
public class JooqPartitionStore implements PartitionStore {

    private final DSLContext dsl;

    public JooqPartitionStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public int ensure(String tableName, LocalDate from, int days) {
        Integer created = dsl.select(DSL.function(
                        "attribution_ensure_partitions",
                        Integer.class,
                        DSL.val(tableName),
                        DSL.val(from),
                        DSL.val(days)))
                .fetchOne(0, Integer.class);
        return created == null ? 0 : created;
    }

    @Override
    public int drop(String tableName, LocalDate before) {
        Integer dropped = dsl.select(DSL.function(
                        "attribution_drop_partitions",
                        Integer.class,
                        DSL.val(tableName),
                        DSL.val(before)))
                .fetchOne(0, Integer.class);
        return dropped == null ? 0 : dropped;
    }

    @Override
    public long defaultPartitionRows(String tableName) {
        // The default partition is not a generated table (the code generator excludes partitions), so it is addressed by
        // name. A row here means a partition was missing at insert time and is an alert.
        return dsl.fetchCount(DSL.table(DSL.name(tableName + "_default")));
    }
}
