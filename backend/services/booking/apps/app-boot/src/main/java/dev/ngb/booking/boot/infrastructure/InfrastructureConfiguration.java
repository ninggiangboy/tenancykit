package dev.ngb.booking.boot.infrastructure;

import dev.ngb.core.event.EventInbox;
import dev.ngb.core.messaging.inbox.JooqEventInbox;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.spring.runtime.SystemClock;
import dev.ngb.core.spring.runtime.UuidV7IdGenerator;
import dev.ngb.core.spring.tenancy.ScopedTenantContext;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.booking.boot.infrastructure.tenancy.BookingTransactionScopeHook;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the technical ports every context shares, and is the only configuration that sees the transaction manager.
 *
 * <p>It is also the only class that declares {@code clock} and {@code idGenerator}: two configurations declaring them
 * fail at startup with {@code BeanDefinitionOverrideException}.
 */
@Configuration(proxyBeanMethods = false)
class InfrastructureConfiguration {

    @Bean
    TenantContext tenantContext() {
        return new ScopedTenantContext();
    }

    @Bean
    UnitOfWork unitOfWork(
            PlatformTransactionManager transactionManager, JdbcTemplate jdbc, TenantContext tenantContext) {
        return new SpringUnitOfWork(transactionManager, new BookingTransactionScopeHook(jdbc, tenantContext));
    }

    @Bean
    Clock clock() {
        return new SystemClock();
    }

    @Bean
    IdGenerator idGenerator(Clock clock) {
        return new UuidV7IdGenerator(clock);
    }

    @Bean
    EventInbox eventInbox(DSLContext dsl) {
        return new JooqEventInbox(dsl);
    }
}
