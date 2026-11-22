package dev.ngb.customer.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.customer.boot.infrastructure.lease.JobLeases;
import dev.ngb.customer.boot.jobs.MessagingHousekeeping;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The two collaborators the scheduled jobs need. Both belong to the worker: the api runtime serves HTTP and owns no
 * job, and a migrate runtime must not touch a table before the migration that creates it has run.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
class JobsConfiguration {

    @Bean
    JobLeases jobLeases(PlatformTransactionManager transactionManager, JdbcTemplate jdbc) {
        return new JobLeases(transactionManager, jdbc);
    }

    @Bean
    MessagingHousekeeping messagingHousekeeping(
            UnitOfWork unitOfWork, DSLContext dsl, CustomerSettings settings, Clock clock) {
        return new MessagingHousekeeping(unitOfWork, dsl, settings.housekeeping(), clock);
    }
}
