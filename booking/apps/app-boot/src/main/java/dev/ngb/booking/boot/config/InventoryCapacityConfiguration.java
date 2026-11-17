package dev.ngb.booking.boot.config;

import dev.ngb.booking.catalog.api.session.SessionPlaces;
import dev.ngb.booking.inventory.adapter.capacity.persistence.CapacitySessionPlaces;
import dev.ngb.booking.inventory.adapter.capacity.persistence.JooqCapacityEngine;
import dev.ngb.booking.inventory.adapter.capacity.persistence.JooqCapacityUnitReader;
import dev.ngb.booking.inventory.adapter.capacity.persistence.JooqPoolAvailability;
import dev.ngb.booking.inventory.api.capacity.PoolAvailability;
import dev.ngb.booking.inventory.api.engine.InventoryEngine;
import dev.ngb.booking.inventory.application.capacity.port.CapacityUnitReader;
import dev.ngb.booking.inventory.application.capacity.service.CapacityPlanner;
import dev.ngb.booking.inventory.application.capacity.service.CapacityRules;
import dev.ngb.core.tenancy.TenantContext;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the capacity engine: the flash-sale promise a booking claims places with, the counts a read answers with, the
 * places catalog creates and resizes a session's pools through, and the arithmetic a resize obeys.
 */
@Configuration(proxyBeanMethods = false)
class InventoryCapacityConfiguration {

    @Bean
    CapacityRules capacityRules() {
        return new CapacityRules();
    }

    @Bean
    CapacityPlanner capacityPlanner(CapacityRules rules) {
        return new CapacityPlanner(rules);
    }

    @Bean
    CapacityUnitReader capacityUnitReader(DSLContext dsl) {
        return new JooqCapacityUnitReader(dsl);
    }

    @Bean
    InventoryEngine capacityEngine(DSLContext dsl, CapacityUnitReader units, CapacityRules rules) {
        return new JooqCapacityEngine(dsl, units, rules);
    }

    @Bean
    SessionPlaces sessionPlaces(DSLContext dsl, TenantContext tenantContext, CapacityPlanner planner) {
        return new CapacitySessionPlaces(dsl, tenantContext, planner);
    }

    @Bean
    PoolAvailability poolAvailability(CapacityUnitReader units) {
        return new JooqPoolAvailability(units);
    }
}
