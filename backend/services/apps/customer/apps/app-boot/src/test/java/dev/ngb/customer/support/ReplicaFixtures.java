package dev.ngb.customer.support;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.id.TenantSystemId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

/**
 * Seeds the replica tables directly, as Platform would through the snapshot and its events. A test that needs a
 * tenant, a hostname, a user or a Tenant System writes it here instead of calling an endpoint customer does not have.
 *
 * <p>Rows are written as the migration owner, so row-level security never hides them and the seeded data matches what
 * the replica consumer would store.
 */
@Component
public class ReplicaFixtures {

    /** The reserved Platform tenant the baseline creates. */
    private static final String PLATFORM_TENANT = "00000000-0000-7000-8000-000000000001";

    /** The base domain the seeded tenant hostnames live under. */
    private static final String BASE_DOMAIN = "localhost";

    private final JdbcTemplate jdbc;

    ReplicaFixtures(Environment environment) {
        // Seed as the migration owner, so row-level security never hides a row a test just wrote. A context that never
        // seeds (a test that only boots the app to inspect it) supplies no URL; the first seed then fails loudly.
        String url = environment.getProperty("spring.flyway.url");
        if (url == null || url.isBlank()) {
            this.jdbc = null;
        } else {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    url,
                    environment.getProperty("spring.flyway.user"),
                    environment.getProperty("spring.flyway.password"));
            dataSource.setDriverClassName("org.postgresql.Driver");
            this.jdbc = new JdbcTemplate(dataSource);
        }
    }

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            throw new IllegalStateException("ReplicaFixtures has no database URL in this context");
        }
        return jdbc;
    }

    /** A record of one seeded tenant: its id, code, hostname and cantonical platform hostname. */
    public record TenantSeed(TenantId id, String code, String host) {}

    /** A fresh active tenant, entitled to customer, with a tenant subdomain that resolves to it. */
    public TenantSeed tenant() {
        String code = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc().update(
                "insert into tenants (id, code, name, status, created_at, version) values (?, ?, ?, 'ACTIVE', ?, 1)",
                id, code, "Tenant " + code, now);
        String host = code + "." + BASE_DOMAIN;
        jdbc().update(
                "insert into tenant_domains (id, tenant_id, hostname, kind, created_at, version) values (?, ?, ?, 'TENANT_SUBDOMAIN', ?, 1)",
                UUID.randomUUID(), id, host, now);
        enableApp(id, "customer");
        return new TenantSeed(new TenantId(id), code, host);
    }

    /** The platform pseudo-tenant's hostname, so a request to the platform host resolves to the platform scope. */
    public String platformHost() {
        String host = "platform." + BASE_DOMAIN;
        jdbc().update(
                "insert into tenant_domains (id, tenant_id, hostname, kind, created_at, version) values (?, null, ?, 'PLATFORM', ?, 1) on conflict (hostname) do nothing",
                UUID.randomUUID(), host, Timestamp.from(Instant.now()));
        return host;
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

    /** Revokes the app for the tenant, so the entitlement filter refuses every endpoint of it. */
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
        return seedUser(tenantId, "TENANT", keycloakSubject, permissions);
    }

    /** A Platform-scope user: it authenticates, and every tenant endpoint must then refuse it with 403. */
    public UUID platformUser(String keycloakSubject, String... permissions) {
        return seedUser(platformTenantId(), "PLATFORM", keycloakSubject, permissions);
    }

    private UUID seedUser(UUID tenantId, String scope, String keycloakSubject, String... permissions) {
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
                values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, 1)
                """,
                id, tenantId, scope, keycloakSubject, keycloakSubject + "@example.com", "User " + id, codes);
        return id;
    }

    /** The reserved Platform tenant's id, for a seeded platform user. */
    public static UUID platformTenantId() {
        return UUID.fromString(PLATFORM_TENANT);
    }
}
