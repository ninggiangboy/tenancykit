package dev.ngb.core.replica;

import dev.ngb.core.event.SuiteEventTypes;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The one list of replicated Platform streams. Every replica name, event type, table, column set and conflict key
 * lives here, so adding a stream is one constant here plus the migration that creates its table; the writer, the
 * inbound handler, the snapshot client's paging order, the queue bindings and the schema assertion all read it.
 *
 * <p>The order of the constants is the order a rebuild pages them in, so a row's foreign key already exists when the
 * stream that references it is applied. The event type is the wire name a live message carries; the stream name is the
 * path segment the snapshot API answers to; the table is the physical name the upsert writes.
 */
public enum ReplicaStream {

    /** One row per tenant. */
    TENANTS(
            "tenants",
            SuiteEventTypes.TENANT_CHANGED,
            "tenants",
            List.of("id", "code", "name", "status", "created_at", "version"),
            List.of("id")),

    /** One row per tenant domain, including the platform host, whose {@code tenant_id} is null. */
    TENANT_DOMAINS(
            "tenant-domains",
            SuiteEventTypes.TENANT_DOMAIN_CHANGED,
            "tenant_domains",
            List.of("id", "tenant_id", "hostname", "kind", "created_at", "version"),
            List.of("id")),

    /** One row per Tenant System. */
    TENANT_SYSTEMS(
            "tenant-systems",
            SuiteEventTypes.TENANT_SYSTEM_CHANGED,
            "tenant_systems",
            List.of("id", "tenant_id", "name", "status", "created_at", "version"),
            List.of("id")),

    /** One row per user: the identity and the permission codes this app enforces for it. */
    USER_ACCESS(
            "user-access",
            SuiteEventTypes.USER_ACCESS_CHANGED,
            "user_grants",
            List.of("user_id", "tenant_id", "scope", "keycloak_subject", "email", "display_name", "status", "permissions",
                    "version"),
            List.of("user_id")),

    /** One row per (tenant, app): whether the tenant may use the app at all. */
    ENTITLEMENTS(
            "entitlements",
            SuiteEventTypes.ENTITLEMENT_CHANGED,
            "app_entitlements",
            List.of("tenant_id", "app_code", "enabled", "version"),
            List.of("tenant_id", "app_code"));

    private static final Map<String, ReplicaStream> BY_STREAM_NAME =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(ReplicaStream::streamName, Function.identity()));

    private static final Map<String, ReplicaStream> BY_EVENT_TYPE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(ReplicaStream::eventType, Function.identity()));

    private final String streamName;
    private final String eventType;
    private final String table;
    private final List<String> columns;
    private final List<String> conflictKey;

    ReplicaStream(String streamName, String eventType, String table, List<String> columns, List<String> conflictKey) {
        this.streamName = streamName;
        this.eventType = eventType;
        this.table = table;
        this.columns = List.copyOf(columns);
        this.conflictKey = List.copyOf(conflictKey);
    }

    /** The snapshot API's name for this stream, such as {@code tenant-domains}. */
    public String streamName() {
        return streamName;
    }

    /** The {@code *.changed} type a live message carries for this stream. */
    public String eventType() {
        return eventType;
    }

    /** The physical table the stream's upsert writes. */
    public String table() {
        return table;
    }

    /** Every column the upsert of this stream sets. */
    public List<String> columns() {
        return columns;
    }

    /** The key the upsert's conflict target needs, which the table's primary key must be. */
    public List<String> conflictKey() {
        return conflictKey;
    }

    /** The stream a message type belongs to, or empty when this replica does not consume that type. */
    public static Optional<ReplicaStream> byEventType(String eventType) {
        return eventType == null ? Optional.empty() : Optional.ofNullable(BY_EVENT_TYPE.get(eventType));
    }

    /** The stream the snapshot API names, or empty when the name is not one this replica knows. */
    public static Optional<ReplicaStream> byStreamName(String streamName) {
        return streamName == null ? Optional.empty() : Optional.ofNullable(BY_STREAM_NAME.get(streamName));
    }

    /** Every stream's event type, in the order a rebuild pages them, so a queue can bind all of them. */
    public static List<String> eventTypes() {
        return Arrays.stream(values()).map(ReplicaStream::eventType).toList();
    }
}
