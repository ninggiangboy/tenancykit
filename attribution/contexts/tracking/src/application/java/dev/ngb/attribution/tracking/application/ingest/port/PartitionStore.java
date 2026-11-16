package dev.ngb.attribution.tracking.application.ingest.port;

import java.time.LocalDate;

/**
 * The partition and retention maintenance the app role drives through the owner's SECURITY DEFINER functions.
 *
 * <p>The app role owns no table and cannot create one, so this port is the only way it manages a partition: each call
 * goes to a function owned by the migration owner, whose search path is fixed and whose table name is checked against
 * an allow list.
 */
public interface PartitionStore {

    /** The tables the partition maintenance manages. */
    String CLICK_EVENTS = "click_events";

    /**
     * Create every missing day partition in {@code [from, from + days)}.
     *
     * @return how many partitions were created
     */
    int ensure(String tableName, LocalDate from, int days);

    /**
     * Drop every partition whose whole range ends before {@code before}.
     *
     * @return how many partitions were dropped
     */
    int drop(String tableName, LocalDate before);

    /** How many rows the default partition holds, which is an alert when greater than zero. */
    long defaultPartitionRows(String tableName);
}
