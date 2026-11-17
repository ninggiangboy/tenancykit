package dev.ngb.booking.boot.config;

import dev.ngb.booking.booking.adapter.booking.persistence.JooqBookingReadRepository;
import dev.ngb.booking.booking.adapter.booking.persistence.JooqBookingRepository;
import dev.ngb.booking.booking.adapter.series.persistence.JooqSeriesReadRepository;
import dev.ngb.booking.booking.adapter.series.persistence.JooqSeriesRepository;
import dev.ngb.booking.booking.application.booking.command.CancelBookingUseCase;
import dev.ngb.booking.booking.application.booking.command.ConfirmBookingUseCase;
import dev.ngb.booking.booking.application.booking.command.CreateBookingUseCase;
import dev.ngb.booking.booking.application.booking.command.ExpireHoldsUseCase;
import dev.ngb.booking.booking.application.booking.command.ExtendBookingUseCase;
import dev.ngb.booking.booking.application.booking.command.MarkNoShowUseCase;
import dev.ngb.booking.booking.application.booking.command.RescheduleBookingUseCase;
import dev.ngb.booking.booking.application.booking.port.BookingRepository;
import dev.ngb.booking.booking.application.booking.query.BookingQueries;
import dev.ngb.booking.booking.application.booking.query.BookingReadRepository;
import dev.ngb.booking.booking.application.booking.service.BookingEventPublisher;
import dev.ngb.booking.booking.application.booking.service.BookingRules;
import dev.ngb.booking.booking.application.booking.service.InventoryEngines;
import dev.ngb.booking.booking.application.series.command.CancelSeriesUseCase;
import dev.ngb.booking.booking.application.series.command.ConfirmSeriesUseCase;
import dev.ngb.booking.booking.application.series.command.CreateSeriesUseCase;
import dev.ngb.booking.booking.application.series.port.SeriesRepository;
import dev.ngb.booking.booking.application.series.query.SeriesQueries;
import dev.ngb.booking.booking.application.series.query.SeriesReadRepository;
import dev.ngb.booking.catalog.api.calendar.CalendarReader;
import dev.ngb.booking.catalog.api.group.GroupCursorReader;
import dev.ngb.booking.catalog.api.recurrence.RecurrenceReader;
import dev.ngb.booking.inventory.api.assignment.ResourceAssignmentPlanner;
import dev.ngb.booking.inventory.api.engine.InventoryEngine;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the booking lifecycle: the repositories that store a booking and its resources, the rules and engines it is
 * claimed through, the request/response use cases, the read side and the expiry sweep.
 *
 * <p>The clock, the id generator, the unit of work and the outbox come from the infrastructure configuration, so
 * nothing here opens a transaction of its own. The tenant-level timings are read here once and passed to the use cases
 * as plain numbers, so the application layer never sees a settings tree.
 */
@Configuration(proxyBeanMethods = false)
class BookingLifecycleConfiguration {

    /** How many lapsed holds one sweep pass reclaims; the schedule runs again for the rest. */
    private static final int EXPIRE_BATCH_SIZE = 500;

    @Bean
    BookingRepository bookingRepository(DSLContext dsl, TenantContext tenantContext) {
        return new JooqBookingRepository(dsl, tenantContext);
    }

    @Bean
    BookingReadRepository bookingReadRepository(DSLContext dsl) {
        return new JooqBookingReadRepository(dsl);
    }

    @Bean
    BookingQueries bookingQueries(UnitOfWork unitOfWork, BookingReadRepository bookings) {
        return new BookingQueries(unitOfWork, bookings);
    }

    @Bean
    BookingRules bookingRules(CalendarReader calendars) {
        return new BookingRules(calendars);
    }

    @Bean
    InventoryEngines inventoryEngines(List<InventoryEngine> engines) {
        return new InventoryEngines(engines);
    }

    @Bean
    BookingEventPublisher bookingEventPublisher(EventPublisher events, IdGenerator ids) {
        return new BookingEventPublisher(events, ids);
    }

    @Bean
    CreateBookingUseCase createBookingUseCase(
            UnitOfWork unitOfWork,
            BookingRepository bookings,
            BookingRules rules,
            InventoryEngines engines,
            CalendarReader calendars,
            BookingEventPublisher events,
            ResourceAssignmentPlanner planner,
            GroupCursorReader cursors,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock,
            BookingSettings settings) {
        return new CreateBookingUseCase(
                unitOfWork,
                bookings,
                rules,
                engines,
                calendars,
                events,
                planner,
                cursors,
                tenantContext,
                ids,
                clock,
                settings.timings().holdTtlSeconds(),
                settings.timings().graceSeconds());
    }

