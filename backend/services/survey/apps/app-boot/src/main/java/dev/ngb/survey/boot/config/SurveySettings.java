package dev.ngb.survey.boot.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything survey reads from its own configuration tree, in one record, so the wiring classes never touch a property
 * source and a missing value fails at startup rather than at the first request.
 *
 * <p>The relay tuning copies the broker relay's vocabulary — batch size, max attempts, jittered backoff, claim deadline
 * — so the two cannot drift conceptually.
 */
@ConfigurationProperties(prefix = "survey")
public record SurveySettings(
        Messaging messaging,
        Relay relay,
        Events events,
        Jobs jobs,
        Housekeeping housekeeping,
        Links links,
        Geoip geoip,
        Uploads uploads,
        PublicApi publicApi,
        Webhooks webhooks) {

    /** The Platform state-transfer events this service consumes on its own queue. */
    public record Messaging(String exchange, String queue) {}

    /** @param claimDeadline how long a claimed outbox row stays leased before another worker may take it again */
    public record Relay(
            int batchSize,
            int maxAttempts,
            Duration backoffBase,
            Duration backoffCap,
            double jitter,
            Duration claimDeadline) {}

    /** The one exchange this app produces: its own three facts, which no app consumes yet. */
    public record Events(String exchange) {}

    /** @param tenantParallelism at most this many tenants run at once, kept below the connection pool */
    public record Jobs(boolean enabled, int tenantParallelism) {}

    /**
     * Bounded retention for the messaging tables: without a purge the inbox and dead outbox rows grow without bound.
     *
     * @param abandonedVisitAfter how long an unfinished visit may stay idle before the sweep abandons it
     */
    public record Housekeeping(
            boolean enabled,
            Duration inboxRetention,
            Duration deadOutboxRetention,
            Duration orphanedUploadRetention,
            Duration abandonedVisitAfter,
            int batchSize,
            Duration interval) {}

    /**
     * The HMAC keys a link token is signed with.
     *
     * @param activeKeyId the key id new tokens carry; verification accepts any key still in {@code keys}, so a
     *     rotation keeps a link already handed out working until the old key is removed
     * @param publicBaseUrl where a respondent reaches the public endpoints, such as {@code http://localhost/survey}
     */
    public record Links(String activeKeyId, String publicBaseUrl, Map<String, String> keys) {}

    /**
     * The GeoIP database.
     *
     * @param path the DB-IP Lite {@code .mmdb} file; absent is allowed and leaves every country null
     */
    public record Geoip(String path) {}

    /**
     * The S3 settings an upload and an export use.
     *
     * @param endpoint the server's own S3 endpoint
     * @param publicEndpoint the host a respondent's browser reaches, used only to sign a presigned URL
     */
    public record Uploads(
            String endpoint, String publicEndpoint, String bucket, String region, String accessKey, String secretKey) {}

    /**
     * The public surface's limits. Both are per instance and in memory, so with N instances the effective limit is N
     * times the setting.
     *
     * @param ratePerMinute one caller's requests per minute, keyed by an HMAC of the client IP
     * @param maxBodyBytes the largest request body any public endpoint accepts
     */
    public record PublicApi(int ratePerMinute, int maxBodyBytes) {}

    /**
     * The outbound webhook sender: what it may target, how it retries and how its HTTP pool is sized.
     *
     * @param masterKey the AES key the endpoint secrets are encrypted with; a deployment must supply one
     * @param allowInsecureTargets whether http and loopback/private destinations are accepted (development only)
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
}
