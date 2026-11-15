package dev.ngb.mailer.boot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything mailer reads from configuration, in one record tree, so the wiring classes never touch a property source and
 * a missing value fails at startup rather than at the first send.
 *
 * <p>The delivery tuning copies the broker relay's field vocabulary — batch size, max attempts, jittered backoff — so the
 * two cannot drift conceptually, while {@code lease} and {@code sendDeadline} are this channel's own: they exist because
 * a send is a network call the ledger has to bound.
 */
@ConfigurationProperties(prefix = "mailer")
public record MailerSettings(
        Messaging messaging,
        Sender sender,
        Ses ses,
        Delivery delivery,
        Jobs jobs,
        Housekeeping housekeeping,
        Credentials credentials,
        Secrets secrets) {

    /**
     * @param exchange the command exchange this service consumes, named in contracts/commands
     * @param eventsExchange the exchange this service produces its delivery results to; it is a <em>producing</em>
     *     exchange, distinct from the command exchange it consumes
     * @param queue the durable command queue
     */
    public record Messaging(String exchange, String eventsExchange, String queue) {}

    /**
     * Which transport sends, and the identity used when a tenant has registered none.
     *
     * @param kind {@code smtp} locally, {@code ses} in a deployment
     */
    public record Sender(String kind, String region, String defaultFromAddress, String defaultFromName) {}

    /** @param endpointOverride blank in production; points the production adapter at an emulator or a wire stub */
    public record Ses(String endpointOverride) {}

    /**
     * @param lease how long a claim is held; it must exceed {@code sendDeadline} or a second worker claims a delivery
     *     whose first send is still running
     * @param sendDeadline connect plus read plus write timeout: the worst case one send may take
     */
    public record Delivery(
            boolean enabled,
            int batchSize,
            Duration pollDelay,
            int maxAttempts,
            Duration backoffBase,
            Duration backoffCap,
            double jitter,
            Duration lease,
            Duration sendDeadline) {}

    /** @param tenantParallelism at most this many tenants run at once, kept below the connection pool */
    public record Jobs(boolean enabled, int tenantParallelism) {}

    /** Retention for the body and the inbox; both are bounded deletes, not unbounded statements. */
    public record Housekeeping(
            boolean enabled, Duration bodyRetention, Duration inboxRetention, int batchSize, Duration interval) {}

    /**
     * @param staleAfter how old a verification must be before the probe repeats it
     * @param cacheSize how many assumed-role providers are kept
     */
    public record Credentials(boolean healthEnabled, Duration healthInterval, Duration staleAfter, int cacheSize) {}

    /** The master key for credentials at rest: base64 of exactly 32 bytes. */
    public record Secrets(String masterKey, String masterKeyId) {}
}