    @Bean
    ConfirmBookingUseCase confirmBookingUseCase(
            UnitOfWork unitOfWork,
            BookingRepository bookings,
            InventoryEngines engines,
            BookingEventPublisher events,
            Clock clock,
            BookingSettings settings) {
        return new ConfirmBookingUseCase(
                unitOfWork, bookings, engines, events, clock, settings.timings().graceSeconds());
    }

    @Bean
    ExtendBookingUseCase extendBookingUseCase(
            UnitOfWork unitOfWork,
            BookingRepository bookings,
            InventoryEngines engines,
            Clock clock,
            BookingSettings settings) {
        return new ExtendBookingUseCase(
                unitOfWork, bookings, engines, clock, (int) settings.timings().maxExtensionSeconds());
    }

    @Bean
    CancelBookingUseCase cancelBookingUseCase(
            UnitOfWork unitOfWork,
            BookingRepository bookings,
            InventoryEngines engines,
            CalendarReader calendars,
            BookingEventPublisher events,
            Clock clock) {
        return new CancelBookingUseCase(unitOfWork, bookings, engines, calendars, events, clock);
    }

    @Bean
    RescheduleBookingUseCase rescheduleBookingUseCase(
            UnitOfWork unitOfWork,
            BookingRepository bookings,
            BookingRules rules,
            InventoryEngines engines,
            CalendarReader calendars,
            ResourceAssignmentPlanner planner,
            BookingEventPublisher events,
            Clock clock,
            BookingSettings settings) {
        return new RescheduleBookingUseCase(
                unitOfWork,
                bookings,
                rules,
                engines,
                calendars,
                planner,
                events,
                clock,
                settings.timings().graceSeconds());
    }

    @Bean
    MarkNoShowUseCase markNoShowUseCase(
            UnitOfWork unitOfWork, BookingRepository bookings, BookingEventPublisher events, Clock clock) {
        return new MarkNoShowUseCase(unitOfWork, bookings, events, clock);
    }

    @Bean
    SeriesRepository seriesRepository(DSLContext dsl) {
        return new JooqSeriesRepository(dsl);
    }

    @Bean
    SeriesReadRepository seriesReadRepository(DSLContext dsl, BookingReadRepository bookings) {
        return new JooqSeriesReadRepository(dsl, bookings);
    }

    @Bean
    SeriesQueries seriesQueries(UnitOfWork unitOfWork, SeriesReadRepository series) {
        return new SeriesQueries(unitOfWork, series);
    }

    @Bean
    CreateSeriesUseCase createSeriesUseCase(
            UnitOfWork unitOfWork,
            SeriesRepository series,
            CreateBookingUseCase creates,
            BookingRules rules,
            CalendarReader calendars,
            RecurrenceReader recurrence,
            EventPublisher events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new CreateSeriesUseCase(
                unitOfWork, series, creates, rules, calendars, recurrence, events, tenantContext, ids, clock);
    }

    @Bean
    ConfirmSeriesUseCase confirmSeriesUseCase(
            UnitOfWork unitOfWork,
            SeriesRepository series,
            BookingRepository bookings,
            ConfirmBookingUseCase confirms) {
        return new ConfirmSeriesUseCase(unitOfWork, series, bookings, confirms);
    }

    @Bean
    CancelSeriesUseCase cancelSeriesUseCase(
            UnitOfWork unitOfWork,
            SeriesRepository series,
            BookingRepository bookings,
            CancelBookingUseCase cancels,
            EventPublisher events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new CancelSeriesUseCase(
                unitOfWork, series, bookings, cancels, events, tenantContext, ids, clock);
    }

    @Bean
    ExpireHoldsUseCase expireHoldsUseCase(
            UnitOfWork unitOfWork,
            BookingRepository bookings,
            InventoryEngines engines,
            BookingEventPublisher events,
            Clock clock,
            BookingSettings settings) {
        return new ExpireHoldsUseCase(
                unitOfWork,
                bookings,
                engines,
                events,
                clock,
                EXPIRE_BATCH_SIZE,
                settings.timings().graceSeconds());
    }
}
