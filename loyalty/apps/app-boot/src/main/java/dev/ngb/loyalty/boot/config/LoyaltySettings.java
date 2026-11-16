package dev.ngb.loyalty.boot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything loyalty reads from its own configuration tree, in one record, so the wiring classes never touch a property
 * source and a missing value fails at startup rather than at the first event.
 *
 * <p>These are deployment settings, not per-tenant ones: a Tenant's program (currency, expiry rules, tiers, promotions)
 * is business data and arrives in a later phase. Nothing here exports anything, because loyalty publishes no AMQP event;
 * its facts leave only as signed webhooks.
 */
@ConfigurationProperties(prefix = "loyalty")
public record LoyaltySettings(
        Messaging messaging,
        Relay relay,
        Webhooks webhooks,
        Jobs jobs,
        Expiry expiry,
        TierEvaluation tierEvaluation,
        Orders orders,
        Housekeeping housekeeping) {

    /** The Platform state-transfer events this service consumes on its own queue. It declares nothing else. */
    public record Messaging(String exchange, String queue) {}

    /**
     * The in-process outbox relay: how many due rows one poll delivers and how often it fails over before a row is
     * dead.
     *
     * @param pollDelay the gap between two relay ticks
     * @param backoffBase the first retry delay, doubled per attempt up to {@code backoffCap}
     * @param jitter the +/- spread applied to a retry delay, in {@code [0, 1)}
     */
    public record Relay(
            Duration pollDelay,
            int batchSize,
            int maxAttempts,
            Duration backoffBase,
            Duration backoffCap,
            double jitter) {}

    /**
     * Endpoints and signed deliveries.
     *
     * @param allowNonPublicTargets {@code false} everywhere except the compose profile: a loopback or private
     *     destination is a development receiver, never a Tenant's endpoint
     * @param lease how long a claimed delivery belongs to one worker before another may take it
     * @param retention how long a finished delivery and its attempt log are kept before housekeeping purges them
     * @param masterKey the base64 of 32 random bytes that wraps each endpoint's signing secret; a blank value fails
     *     startup rather than storing secrets a deployment cannot read back
     */
    public record Webhooks(
            boolean allowNonPublicTargets,
            Duration pollDelay,
            int batchSize,
            int maxAttempts,
            Duration backoffBase,
            Duration backoffCap,
            Duration lease,
            int maxConnections,
            int maxConnectionsPerRoute,
            Duration connectionRequestTimeout,
            Duration connectTimeout,
            Duration readTimeout,
            Duration deadline,
            String masterKey,
            String masterKeyId,
            Duration retention) {}

    /** @param tenantParallelism at most this many tenants run at once, kept below the connection pool */
    public record Jobs(boolean enabled, int tenantParallelism) {}

    /**
     * The expiry run: one lot at a time, member lock first, in batches that never hold the pool.
     *
     * @param interval the gap between two expiry runs
     */
    public record Expiry(int batchSize, Duration interval) {}

    /**
     * The daily tier evaluation: a member falls a tier only here, never inside an earn.
     *
     * @param interval the gap between two evaluation runs
     */
    public record TierEvaluation(int batchSize, Duration interval) {}

    /**
     * @param maxLinesPerOrder how many lines one order may carry; the same default the sibling app uses, so a Tenant
     *     sending one order to both gets the same answer from each
     */
    public record Orders(int maxLinesPerOrder) {}

    /** Bounded retention for the messaging tables: without a purge the inbox and dead outbox rows grow without bound. */
    public record Housekeeping(
            boolean enabled,
            Duration inboxRetention,
            Duration deadOutboxRetention,
            int batchSize,
            Duration interval) {}
}
