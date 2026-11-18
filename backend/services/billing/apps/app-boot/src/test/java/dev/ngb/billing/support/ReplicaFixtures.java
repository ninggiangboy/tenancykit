package dev.ngb.billing.support;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.id.TenantSystemId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seeds the replica tables directly, as Platform would through the snapshot and events. Billing no longer owns
 * users, roles or tenant systems, so a test that needs one writes it here instead of calling a Billing endpoint.
 *
 * <p>Rows are written as the migration owner ({@link OwnerDatabase}), so row-level security never hides them and the
 * seeded data matches what the replica consumer would store.
 */
@Component
public class ReplicaFixtures {

    private static final String PLATFORM_TENANT = "00000000-0000-7000-8000-000000000001";

    private final JdbcTemplate jdbc;
    private final String baseDomain;

    @org.springframework.beans.factory.annotation.Autowired
    ReplicaFixtures(
            org.springframework.core.env.Environment environment,
            @org.springframework.beans.factory.annotation.Value("${billing.tenancy.base-domain:localhost}") String baseDomain) {
        this.baseDomain = baseDomain;
        // Seed as the migration owner, so row-level security never hides a row a test just wrote. A context that never
        // seeds (a test that only boots the app to inspect it) supplies no URL; the first seed then fails loudly.
        // The migration owner is the container superuser; a context that never seeds supplies no URL.
        String url = environment.getProperty("spring.flyway.url");
        if (url == null || url.isBlank()) {
            this.jdbc = null;
        } else {
            String user = orNull(() -> environment.getProperty("spring.flyway.user"));
            String password = orNull(() -> environment.getProperty("spring.flyway.password"));
            org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(url, user, password);
            dataSource.setDriverClassName("org.postgresql.Driver");
            this.jdbc = new JdbcTemplate(dataSource);
        }
    }

    /** Reads a property, treating an unresolvable placeholder as absent. */
    private static String orNull(java.util.function.Supplier<String> read) {
        try {
            return read.get();
        } catch (RuntimeException unresolved) {
            return "";
        }
    }

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            throw new IllegalStateException("ReplicaFixtures has no database URL in this context");
        }
        return jdbc;
    }

    /** A record of one seeded tenant. */
    public record TenantSeed(TenantId id, String code, String host) {}

    /** A fresh active tenant with a tenant subdomain that resolves to it. */
    public TenantSeed tenant() {
        String code = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc().update(
                "insert into tenants (id, code, name, status, created_at, version) values (?, ?, ?, 'ACTIVE', ?, 1)",
                id, code, "Tenant " + code, now);
        String host = code + "." + baseDomain;
        jdbc().update(
                "insert into tenant_domains (id, tenant_id, hostname, kind, created_at, version) values (?, ?, ?, 'TENANT_SUBDOMAIN', ?, 1)",
                UUID.randomUUID(), id, host, now);
        enableApp(id, "billing");
        return new TenantSeed(new TenantId(id), code, host);
    }

    /** The platform pseudo-tenant's hostname, so a request to the platform host resolves. */
    public void platformDomain() {
        jdbc().update(
                "insert into tenant_domains (id, tenant_id, hostname, kind, created_at, version) values (?, null, ?, 'PLATFORM', ?, 1) on conflict (hostname) do nothing",
                UUID.randomUUID(), "platform." + baseDomain, Timestamp.from(Instant.now()));
    }

    /** Marks the tenant as entitled (or not) to the app. */
    public void enableApp(UUID tenantId, String appCode) {
        jdbc().update(
                """
                insert into app_entitlements (tenant_id, app_code, enabled, version) values (?, ?, true, 1)
                on conflict (tenant_id, app_code) do update set enabled = true, version = app_entitlements.version + 1
                """,
                tenantId, appCode);
    }

    public void disableApp(UUID tenantId, String appCode) {
        jdbc().update(
                """
                insert into app_entitlements (tenant_id, app_code, enabled, version) values (?, ?, false, 1)
                on conflict (tenant_id, app_code) do update set enabled = false, version = app_entitlements.version + 1
                """,
                tenantId, appCode);
    }

    /** A Tenant System row that a seeded credential's token can name. */
    public TenantSystemId tenantSystem(UUID tenantId, String name) {
        UUID id = UUID.randomUUID();
        jdbc().update(
                "insert into tenant_systems (id, tenant_id, name, status, created_at, version) values (?, ?, ?, 'ACTIVE', ?, 1)",
                id, tenantId, name, Timestamp.from(Instant.now()));
        return new TenantSystemId(id);
    }

    /** A user row with the given permission codes, keyed by the Keycloak subject the token carries. */
    public UUID user(UUID tenantId, String keycloakSubject, String... permissions) {
        UUID id = UUID.randomUUID();
        java.sql.Array codes;
        try (java.sql.Connection connection = jdbc().getDataSource().getConnection()) {
            codes = connection.createArrayOf("text", permissions);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not build the permission array", e);
        }
        jdbc().update(
                """
                insert into user_grants (user_id, tenant_id, scope, keycloak_subject, email, display_name, status, permissions, version)
                values (?, ?, 'TENANT', ?, ?, ?, 'ACTIVE', ?, 1)
                """,
                id, tenantId, keycloakSubject, keycloakSubject + "@example.com", "User " + id, codes);
        return id;
    }

    /** The reserved Platform tenant's id, for a seeded platform user. */
    public static UUID platformTenantId() {
        return UUID.fromString(PLATFORM_TENANT);
    }
}
