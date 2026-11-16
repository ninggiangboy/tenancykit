package dev.ngb.attribution.boot.jobs;

import dev.ngb.attribution.tracking.application.ingest.port.PartitionStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import dev.ngb.core.runtime.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the click table's day partitions created ahead of time, so an insert at midnight never finds no partition.
 *
 * <p>The app role cannot create a table, so each call goes through the owner's SECURITY DEFINER function. Seven days of
 * partitions exist at all times; a failure leaves the previous sweep's partitions in place and is logged, because the
 * default partition still catches a stray row. The row count of the default partition is exposed as a gauge: any row
 * there means a partition was missing at insert time and is an alert.
 *
 * <p>It runs only in the worker runtime, because a co-located api runtime scheduling it would double the DDL and fight
 * over the same lock.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "attribution.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class PartitionJob {

    private static final Logger LOG = LoggerFactory.getLogger(PartitionJob.class);

    private final PartitionStore partitions;
    private final int daysAhead;
    private final Clock clock;
    private final AtomicLong defaultPartitionRows = new AtomicLong();

    PartitionJob(
            PartitionStore partitions,
            @Value("${attribution.partitions.days-ahead:8}") int daysAhead,
            Clock clock,
            MeterRegistry meters) {
        this.partitions = partitions;
        this.daysAhead = Math.max(1, daysAhead);
        this.clock = clock;
        Gauge.builder("attribution.ingest.default_partition_rows", defaultPartitionRows, AtomicLong::get)
                .description("Rows in the default partition; greater than zero means a day partition was missing")
                .register(meters);
    }

    /** Every hour, and once shortly after startup, so a fresh deployment has a week of partitions immediately. */
    @Scheduled(fixedDelayString = "${attribution.partitions.interval:PT1H}", initialDelayString = "PT10S")
    public void run() {
        LocalDate today = clock.now().atOffset(ZoneOffset.UTC).toLocalDate();
        runFor(today);
    }

    /** The sweep for one day, exposed so a test drives it without waiting for a schedule. */
    public int runFor(LocalDate today) {
        try {
            int created = partitions.ensure(PartitionStore.CLICK_EVENTS, today, daysAhead);
            if (created > 0) {
                LOG.info("created {} click partition(s) covering {} days from {}", created, daysAhead, today);
            }
            defaultPartitionRows.set(partitions.defaultPartitionRows(PartitionStore.CLICK_EVENTS));
            return created;
        } catch (RuntimeException failure) {
            // The previous sweep's partitions are still there and the default partition catches a stray row; a failed
            // sweep is loud but never fatal to the consumer.
            LOG.error("could not ensure click partitions from {}", today, failure);
            return 0;
        }
    }
}
