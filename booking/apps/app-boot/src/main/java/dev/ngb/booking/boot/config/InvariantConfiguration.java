package dev.ngb.booking.boot.config;

import dev.ngb.booking.boot.health.InvariantCheck;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the invariant check the smoke script and a health investigation run. */
@Configuration(proxyBeanMethods = false)
class InvariantConfiguration {

    @Bean
    InvariantCheck invariantCheck(JdbcTemplate jdbc, BookingSettings settings) {
        return new InvariantCheck(jdbc, settings.timings().graceSeconds(), 120);
    }
}
