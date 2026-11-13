package dev.ngb.core.replica;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

/**
 * The replica tables, addressed with plain SQL names: a table belongs to the app's generated schema, which core never
 * sees. The column contract every app's migration must satisfy lives in {@code replica-tables.sql} next to this class,
 * and {@link ReplicaStream} says which stream writes which of them.
 *
 * <p>Three of the tables keep the names the suite's first migrations used, so an app that already owns them keeps its
 * foreign keys and row-level security untouched; the two tables a replica adds are {@code user_grants} and
 * {@code app_entitlements}.
 */
public final class ReplicaTables {

    public static final Table<Record> TENANTS = DSL.table(DSL.name("tenants"));
    public static final Field<UUID> TENANT_ID = DSL.field(DSL.name("tenants", "id"), SQLDataType.UUID);
    public static final Field<String> TENANT_CODE = DSL.field(DSL.name("tenants", "code"), SQLDataType.VARCHAR);
    public static final Field<String> TENANT_NAME = DSL.field(DSL.name("tenants", "name"), SQLDataType.VARCHAR);
    public static final Field<String> TENANT_STATUS = DSL.field(DSL.name("tenants", "status"), SQLDataType.VARCHAR);
    public static final Field<OffsetDateTime> TENANT_CREATED_AT =
            DSL.field(DSL.name("tenants", "created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    public static final Field<Long> TENANT_VERSION = DSL.field(DSL.name("tenants", "version"), SQLDataType.BIGINT);

    public static final Table<Record> TENANT_DOMAINS = DSL.table(DSL.name("tenant_domains"));
    public static final Field<UUID> DOMAIN_ID = DSL.field(DSL.name("tenant_domains", "id"), SQLDataType.UUID);
    public static final Field<UUID> DOMAIN_TENANT_ID = DSL.field(DSL.name("tenant_domains", "tenant_id"), SQLDataType.UUID);
    public static final Field<String> DOMAIN_HOSTNAME = DSL.field(DSL.name("tenant_domains", "hostname"), SQLDataType.VARCHAR);
    public static final Field<String> DOMAIN_KIND = DSL.field(DSL.name("tenant_domains", "kind"), SQLDataType.VARCHAR);
    public static final Field<OffsetDateTime> DOMAIN_CREATED_AT =
            DSL.field(DSL.name("tenant_domains", "created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    public static final Field<Long> DOMAIN_VERSION = DSL.field(DSL.name("tenant_domains", "version"), SQLDataType.BIGINT);

    public static final Table<Record> TENANT_SYSTEMS = DSL.table(DSL.name("tenant_systems"));
    public static final Field<UUID> SYSTEM_ID = DSL.field(DSL.name("tenant_systems", "id"), SQLDataType.UUID);
    public static final Field<UUID> SYSTEM_TENANT_ID = DSL.field(DSL.name("tenant_systems", "tenant_id"), SQLDataType.UUID);
    public static final Field<String> SYSTEM_NAME = DSL.field(DSL.name("tenant_systems", "name"), SQLDataType.VARCHAR);
    public static final Field<String> SYSTEM_STATUS = DSL.field(DSL.name("tenant_systems", "status"), SQLDataType.VARCHAR);
    public static final Field<OffsetDateTime> SYSTEM_CREATED_AT =
            DSL.field(DSL.name("tenant_systems", "created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    public static final Field<Long> SYSTEM_VERSION = DSL.field(DSL.name("tenant_systems", "version"), SQLDataType.BIGINT);

    /** One row per user: who they are in Keycloak, their tenant and the permission codes this app enforces. */
    public static final Table<Record> USER_GRANTS = DSL.table(DSL.name("user_grants"));
    public static final Field<UUID> GRANT_USER_ID = DSL.field(DSL.name("user_grants", "user_id"), SQLDataType.UUID);
    public static final Field<UUID> GRANT_TENANT_ID = DSL.field(DSL.name("user_grants", "tenant_id"), SQLDataType.UUID);
    public static final Field<String> GRANT_SCOPE = DSL.field(DSL.name("user_grants", "scope"), SQLDataType.VARCHAR);
    public static final Field<String> GRANT_SUBJECT = DSL.field(DSL.name("user_grants", "keycloak_subject"), SQLDataType.VARCHAR);
    public static final Field<String> GRANT_EMAIL = DSL.field(DSL.name("user_grants", "email"), SQLDataType.VARCHAR);
    public static final Field<String> GRANT_DISPLAY_NAME =
            DSL.field(DSL.name("user_grants", "display_name"), SQLDataType.VARCHAR);
    public static final Field<String> GRANT_STATUS = DSL.field(DSL.name("user_grants", "status"), SQLDataType.VARCHAR);
    public static final Field<String[]> GRANT_PERMISSIONS =
            DSL.field(DSL.name("user_grants", "permissions"), SQLDataType.VARCHAR.array());
    public static final Field<Long> GRANT_VERSION = DSL.field(DSL.name("user_grants", "version"), SQLDataType.BIGINT);

    /** One row per (tenant, app): whether the tenant may use the app at all. */
    public static final Table<Record> APP_ENTITLEMENTS = DSL.table(DSL.name("app_entitlements"));
    public static final Field<UUID> ENTITLEMENT_TENANT_ID =
            DSL.field(DSL.name("app_entitlements", "tenant_id"), SQLDataType.UUID);
    public static final Field<String> ENTITLEMENT_APP_CODE =
            DSL.field(DSL.name("app_entitlements", "app_code"), SQLDataType.VARCHAR);
    public static final Field<Boolean> ENTITLEMENT_ENABLED =
            DSL.field(DSL.name("app_entitlements", "enabled"), SQLDataType.BOOLEAN);
    public static final Field<Long> ENTITLEMENT_VERSION =
            DSL.field(DSL.name("app_entitlements", "version"), SQLDataType.BIGINT);

    /** The reserved Platform tenant's code; a rebuild ignores it when deciding whether the replica is empty. */
    public static final String PLATFORM_TENANT_CODE = "platform";

    private ReplicaTables() {}
}
