package dev.ngb.crm.boot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything crm reads from its own configuration tree, in one record, so the wiring classes never touch a property
 * source and a missing value fails at startup rather than at the first request or the first event.
 *
 * <p>Two groups live here. The deployment limits (batch caps, line caps, payload size, export page size) are the
 * plan's defaults, written once in {@code application.yml} and read by the use cases their phases add; they are
 * deployment settings and not per-Tenant settings, because they protect this service and the Tenant cannot raise
 * them. The runtime tuning (relay, jobs, housekeeping, webhooks) copies the broker relay's vocabulary — batch size,
 * max attempts, jittered backoff, lease — so the pieces cannot drift conceptually.
 */
@ConfigurationProperties(prefix = "crm")
public record CrmSettings(
        Messaging messaging,
        Relay relay,
        Jobs jobs,
        Housekeeping housekeeping,
        Batches batches,
        Fields fields,
        Tags tags,
        Consents consents,
        Orders orders,
        Activity activity,
        Exports exports,
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
            Duration claimDeadline) {

        public Relay {
            positive(batchSize, "crm.relay.batch-size");
            positive(maxAttempts, "crm.relay.max-attempts");
            if (jitter < 0 || jitter >= 1) {
                throw new IllegalArgumentException("crm.relay.jitter must be in [0, 1)");
            }
        }
    }

    /** @param tenantParallelism at most this many tenants run at once, kept below the connection pool */
    public record Jobs(boolean enabled, int tenantParallelism) {

        public Jobs {
            positive(tenantParallelism, "crm.jobs.tenant-parallelism");
        }
    }

    /**
     * Bounded retention for the tables a job owns: without a purge the inbox, the dead outbox and the finished
     * deliveries grow without bound.
     *
     * @param webhookDeliveryRetention how long a finished delivery and its attempt log are kept
     */
    public record Housekeeping(
            boolean enabled,
            Duration inboxRetention,
            Duration deadOutboxRetention,
            Duration webhookDeliveryRetention,
            int batchSize,
            Duration interval) {

        public Housekeeping {
            positive(batchSize, "crm.housekeeping.batch-size");
        }
    }

    /**
     * How many items one batch call may carry. A batch reports each item's outcome and never fails as a whole, so the
     * cap bounds one request's work rather than its correctness.
     *
     * @param maxCustomers the largest customer batch, 1,000 by default
     * @param maxActivities the largest activity batch, 1,000 by default
     * @param maxOrders the largest order batch, 100 by default
     * @param maxOrderLines the most lines all orders of one batch may carry together, 5,000 by default
     */
    public record Batches(int maxCustomers, int maxActivities, int maxOrders, int maxOrderLines) {

        public Batches {
            positive(maxCustomers, "crm.batches.max-customers");
            positive(maxActivities, "crm.batches.max-activities");
            positive(maxOrders, "crm.batches.max-orders");
            positive(maxOrderLines, "crm.batches.max-order-lines");
        }
    }

    /** @param maxLinesPerOrder the largest order one call may record, 500 lines by default */
    public record Orders(int maxLinesPerOrder) {

        public Orders {
            positive(maxLinesPerOrder, "crm.orders.max-lines-per-order");
        }
    }

    /**
     * The Tenant's own customer attributes, bounded so one Tenant's definitions and one customer's values cannot grow
     * without limit.
     *
     * @param maxActiveDefinitions how many definitions a Tenant may have that accept values, 50 by default
     * @param maxBytes the largest serialized field values of one customer, 16 KiB by default
     */
    public record Fields(int maxActiveDefinitions, int maxBytes) {

        public Fields {
            positive(maxActiveDefinitions, "crm.fields.max-active-definitions");
            positive(maxBytes, "crm.fields.max-bytes");
        }
    }

    /** @param maxPerCustomer how many tags one customer may carry, 50 by default */
    public record Tags(int maxPerCustomer) {

        public Tags {
            positive(maxPerCustomer, "crm.tags.max-per-customer");
        }
    }

    /** @param maxFutureSkew how far ahead of the service's own clock a consent's {@code occurredAt} may be */
    public record Consents(Duration maxFutureSkew) {

        public Consents {
            if (maxFutureSkew == null || maxFutureSkew.isNegative()) {
                throw new IllegalArgumentException("crm.consents.max-future-skew must not be negative");
            }
        }
    }

    /** @param maxPayloadBytes the largest activity payload, 8 KiB by default */
    public record Activity(int maxPayloadBytes) {

        public Activity {
            positive(maxPayloadBytes, "crm.activity.max-payload-bytes");
        }
    }

    /** @param pageSize how many rows one page of the streamed CSV export holds */
    public record Exports(int pageSize) {

        public Exports {
            positive(pageSize, "crm.exports.page-size");
        }
    }

    /**
     * The outbound webhook sender: what it may target, how it retries and how its HTTP pool is sized.
     *
     * @param masterKey the AES key the endpoint signing secrets are encrypted with; a deployment must supply one, so a
     *     forgotten key fails startup instead of storing a secret in the clear
     * @param allowNonPublicTargets whether http and loopback/private destinations are accepted. False by default and
     *     true only in the compose profile, where the smoke forwarder of phase 16 receives a delivery on a container
     *     network; everywhere else a Tenant-chosen endpoint must be a public https address
     */
    public record Webhooks(
            String masterKey,
            String masterKeyId,
            boolean allowNonPublicTargets,
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
            Duration deadline) {

        public Webhooks {
            positive(batchSize, "crm.webhooks.batch-size");
            positive(maxAttempts, "crm.webhooks.max-attempts");
        }
    }

    private static void positive(int value, String property) {
        if (value < 1) {
            throw new IllegalArgumentException(property + " must be at least 1");
        }
    }
}
