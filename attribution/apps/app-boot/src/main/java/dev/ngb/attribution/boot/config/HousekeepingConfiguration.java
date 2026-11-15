package dev.ngb.attribution.boot.config;

import dev.ngb.attribution.boot.infrastructure.housekeeping.MessagingHousekeeping;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The housekeeping of the two tables the wiring layer owns. It is wired here rather than component-scanned so the class
 * carries no stereotype of its own and the {@code boot.jobs} package keeps one shape: only worker jobs.
 */
@Configuration(proxyBeanMethods = false)
class HousekeepingConfiguration {

    @Bean
    MessagingHousekeeping messagingHousekeeping(
            UnitOfWork unitOfWork, DSLContext dsl, AttributionSettings settings, Clock clock) {
        return new MessagingHousekeeping(unitOfWork, dsl, settings.housekeeping(), clock);
    }
}
