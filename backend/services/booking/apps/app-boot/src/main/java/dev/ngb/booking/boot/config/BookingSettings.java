package dev.ngb.booking.boot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything booking reads from its own configuration tree, in one record, so the wiring classes never touch a
 * property source and a missing value fails at startup rather than at the first request.
 *
 * <p>The timings are the plan's defaults: a ten-minute hold, thirty minutes of total extension, a sixty-second grace
 * window, a ten-minute waitlist offer, a 120-day materialization horizon, a one-hour token cap, 120 requests per minute
 * per fingerprint and eight in-flight holds per target per instance.
 */
@ConfigurationProperties(prefix = "booking")
public record BookingSettings(
        Messaging messaging,
        Relay relay,
        Webhooks webhooks,
        Gate gate,
        Jobs jobs,
        Housekeeping housekeeping,
        Timings timings) {

    /** The Platform state-transfer events this service consumes on its own queue. */
    public record Messaging(String exchange, String queue) {}

    /**
     * The customer-token keys and the public edge's body limit.
     *
     * @param keys every key still accepted, keyed by id; a rotation adds one and moves {@code active-key-id}
     */
    public record Gate(String activeKeyId, java.util.Map<String, String> keys, int maxBodyBytes) {}

    /**
     * The in-process outbox relay: booking declares no exchange, so every due outbox row is handed to the webhook
     * listener inside the relay's own transaction instead of being published to a broker.
     */
    public record Relay(
            Duration pollDelay,
            int batchSize,
            int maxAttempts,
            Duration backoffBase,
            Duration backoffCap,
            double jitter,
            Duration claimDeadline) {}

    /**
     * The webhook sender: the signing key at rest, the destination policy, the retry schedule and the HTTP limits.
     *
     * @param allowInsecureTargets true only in the compose profile, where the stack's smoke forwarder listens on the
     *     container network; every other deployment requires a public https address
     */
    public record Webhooks(
            String masterKey,
            String masterKeyId,
            boolean allowInsecureTargets,
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
            Duration deadline) {}

    /** @param tenantParallelism at most this many tenants run at once, kept below the connection pool */
    public record Jobs(boolean enabled, int tenantParallelism) {}

    /** Bounded retention for the messaging tables, so the inbox and dead outbox rows cannot grow without bound. */
    public record Housekeeping(
            boolean enabled, Duration inboxRetention, Duration deadOutboxRetention, int batchSize, Duration interval) {}

    /**
     * The tenant-level timing and admission defaults. An offering or a tenant row may override one; these are what a
     * tenant that set nothing gets.
     */
    public record Timings(
            long holdTtlSeconds,
            long maxExtensionSeconds,
            long graceSeconds,
            long offerWindowSeconds,
            long horizonDays,
            long tokenTtlCapSeconds,
            int rateLimitPerMinute,
            int admissionCap,
            int waitlistCapPerTarget,
            int admitPerSecond,
            int admitWindowSeconds) {}
}
