package dev.ngb.core.replica;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * One guarded upsert per replica stream. Every stream's payload carries a monotonic {@code version}; an event whose
 * version is not newer than the stored row is a no-op, so neither arrival order nor redelivery matters.
 *
 * <p>The guard is in the {@code where} clause of the conflict update:
 * {@code insert ... on conflict (key) do update set ... where stored.version < excluded.version}. Equal versions are
 * ignored, so the snapshot pages and live messages may interleave freely.
 *
 * <p>Which event type belongs to which stream, and which table and columns that stream writes, is
 * {@link ReplicaStream}'s to say; a payload that lacks a required field is refused with an
 * {@link IllegalArgumentException} rather than stored half-populated.
 */
public class ReplicaWriter {

    private final DSLContext dsl;

    public ReplicaWriter(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Applies one event payload of the replicated types; a type no stream declares is ignored. */
    public boolean apply(String type, Map<String, Object> data) {
        return ReplicaStream.byEventType(type).map(stream -> apply(stream, data)).orElse(false);
    }

    /**
     * Applies one payload of the stream's type.
     *
     * @throws IllegalArgumentException when the payload lacks a field the stream requires, or carries one of a type
     *     the stream cannot store; the caller rejects the event rather than writing a half-populated row
     */
    public boolean apply(ReplicaStream stream, Map<String, Object> data) {
        return switch (stream) {
            case TENANTS -> tenant(data);
            case TENANT_DOMAINS -> tenantDomain(data);
            case TENANT_SYSTEMS -> tenantSystem(data);
            case USER_ACCESS -> userAccess(data);
            case ENTITLEMENTS -> entitlement(data);
        };
    }

    public boolean tenant(Map<String, Object> data) {
        UUID id = uuid(data, "id");
        long version = version(data);
        return dsl.insertInto(ReplicaTables.TENANTS)
                .set(ReplicaTables.TENANT_ID, id)
                .set(ReplicaTables.TENANT_CODE, string(data, "code"))
                .set(ReplicaTables.TENANT_NAME, string(data, "name"))
                .set(ReplicaTables.TENANT_STATUS, string(data, "status"))
                .set(ReplicaTables.TENANT_CREATED_AT, timestamp(data, "created_at"))
                .set(ReplicaTables.TENANT_VERSION, version)
                .onConflict(ReplicaTables.TENANT_ID)
                .doUpdate()
                .set(ReplicaTables.TENANT_CODE, string(data, "code"))
                .set(ReplicaTables.TENANT_NAME, string(data, "name"))
                .set(ReplicaTables.TENANT_STATUS, string(data, "status"))
                .set(ReplicaTables.TENANT_VERSION, version)
                .where(ReplicaTables.TENANT_VERSION.lt(version))
                .execute() > 0;
    }

    public boolean tenantDomain(Map<String, Object> data) {
        UUID id = uuid(data, "id");
        long version = version(data);
        return dsl.insertInto(ReplicaTables.TENANT_DOMAINS)
                .set(ReplicaTables.DOMAIN_ID, id)
                .set(ReplicaTables.DOMAIN_TENANT_ID, nullableUuid(data, "tenant_id"))
                .set(ReplicaTables.DOMAIN_HOSTNAME, string(data, "hostname"))
                .set(ReplicaTables.DOMAIN_KIND, string(data, "kind"))
                .set(ReplicaTables.DOMAIN_CREATED_AT, timestamp(data, "created_at"))
                .set(ReplicaTables.DOMAIN_VERSION, version)
                .onConflict(ReplicaTables.DOMAIN_ID)
                .doUpdate()
                .set(ReplicaTables.DOMAIN_TENANT_ID, nullableUuid(data, "tenant_id"))
                .set(ReplicaTables.DOMAIN_HOSTNAME, string(data, "hostname"))
                .set(ReplicaTables.DOMAIN_KIND, string(data, "kind"))
                .set(ReplicaTables.DOMAIN_VERSION, version)
                .where(ReplicaTables.DOMAIN_VERSION.lt(version))
                .execute() > 0;
    }

    public boolean tenantSystem(Map<String, Object> data) {
        UUID id = uuid(data, "id");
        long version = version(data);
        return dsl.insertInto(ReplicaTables.TENANT_SYSTEMS)
                .set(ReplicaTables.SYSTEM_ID, id)
                .set(ReplicaTables.SYSTEM_TENANT_ID, uuid(data, "tenant_id"))
                .set(ReplicaTables.SYSTEM_NAME, string(data, "name"))
                .set(ReplicaTables.SYSTEM_STATUS, string(data, "status"))
                .set(ReplicaTables.SYSTEM_CREATED_AT, timestamp(data, "created_at"))
                .set(ReplicaTables.SYSTEM_VERSION, version)
                .onConflict(ReplicaTables.SYSTEM_ID)
                .doUpdate()
                .set(ReplicaTables.SYSTEM_TENANT_ID, uuid(data, "tenant_id"))
                .set(ReplicaTables.SYSTEM_NAME, string(data, "name"))
                .set(ReplicaTables.SYSTEM_STATUS, string(data, "status"))
                .set(ReplicaTables.SYSTEM_VERSION, version)
                .where(ReplicaTables.SYSTEM_VERSION.lt(version))
                .execute() > 0;
    }

    public boolean userAccess(Map<String, Object> data) {
        UUID userId = uuid(data, "user_id");
        long version = version(data);
        String[] permissions = permissions(data);
        return dsl.insertInto(ReplicaTables.USER_GRANTS)
                .set(ReplicaTables.GRANT_USER_ID, userId)
                .set(ReplicaTables.GRANT_TENANT_ID, uuid(data, "tenant_id"))
                .set(ReplicaTables.GRANT_SCOPE, string(data, "scope"))
                .set(ReplicaTables.GRANT_SUBJECT, string(data, "keycloak_subject"))
                .set(ReplicaTables.GRANT_EMAIL, string(data, "email"))
                .set(ReplicaTables.GRANT_DISPLAY_NAME, string(data, "display_name"))
                .set(ReplicaTables.GRANT_STATUS, string(data, "status"))
                .set(ReplicaTables.GRANT_PERMISSIONS, permissions)
                .set(ReplicaTables.GRANT_VERSION, version)
                .onConflict(ReplicaTables.GRANT_USER_ID)
                .doUpdate()
                .set(ReplicaTables.GRANT_TENANT_ID, uuid(data, "tenant_id"))
                .set(ReplicaTables.GRANT_SCOPE, string(data, "scope"))
                .set(ReplicaTables.GRANT_SUBJECT, string(data, "keycloak_subject"))
                .set(ReplicaTables.GRANT_EMAIL, string(data, "email"))
                .set(ReplicaTables.GRANT_DISPLAY_NAME, string(data, "display_name"))
                .set(ReplicaTables.GRANT_STATUS, string(data, "status"))
                .set(ReplicaTables.GRANT_PERMISSIONS, permissions)
                .set(ReplicaTables.GRANT_VERSION, version)
                .where(ReplicaTables.GRANT_VERSION.lt(version))
                .execute() > 0;
    }

    public boolean entitlement(Map<String, Object> data) {
        UUID tenantId = uuid(data, "tenant_id");
        String appCode = string(data, "app_code");
        long version = version(data);
        boolean enabled = Boolean.TRUE.equals(data.get("enabled"));
        return dsl.insertInto(ReplicaTables.APP_ENTITLEMENTS)
                .set(ReplicaTables.ENTITLEMENT_TENANT_ID, tenantId)
                .set(ReplicaTables.ENTITLEMENT_APP_CODE, appCode)
                .set(ReplicaTables.ENTITLEMENT_ENABLED, enabled)
                .set(ReplicaTables.ENTITLEMENT_VERSION, version)
                .onConflict(ReplicaTables.ENTITLEMENT_TENANT_ID, ReplicaTables.ENTITLEMENT_APP_CODE)
                .doUpdate()
                .set(ReplicaTables.ENTITLEMENT_ENABLED, enabled)
                .set(ReplicaTables.ENTITLEMENT_VERSION, version)
                .where(ReplicaTables.ENTITLEMENT_VERSION.lt(version))
                .execute() > 0;
    }

    /** Whether the replica holds no tenant other than the reserved platform pseudo-tenant. */
    public boolean tenantsMissing() {
        return dsl.fetchCount(
                        DSL.selectOne()
                                .from(ReplicaTables.TENANTS)
                                .where(ReplicaTables.TENANT_CODE.ne(ReplicaTables.PLATFORM_TENANT_CODE)))
                == 0;
    }

    private static long version(Map<String, Object> data) {
        Object value = data.get("version");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            throw new IllegalArgumentException("a replica event must carry a version");
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static UUID uuid(Map<String, Object> data, String field) {
        UUID value = nullableUuid(data, field);
        if (value == null) {
            throw new IllegalArgumentException("a replica event must carry " + field);
        }
        return value;
    }

    private static UUID nullableUuid(Map<String, Object> data, String field) {
        Object value = data.get(field);
        return value == null ? null : UUID.fromString(String.valueOf(value));
    }

    private static String string(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (value == null) {
            throw new IllegalArgumentException("a replica event must carry " + field);
        }
        return String.valueOf(value);
    }

    private static OffsetDateTime timestamp(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (value == null) {
            throw new IllegalArgumentException("a replica event must carry " + field);
        }
        return Instant.parse(String.valueOf(value)).atOffset(ZoneOffset.UTC);
    }

    /**
     * The permission codes an event carries. A missing or non-list field is rejected, never read as an empty set: an
     * empty array would clear every right the user holds, which is the one thing a state-transfer event must never do
     * by accident. A producer that means "no permissions" sends an empty list.
     */
    private static String[] permissions(Map<String, Object> data) {
        Object value = data.get("permissions");
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "a user-access.changed event must carry permissions as a list of codes, not " + describe(value));
        }
        return list.stream().map(String::valueOf).toArray(String[]::new);
    }

    /** What a payload field held, for a message that must say why the event was refused. */
    private static String describe(Object value) {
        return value == null ? "absent" : "a " + value.getClass().getSimpleName();
    }
}
