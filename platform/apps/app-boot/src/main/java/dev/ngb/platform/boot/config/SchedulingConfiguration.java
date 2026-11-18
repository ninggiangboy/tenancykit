package dev.ngb.platform.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.platform.boot.jobs.JobLease;
import dev.ngb.platform.boot.jobs.JooqJobLease;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling is on for the outbox relay, the rate-limit sweep and the console redirect sync; jobs lease their turn. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration {

    @Bean
    JobLease jobLease(DSLContext dsl, Clock clock) {
        return new JooqJobLease(dsl, clock);
    }
}
