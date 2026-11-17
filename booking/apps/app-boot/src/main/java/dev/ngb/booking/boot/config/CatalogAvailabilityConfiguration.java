package dev.ngb.booking.boot.config;

import dev.ngb.booking.catalog.api.calendar.CalendarReader;
import dev.ngb.booking.catalog.api.group.GroupCursorReader;
import dev.ngb.booking.catalog.adapter.group.persistence.JooqGroupCursorReader;
import dev.ngb.booking.catalog.application.block.query.BlockReadRepository;
import dev.ngb.booking.catalog.application.calendar.query.CalendarReadRepository;
import dev.ngb.booking.catalog.application.catalog.service.CalendarReaderService;
import dev.ngb.booking.catalog.application.group.query.GroupReadRepository;
import dev.ngb.booking.catalog.application.offering.query.OfferingReadRepository;
import dev.ngb.booking.catalog.application.recurrence.service.RecurrenceValidator;
import dev.ngb.booking.catalog.application.resource.query.ResourceReadRepository;
import dev.ngb.booking.catalog.application.session.query.SessionReadRepository;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes the catalog's calendar read port to inventory, the one context that computes availability from it. */
@Configuration(proxyBeanMethods = false)
class CatalogAvailabilityConfiguration {

    @Bean
    CalendarReader calendarReader(
            ResourceReadRepository resources,
            CalendarReadRepository calendars,
            OfferingReadRepository offerings,
            GroupReadRepository groups,
            BlockReadRepository blocks,
            SessionReadRepository sessions,
            RecurrenceValidator recurrence) {
        return new CalendarReaderService(resources, calendars, offerings, groups, blocks, sessions, recurrence);
    }

    @Bean
    GroupCursorReader groupCursorReader(DSLContext dsl) {
        return new JooqGroupCursorReader(dsl);
    }
}
