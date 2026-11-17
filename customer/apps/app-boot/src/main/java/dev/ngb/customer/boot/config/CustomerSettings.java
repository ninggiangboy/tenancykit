package dev.ngb.customer.boot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything customer reads from its own configuration tree, in one record, so the wiring classes never touch a property
 * source and a missing value fails at startup rather than at the first request or the first event.
 *
 * <p>Two groups live here. The deployment limits (batch caps, line caps, payload size, export page size) are the
 * plan's defaults, written once in {@code application.yml} and read by the use cases their phases add; they are
 * deployment settings and not per-Tenant settings, because they protect this service and the Tenant cannot raise
 * them. The runtime tuning (relay, jobs, housekeeping, webhooks) copies the broker relay's vocabulary — batch size,
 * max attempts, jittered backoff, lease — so the pieces cannot drift conceptually.
 */
@ConfigurationProperties(prefix = "customer")
public record CustomerSettings(
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
            positive(batchSize, "customer.relay.batch-size");
            positive(maxAttempts, "customer.relay.max-attempts");
            if (jitter < 0 || jitter >= 1) {
                throw new IllegalArgumentException("customer.relay.jitter must be in [0, 1)");
            }
        }
    }

    /** @param tenantParallelism at most this many tenants run at once, kept below the connection pool */
    public record Jobs(boolean enabled, int tenantParallelism) {

        public Jobs {
            positive(tenantParallelism, "customer.jobs.tenant-parallelism");
        }
    }

    /**
     * Bounded retention for the tables a job owns: without a purge the inbox, the outbox and the finished deliveries
     * grow without bound, and every one of them can hold a person's data in a payload, a body or an event id.
     *
     * @param inboxRetention how long a consumed event id is kept for deduplication
     * @param relayedOutboxRetention how long an outbox row that reached its consumers keeps its payload, 7 days by
     *     default: the delivery already carries the body, so the row is only evidence after the relay
     * @param deadOutboxRetention how long a row the relay gave up on is kept for inspection
     * @param webhookDeliveryRetention how long a finished delivery and its attempt log are kept
     */
    public record Housekeeping(
            boolean enabled,
            Duration inboxRetention,
            Duration relayedOutboxRetention,
            Duration deadOutboxRetention,
            Duration webhookDeliveryRetention,
            int batchSize,
            Duration interval) {

        public Housekeeping {
            positive(batchSize, "customer.housekeeping.batch-size");
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
            positive(maxCustomers, "customer.batches.max-customers");
            positive(maxActivities, "customer.batches.max-activities");
            positive(maxOrders, "customer.batches.max-orders");
            positive(maxOrderLines, "customer.batches.max-order-lines");
        }
    }

    /**
     * How large one order may be, and how far ahead of the service's clock it may be dated.
     *
     * @param maxLinesPerOrder the largest order one call may record, 500 lines by default. It is a setting rather than
     *     a constant because it also bounds the size of one outbox row and one webhook body, so an installation with a
     *     slow endpoint can lower it
     * @param maxFutureSkew how far ahead of the service's clock an order or a return may be dated; there is no lower
     *     bound, because a Tenant that imports historic orders sends dates no window can accept
     */
    public record Orders(int maxLinesPerOrder, Duration maxFutureSkew) {

        public Orders {
            positive(maxLinesPerOrder, "customer.orders.max-lines-per-order");
            if (maxFutureSkew == null || maxFutureSkew.isNegative()) {
                throw new IllegalArgumentException("customer.orders.max-future-skew must not be negative");
            }
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
            positive(maxActiveDefinitions, "customer.fields.max-active-definitions");
            positive(maxBytes, "customer.fields.max-bytes");
        }
    }

    /** @param maxPerCustomer how many tags one customer may carry, 50 by default */
    public record Tags(int maxPerCustomer) {

        public Tags {
            positive(maxPerCustomer, "customer.tags.max-per-customer");
        }
    }

    /** @param maxFutureSkew how far ahead of the service's own clock a consent's {@code occurredAt} may be */
    public record Consents(Duration maxFutureSkew) {

        public Consents {
            if (maxFutureSkew == null || maxFutureSkew.isNegative()) {
                throw new IllegalArgumentException("customer.consents.max-future-skew must not be negative");
            }
        }
    }

    /** @param maxPayloadBytes the largest activity payload, 8 KiB by default */
    public record Activity(int maxPayloadBytes) {

        public Activity {
            positive(maxPayloadBytes, "customer.activity.max-payload-bytes");
        }
    }

    /**
     * How one streamed CSV export is read and how long its claim lasts.
     *
     * @param pageSize how many rows one round trip of the server-side cursor reads
     * @param lease how long one tenant's export claim is held before another request may take it over, so a process
     *     that died mid-export does not lock its tenant out for ever
     */
    public record Exports(int pageSize, Duration lease) {

        public Exports {
            positive(pageSize, "customer.exports.page-size");
            if (lease == null || lease.isNegative() || lease.isZero()) {
                throw new IllegalArgumentException("customer.exports.lease must be positive");
            }
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
            positive(batchSize, "customer.webhooks.batch-size");
            positive(maxAttempts, "customer.webhooks.max-attempts");
        }
    }

    private static void positive(int value, String property) {
        if (value < 1) {
            throw new IllegalArgumentException(property + " must be at least 1");
        }
    }
}
