package dev.ngb.workflow.boot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The worker's own knobs: how many tenants a job may touch at once, and how much housekeeping may delete per round.
 *
 * @param jobs the scheduled-work settings
 * @param housekeeping the retention settings
 */
@ConfigurationProperties(prefix = "workflow")
public record WorkflowSettings(Jobs jobs, Housekeeping housekeeping) {

    /**
     * @param enabled whether the worker's scheduled jobs run at all
     * @param tenantParallelism at most this many tenants run at once across every job
     */
    public record Jobs(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("4") int tenantParallelism) {}

    /**
     * @param enabled whether old inbox and dead outbox rows are purged
     * @param inboxRetention how long a consumed event is remembered for deduplication
     * @param deadOutboxRetention how long a dead outbox row is kept as evidence
     * @param batchSize how many rows one round deletes at most
     * @param interval how often the job runs
     */
    public record Housekeeping(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("P30D") Duration inboxRetention,
            @DefaultValue("P7D") Duration deadOutboxRetention,
            @DefaultValue("500") int batchSize,
            @DefaultValue("PT1H") Duration interval) {}
}
