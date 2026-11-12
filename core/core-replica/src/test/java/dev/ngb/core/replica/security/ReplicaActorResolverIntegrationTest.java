package dev.ngb.core.replica.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.ActorType;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.spring.tenancy.ScopedTenantContext;
import dev.ngb.core.tenancy.RequestScope;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The actor resolver: a user comes from user_grants in the host's tenant; a Tenant System from its claims. */
class ReplicaActorResolverIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static {
        POSTGRES.start();
    }

    private PGSimpleDataSource dataSource;
    private ScopedTenantContext tenantContext;
    private ReplicaActorResolver resolver;
    private UUID tenantId;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("drop table if exists user_grants, tenant_systems, tenants");
            statement.execute("""
                    create table tenants (
                        id uuid primary key, code text not null unique, name text not null,
                        status text not null, created_at timestamptz not null, version bigint not null default 0)""");
            statement.execute("""
                    create table tenant_systems (
                        id uuid primary key, tenant_id uuid not null references tenants (id), name text not null,
                        status text not null, created_at timestamptz not null, version bigint not null default 0)""");
            statement.execute("""
                    create table user_grants (
                        user_id uuid primary key, tenant_id uuid not null references tenants (id), scope text not null,
                        keycloak_subject text not null unique, email text not null, display_name text not null,
                        status text not null, permissions text[] not null default '{}', version bigint not null default 0)""");
        }
        DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
        UnitOfWork unitOfWork = new SpringUnitOfWork(new DataSourceTransactionManager(dataSource), TransactionScopeHook.none());
        tenantContext = new ScopedTenantContext();
        resolver = new ReplicaActorResolver(unitOfWork, dsl, tenantContext);

        tenantId = UUID.randomUUID();
        Instant now = Instant.now();
        dsl.execute(
                "insert into tenants values (?, ?, ?, 'ACTIVE', ?::timestamptz, 1)",
                tenantId, "acme", "Acme", now.toString());
    }

    @Test
    void aUserComesFromTheGrantsRowOfTheHostsTenant() {
        UUID userId = UUID.randomUUID();
        insertUser(userId, tenantId, "kc-subject-1", "ACTIVE", new String[] {"billing:invoice:read"});

        Optional<Actor> actor = inTenant(() -> resolver.resolve(userJwt("kc-subject-1")));

        assertThat(actor).isPresent();
        assertThat(actor.get().type()).isEqualTo(ActorType.USER);
        assertThat(actor.get().id()).isEqualTo(userId);
        assertThat(actor.get().has("billing:invoice:read")).isTrue();
    }

    @Test
    void aDisabledUserIsNotAnActor() {
        insertUser(UUID.randomUUID(), tenantId, "kc-subject-2", "DISABLED", new String[] {"billing:invoice:read"});

        assertThat(inTenant(() -> resolver.resolve(userJwt("kc-subject-2")))).isEmpty();
    }

    @Test
    void aUserFromAnotherTenantIsNotVisibleHere() {
        insertUser(UUID.randomUUID(), tenantId, "kc-subject-3", "ACTIVE", new String[] {"billing:invoice:read"});
        UUID otherTenant = UUID.randomUUID();

        assertThat(tenantContext.callAs(
                        RequestScope.tenant(new TenantId(otherTenant)),
                        () -> resolver.resolve(userJwt("kc-subject-3"))))
                .isEmpty();
    }

    @Test
    void aTenantSystemComesFromItsClaimsWhenActiveInTheHostsTenant() {
        UUID systemId = UUID.randomUUID();
        insertSystem(systemId, tenantId, "ACTIVE");

        Optional<Actor> actor = inTenant(() -> resolver.resolve(systemJwt(systemId, tenantId)));

        assertThat(actor).isPresent();
        assertThat(actor.get().type()).isEqualTo(ActorType.TENANT_SYSTEM);
        assertThat(actor.get().has("billing:invoice:read")).isTrue();
    }

    @Test
    void aDisabledTenantSystemIsNotAnActor() {
        UUID systemId = UUID.randomUUID();
        insertSystem(systemId, tenantId, "DISABLED");

        assertThat(inTenant(() -> resolver.resolve(systemJwt(systemId, tenantId)))).isEmpty();
    }

    @Test
    void aTenantSystemTokenForAnotherTenantIsRefused() {
        UUID systemId = UUID.randomUUID();
        insertSystem(systemId, tenantId, "ACTIVE");
        UUID otherTenant = UUID.randomUUID();

        assertThatThrownBy(() -> inTenant(() -> resolver.resolve(systemJwt(systemId, otherTenant))))
                .isInstanceOf(ReplicaActorResolver.TenantMismatchException.class);
    }

    private <T> T inTenant(java.util.function.Supplier<T> work) {
        return tenantContext.callAs(RequestScope.tenant(new TenantId(tenantId)), work);
    }

    private void insertUser(UUID id, UUID tenant, String subject, String status, String[] permissions) {
        dsl().execute(
                "insert into user_grants values (?, ?, 'TENANT', ?, ?, 'User', ?, ?, 1)",
                id, tenant, subject, id + "@example.com", status, permissions);
    }

    private void insertSystem(UUID id, UUID tenant, String status) {
        dsl().execute("insert into tenant_systems values (?, ?, 'Partner', ?, now(), 1)", id, tenant, status);
    }

    private DSLContext dsl() {
        return DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    }

    /** A Keycloak-shaped token: only the subject matters to the user path. */
    private static Jwt userJwt(String subject) {
        return Jwt.withTokenValue("user-token")
                .header("alg", "ES256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .audience(List.of("billing"))
                .build();
    }

    /** A Platform Tenant System token: the subject plus the tenant claims. */
    private static Jwt systemJwt(UUID systemId, UUID tenant) {
        return Jwt.withTokenValue("system-token")
                .header("alg", "ES256")
                .subject("tenant-system/" + systemId)
                .claim("tenant_id", tenant.toString())
                .claim("scope", "billing:invoice:read")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .audience(List.of("billing"))
                .build();
    }
}
