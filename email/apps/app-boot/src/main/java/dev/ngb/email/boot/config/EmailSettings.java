package dev.ngb.email.boot.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything email reads from its own configuration tree, in one record, so the wiring classes never touch a property
 * source and a missing value fails at startup rather than at the first event.
 *
 * <p>The relay tuning copies the broker relay's vocabulary — batch size, max attempts, jittered backoff, claim deadline
 * — so the two cannot drift conceptually.
 */
@ConfigurationProperties(prefix = "email")
public record EmailSettings(
        Messaging messaging,
        Mailer mailer,
        Commands commands,
        Relay relay,
        Jobs jobs,
        Defaults defaults,
        Campaign campaign,
        Housekeeping housekeeping,
        Tracking tracking) {

    /** The Platform state-transfer events this service consumes on its own queue. */
    public record Messaging(String exchange, String queue) {}

    /**
     * How often each campaign job runs.
     *
     * @param publisherInterval the publishing tick; the per-tick budget is the tenant's per-minute cap divided by the
     *     number of these ticks in a minute, so the two can never drift apart
     */
    public record Campaign(Duration publisherInterval, Duration schedulerInterval) {}

    /** Mailer's result event: the second stream this service consumes. */
    public record Mailer(String exchange, String queue) {}

    /** The one command this service produces: {@code email.send} into mailer's command exchange. */
    public record Commands(String exchange, String routingKey) {}

    /** @param claimDeadline how long a claimed outbox row stays leased before another worker may take it again */
    public record Relay(
            int batchSize,
            int maxAttempts,
            Duration backoffBase,
            Duration backoffCap,
            double jitter,
            Duration claimDeadline) {}

    /** @param tenantParallelism at most this many tenants run at once, kept below the connection pool */
    public record Jobs(boolean enabled, int tenantParallelism) {}

    /**
     * The values a tenant inherits when it has no {@code email_settings} row of its own.
     *
     * @param fromKey the configured sender key; mailer resolves the address, so a tenant cannot choose a domain
     * @param bodyRetentionDays how long a rendered body is kept before housekeeping purges it
     * @param campaignPublishPerMinute the default cap on campaign commands published per minute
     */
    public record Defaults(
            String fromKey,
            String fromName,
            String replyTo,
            int bodyRetentionDays,
            int campaignPublishPerMinute) {}

    /** Bounded retention for the messaging tables: without a purge the inbox and dead outbox rows grow without bound. */
    public record Housekeeping(
            boolean enabled,
            Duration inboxRetention,
            Duration deadOutboxRetention,
            Duration campaignRecipientRetention,
            int batchSize,
            Duration interval) {}

    /**
     * The HMAC keys tracking tokens are signed with.
     *
     * @param activeKeyId the key id new tokens carry; verification accepts any key still in {@code keys}, so a
     *     rotation keeps old mail's tokens working until the old key is removed
     * @param publicBaseUrl where a recipient reaches the public endpoints, such as {@code http://localhost/email}
     */
    public record Tracking(String activeKeyId, String publicBaseUrl, Map<String, String> keys) {}
}
