package dev.ngb.booking.boot.config;

import dev.ngb.booking.catalog.api.calendar.CalendarReader;
import dev.ngb.booking.inventory.adapter.availability.persistence.JooqBusyRangeReader;
import dev.ngb.booking.inventory.adapter.interval.persistence.JooqIntervalEngine;
import dev.ngb.booking.inventory.api.engine.InventoryEngine;
import dev.ngb.booking.inventory.application.availability.port.BusyRangeReader;
import dev.ngb.booking.inventory.application.availability.query.AvailabilityQueries;
import dev.ngb.booking.inventory.application.availability.service.AvailabilityCalculator;
import dev.ngb.booking.inventory.application.interval.service.IntervalClaimRules;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the inventory interval engine and availability: the range claim that no two of a resource overlap, the claim
 * rows availability subtracts, the calculator that reads the catalog's calendars through its port, and the query the
 * availability handler calls.
 *
 * <p>The claim rows and the calculation are adapter and application details; this class is the one place that names the
 * jOOQ engine behind the {@code InventoryEngine} port, so the booking lifecycle sees only the port.
 */
@Configuration(proxyBeanMethods = false)
class InventoryIntervalConfiguration {

    @Bean
    IntervalClaimRules intervalClaimRules() {
        return new IntervalClaimRules();
    }

    @Bean
    InventoryEngine intervalEngine(DSLContext dsl, TenantContext tenantContext, IntervalClaimRules rules) {
        return new JooqIntervalEngine(dsl, tenantContext, rules);
    }

    @Bean
    BusyRangeReader busyRangeReader(DSLContext dsl, Clock clock) {
        return new JooqBusyRangeReader(dsl, clock);
    }

    @Bean
    AvailabilityCalculator availabilityCalculator(BusyRangeReader busyRanges) {
        return new AvailabilityCalculator(busyRanges);
    }

    @Bean
    AvailabilityQueries availabilityQueries(
            UnitOfWork unitOfWork, CalendarReader calendars, Clock clock, AvailabilityCalculator calculator) {
        return new AvailabilityQueries(unitOfWork, calendars, clock, calculator);
    }
}
