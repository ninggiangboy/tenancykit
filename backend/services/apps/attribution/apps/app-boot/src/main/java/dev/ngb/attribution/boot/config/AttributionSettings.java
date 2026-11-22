package dev.ngb.attribution.boot.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything attribution reads from its own configuration tree, in one record, so the wiring classes never touch a
 * property source and a missing value fails at startup rather than at the first event.
 *
 * <p>The relay tuning copies the broker relay's vocabulary — batch size, max attempts, jittered backoff, claim
 * deadline — so the two cannot drift conceptually.
 */
@ConfigurationProperties(prefix = "attribution")
public record AttributionSettings(
        Messaging messaging,
        Ingest ingest,
        Relay relay,
        Jobs jobs,
        Defaults defaults,
        Housekeeping housekeeping,
        Keys keys,
        Redirect redirect) {

    /** The Platform state-transfer events this service consumes on its own queue. */
    public record Messaging(String exchange, String queue) {}

    /**
     * The app's own ingest exchange. The redirect offers a click to a bounded buffer and a publisher sends batches
     * here; the worker consumes them. This is not the outbox: an ingest batch is not a domain event.
     */
    public record Ingest(String exchange, String queue) {}

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
     * The values a tenant inherits when it has no {@code attribution_settings} row of its own.
     *
     * @param clickParam the destination query parameter that carries the signed click id
     * @param defaultWindowDays the attribution window a campaign inherits
     * @param rawRetentionDays how long a raw event partition is kept; a deployment setting, not a tenant one, because
     *     partitions are shared across tenants
     */
    public record Defaults(String clickParam, int defaultWindowDays, int rawRetentionDays) {}

    /** Bounded retention for the messaging tables: without a purge the inbox and dead outbox rows grow without bound. */
    public record Housekeeping(boolean enabled, Duration inboxRetention, Duration deadOutboxRetention, int batchSize, Duration interval) {}

    /**
     * The redirect path's tuning. Every number here is a latency or a memory bound on the one endpoint a visitor waits
     * for, so the defaults are deliberately small.
     *
     * @param cacheTtl how long a resolved link (or a negative entry) lives in memory before the next read reloads it;
     *     a paused or archived link keeps redirecting for at most this long on an instance that has not reloaded it
     * @param cacheMaxSize the most links one instance keeps in memory, so a hostile slug cannot grow the cache
     * @param bufferSize how many click events the in-memory buffer holds before the oldest is dropped
     * @param flushBatchSize how many events one publish batch carries
     * @param flushInterval how long the publisher waits for a full batch before sending what it has
     * @param visitorPerMinute the public per-visitor-hash rate, per instance
     * @param linkPerMinute the public per-link rate, per instance
     * @param unlockAttempts how many wrong passwords one link and visitor hash may try per window before refusal
     * @param unlockWindow the window the unlock attempt counter resets after
     */
    public record Redirect(
            @DefaultValue("PT30S") Duration cacheTtl,
            @DefaultValue("10000") int cacheMaxSize,
            @DefaultValue("10000") int bufferSize,
            @DefaultValue("500") int flushBatchSize,
            @DefaultValue("PT0.2S") Duration flushInterval,
            @DefaultValue("600") int visitorPerMinute,
            @DefaultValue("6000") int linkPerMinute,
            @DefaultValue("5") int unlockAttempts,
            @DefaultValue("PT10M") Duration unlockWindow) {}

    /**
     * The HMAC keys and paths the app signs and reads with. A blank click key or visitor hash key fails startup, the
     * same way mailroom's tracking configuration refuses to start without one.
     *
     * @param activeKeyId the click-token key id new tokens carry; verification accepts every key still in
     *     {@code clickKeys}, so a rotation keeps links already in the wild working until the old key is removed
     * @param publicBaseUrl where a visitor reaches the public endpoints, such as {@code http://localhost}
     * @param visitorHashKey the master key the daily visitor hash is derived from
     * @param geoipPath the DB-IP Lite country database; a missing file yields an empty country rather than failing
     */
    public record Keys(
            String activeKeyId,
            String publicBaseUrl,
            String visitorHashKey,
            String geoipPath,
            Map<String, String> clickKeys) {}
}
