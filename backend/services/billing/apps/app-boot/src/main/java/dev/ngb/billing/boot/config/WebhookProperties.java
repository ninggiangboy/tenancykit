package dev.ngb.billing.boot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The outbound webhook sender: what it may target, how it retries and how its HTTP pool is sized.
 *
 * @param allowInsecureTargets whether http and loopback/private destinations are accepted (development only)
 * @param batchSize how many due deliveries one round claims
 * @param maxAttempts how many times a delivery is attempted before it is dead
 * @param backoffBase the first retry delay; later delays double from it
 * @param backoffCap the longest retry delay
 * @param lease how long a claimed delivery stays invisible to other workers
 * @param maxConnections the whole HTTP connection pool
 * @param maxConnectionsPerRoute the per-destination connection pool
 * @param connectionRequestTimeout how long a thread waits for a pooled connection
 * @param connectTimeout how long the TCP connect may take
 * @param readTimeout how long the receiver may take to answer
 * @param deadline the whole attempt's wall-clock budget
 */
@ConfigurationProperties(prefix = "billing.webhooks")
public record WebhookProperties(
        @DefaultValue("false") boolean allowInsecureTargets,
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
