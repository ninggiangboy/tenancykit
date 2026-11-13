package dev.ngb.core.messaging.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.audit.JooqAuditLog;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The audit log against the plain-SQL {@code audit_log} contract every service's migration satisfies: an entry is
 * appended inside the caller's transaction and nowhere else, and a rollback takes it with it.
 */
class JooqAuditLogIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static {
        POSTGRES.start();
    }

    private static final Instant OCCURRED_AT = Instant.parse("2026-11-13T10:00:00Z");
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());

    private PGSimpleDataSource dataSource;
    private DSLContext dsl;
    private UnitOfWork unitOfWork;
    private JooqAuditLog auditLog;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists audit_log");
            statement.execute("""
                    create table audit_log (
                        id          uuid primary key,
                        tenant_id   uuid        not null,
                        actor       text        not null,
                        action      text        not null,
                        subject     text,
                        request_id  text,
                        occurred_at timestamptz not null
                    )""");
        }
        dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
        unitOfWork = new SpringUnitOfWork(new DataSourceTransactionManager(dataSource), TransactionScopeHook.none());
        auditLog = new JooqAuditLog(dsl, new IdGenerator() {
            @Override
            public UUID newId() {
                return UUID.randomUUID();
            }
        });
    }

    @Test
    void anEntryIsAppendedInsideTheUnitOfWork() {
        unitOfWork.transactional(() -> auditLog.record(entry("credential.created")));

        Record row = dsl.fetchOne("select * from audit_log");

        assertThat(row.get("id", UUID.class)).isNotNull();
        assertThat(row.get("tenant_id", UUID.class)).isEqualTo(TENANT.value());
        assertThat(row.get("actor", String.class)).isEqualTo("user/admin");
        assertThat(row.get("action", String.class)).isEqualTo("credential.created");
        assertThat(row.get("subject", String.class)).isEqualTo("tenant-system/svc/credential/1");
        assertThat(row.get("request_id", String.class)).isEqualTo("req-1");
        assertThat(row.get("occurred_at", OffsetDateTime.class).toInstant()).isEqualTo(OCCURRED_AT);
    }

    @Test
    void recordingOutsideAUnitOfWorkIsRefused() {
        assertThatThrownBy(() -> auditLog.record(entry("credential.created")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unit of work");

        assertThat(dsl.fetchCount(DSL.table("audit_log"))).isZero();
    }

    @Test
    void aRolledBackTransactionLeavesNoEntryBehind() {
        assertThatThrownBy(() -> unitOfWork.transactional(() -> {
                    auditLog.record(entry("credential.revoked"));
                    throw new IllegalStateException("the command failed after the audit entry");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(dsl.fetchCount(DSL.table("audit_log"))).isZero();
    }

    private static AuditEntry entry(String action) {
        return AuditEntry.tenant(TENANT, "user/admin", action, "tenant-system/svc/credential/1", "req-1", OCCURRED_AT);
    }
}
