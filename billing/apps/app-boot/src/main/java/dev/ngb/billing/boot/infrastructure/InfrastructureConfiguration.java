package dev.ngb.billing.boot.infrastructure;

import dev.ngb.core.event.EventInbox;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.spring.runtime.SystemClock;
import dev.ngb.core.spring.runtime.UuidV7IdGenerator;
import dev.ngb.billing.boot.infrastructure.coordination.JobLeases;
import dev.ngb.billing.boot.infrastructure.coordination.JobRuns;
import dev.ngb.core.messaging.inbox.JooqEventInbox;
import dev.ngb.core.messaging.outbox.JooqOutboxAppender;
import dev.ngb.core.spring.audit.JooqAuditLog;
import dev.ngb.billing.boot.infrastructure.tenancy.BillingTransactionScopeHook;
import dev.ngb.core.spring.tenancy.ScopedTenantContext;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.tenancy.TenantContext;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** Wires the technical ports every context shares; it is the only configuration that sees the transaction manager. */
@Configuration(proxyBeanMethods = false)
class InfrastructureConfiguration {

    @Bean
    TenantContext tenantContext() {
        return new ScopedTenantContext();
    }

    @Bean
    UnitOfWork unitOfWork(PlatformTransactionManager transactionManager, JdbcTemplate jdbc, TenantContext tenantContext) {
        return new SpringUnitOfWork(transactionManager, new BillingTransactionScopeHook(jdbc, tenantContext));
    }

    @Bean
    Clock clock() {
        return new SystemClock();
    }

    @Bean
    IdGenerator idGenerator() {
        return new UuidV7IdGenerator();
    }

    @Bean
    EventPublisher eventPublisher(DSLContext dsl, JsonMapper json) {
        return new JooqOutboxAppender(dsl, json);
    }

    @Bean
    EventInbox eventInbox(DSLContext dsl) {
        return new JooqEventInbox(dsl);
    }

    @Bean
    AuditLog auditLog(DSLContext dsl, IdGenerator ids) {
        return new JooqAuditLog(dsl, ids);
    }

    @Bean
    JobLeases jobLeases(PlatformTransactionManager transactionManager, JdbcTemplate jdbc) {
        return new JobLeases(transactionManager, jdbc);
    }

    @Bean
    JobRuns jobRuns(PlatformTransactionManager transactionManager, JdbcTemplate jdbc) {
        return new JobRuns(transactionManager, jdbc);
    }

}
