package dev.ngb.notification.boot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything notification reads from its own configuration tree, in one record, so the wiring classes never touch a
 * property source and a missing value fails at startup rather than at the first event.
 *
 * <p>The email tuning copies the broker relay's vocabulary — batch size, max attempts, jittered backoff, claim
 * deadline — so the two cannot drift conceptually.
 */
@ConfigurationProperties(prefix = "notification")
public record NotificationSettings(
        Messaging messaging,
        Commands commands,
        Jobs jobs,
        Email email,
        Housekeeping housekeeping,
        Templates templates) {

    /** The Platform events this service consumes. Deliberately a different key from {@code commands}. */
    public record Messaging(String exchange, String queue) {}

    /** The command this service produces: {@code email.send} to mailer's exchange. */
    public record Commands(String exchange, String routingKey) {}

    /** @param tenantParallelism at most this many tenants run at once, kept below the connection pool */
    public record Jobs(boolean enabled, int tenantParallelism) {}

    /**
     * @param enabled false stops publishing without losing queued work: outbox rows stay {@code PENDING}
     * @param fromKey the configured sender key; mailer resolves the address, so a tenant cannot choose a domain
     */
    public record Email(
            boolean enabled,
            String fromKey,
            Duration pollDelay,
            int batchSize,
            int maxAttempts,
            Duration backoffBase,
            Duration backoffCap,
            double jitter,
            Duration claimDeadline) {}

    /** Bounded retention per table: without a purge, in-app bodies and the ledger grow forever. */
    public record Housekeeping(
            boolean enabled,
            Duration notificationRetention,
            Duration dispatchRetention,
            Duration inboxRetention,
            Duration deadOutboxRetention,
            int batchSize,
            Duration interval) {}

    /** @param maxPartBytes the largest template part the renderer accepts, before refusing rather than truncating */
    public record Templates(int maxPartBytes) {}
}
