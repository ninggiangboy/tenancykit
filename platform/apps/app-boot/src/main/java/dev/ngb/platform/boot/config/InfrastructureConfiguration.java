package dev.ngb.platform.boot.config;

import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.event.EventInbox;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.messaging.inbox.JooqEventInbox;
import dev.ngb.core.messaging.outbox.JooqOutboxAppender;
import dev.ngb.core.spring.audit.JooqAuditLog;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.spring.runtime.SystemClock;
import dev.ngb.core.spring.runtime.UuidV7IdGenerator;
import dev.ngb.core.spring.tenancy.ScopedTenantContext;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.boot.tenancy.PlatformTransactionScopeHook;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** Wires the technical ports the tenancy context shares. Holds no business rules. */
@Configuration(proxyBeanMethods = false)
class InfrastructureConfiguration {

    @Bean
    TenantContext tenantContext() {
        return new ScopedTenantContext();
    }

    @Bean
    UnitOfWork unitOfWork(PlatformTransactionManager transactionManager, JdbcTemplate jdbc, TenantContext tenantContext) {
        return new SpringUnitOfWork(transactionManager, new PlatformTransactionScopeHook(jdbc, tenantContext));
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
}
