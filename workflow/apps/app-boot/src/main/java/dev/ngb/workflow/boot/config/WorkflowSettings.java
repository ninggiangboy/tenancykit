package dev.ngb.workflow.boot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The worker's own knobs: how many tenants a job may touch at once, how much housekeeping may delete per round, and how
 * the runtime claims and paces tokens.
 *
 * @param jobs the scheduled-work settings
 * @param housekeeping the retention settings
 * @param runtime the execution settings
 */
@ConfigurationProperties(prefix = "workflow")
public record WorkflowSettings(
        @DefaultValue Jobs jobs,
        @DefaultValue Housekeeping housekeeping,
        @DefaultValue Runtime runtime,
        @DefaultValue Schedules schedules,
        @DefaultValue Secrets secrets) {

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

    /**
     * @param lease how long a claimed token is owned before the sweeper may take it back
     * @param batch how many steps one sweep runs per tenant before it moves on
     * @param sweepInterval how often the runnable sweep looks for due tokens
     */
    public record Runtime(
            @DefaultValue("PT1M") Duration lease,
            @DefaultValue("50") int batch,
            @DefaultValue("PT2S") Duration sweepInterval) {}

    /**
     * @param enabled whether cron schedules fire at all
     * @param interval how often the worker looks for due schedules
     * @param batch how many due schedules one sweep fires per tenant
     */
    public record Schedules(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT30S") Duration interval,
            @DefaultValue("20") int batch) {}

    /**
     * @param masterKey a base64 32-byte AES-256 key; a deployment sets it in the environment and the app refuses to
     *     start without one
     * @param keyId the id stored with every envelope this deployment writes
     */
    public record Secrets(String masterKey, @DefaultValue("v1") String keyId) {}
}
