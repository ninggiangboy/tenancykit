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
        Messaging messaging, Jobs jobs, Housekeeping housekeeping, Timings timings) {

    /** The Platform state-transfer events this service consumes on its own queue. */
    public record Messaging(String exchange, String queue) {}

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
