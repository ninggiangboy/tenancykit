package dev.ngb.workflow.boot.config;

import java.time.Duration;
import java.util.Map;
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
        @DefaultValue Secrets secrets,
        @DefaultValue Connections connections,
        @DefaultValue Webhooks webhooks,
        @DefaultValue Relay relay,
        @DefaultValue Quota quota) {

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
            @DefaultValue("P30D") Duration webhookDeliveryRetention,
            @DefaultValue("P90D") Duration instanceRetention,
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
    public record Secrets(
            String masterKey, @DefaultValue("v1") String keyId, @DefaultValue Map<String, String> previousKeys) {}

    /**
     * @param allowNonPublicTargets development only: a compose endpoint may resolve to a container address
     * @param batchSize how many deliveries one round claims per tenant
     * @param maxAttempts deliveries before a row is dead
     * @param lease how long a claimed delivery stays owned before another worker may take it
     */
    public record Webhooks(
            @DefaultValue("false") boolean allowNonPublicTargets,
            @DefaultValue("25") int batchSize,
            @DefaultValue("10") int maxAttempts,
            @DefaultValue("PT30S") Duration backoffBase,
            @DefaultValue("PT6H") Duration backoffCap,
            @DefaultValue("PT2M") Duration lease,
            @DefaultValue("100") int maxConnections,
            @DefaultValue("25") int maxConnectionsPerRoute,
            @DefaultValue("PT2S") Duration connectionRequestTimeout,
            @DefaultValue("PT3S") Duration connectTimeout,
            @DefaultValue("PT10S") Duration readTimeout,
            @DefaultValue("PT15S") Duration deadline) {}

    /**
     * @param concurrentInstances how many instances one Tenant may have RUNNING, WAITING or COMPENSATING
     * @param activeDefinitions how many definitions one Tenant may hold that are not retired
     */
    public record Quota(
            @DefaultValue("1000") int concurrentInstances,
            @DefaultValue("200") int activeDefinitions) {}

    /**
     * @param batchSize how many outbox rows one relay round handles per tenant
     * @param maxAttempts relay attempts before a row is dead
     */
    public record Relay(
            @DefaultValue("50") int batchSize,
            @DefaultValue("10") int maxAttempts,
            @DefaultValue("PT10S") Duration backoffBase,
            @DefaultValue("PT1H") Duration backoffCap,
            @DefaultValue("0.2") double jitter,
            @DefaultValue("PT30S") Duration claimDeadline) {}

    /**
     * @param tokenUrl the Platform token endpoint the engine mints audience tokens from
     * @param allowNonPublicTargets development only: the compose network is private, so app addresses resolve there
     * @param connectTimeout how long a connect may take
     * @param readTimeout how long a response may take
     * @param apps the base address of each app this deployment may call, by the name a node uses
     */
    public record Connections(
            @DefaultValue("http://localhost:8080/oauth/token") String tokenUrl,
            @DefaultValue("false") boolean allowNonPublicTargets,
            @DefaultValue("PT5S") Duration connectTimeout,
            @DefaultValue("PT30S") Duration readTimeout,
            @DefaultValue Map<String, String> apps) {}
}
