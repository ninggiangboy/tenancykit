package dev.ngb.booking.boot.config;

import dev.ngb.booking.catalog.adapter.calendar.persistence.JooqCalendarReadRepository;
import dev.ngb.booking.catalog.adapter.calendar.persistence.JooqCalendarRepository;
import dev.ngb.booking.catalog.adapter.resource.persistence.JooqResourceReadRepository;
import dev.ngb.booking.catalog.adapter.resource.persistence.JooqResourceRepository;
import dev.ngb.booking.catalog.application.calendar.command.AddCalendarExceptionUseCase;
import dev.ngb.booking.catalog.application.calendar.command.DeleteCalendarExceptionUseCase;
import dev.ngb.booking.catalog.application.calendar.command.ReplaceWorkingHoursUseCase;
import dev.ngb.booking.catalog.application.calendar.port.CalendarRepository;
import dev.ngb.booking.catalog.application.calendar.query.CalendarQueries;
import dev.ngb.booking.catalog.application.calendar.query.CalendarReadRepository;
import dev.ngb.booking.catalog.application.resource.command.ArchiveResourceUseCase;
import dev.ngb.booking.catalog.application.resource.command.CreateResourceUseCase;
import dev.ngb.booking.catalog.application.resource.command.PatchResourceUseCase;
import dev.ngb.booking.catalog.application.resource.port.ResourceRepository;
import dev.ngb.booking.catalog.application.resource.query.ResourceQueries;
import dev.ngb.booking.catalog.application.resource.query.ResourceReadRepository;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the catalog's resources and calendars: their repositories, the read queries and the use cases that create,
 * change, archive, replace the hours of and date an exception on a resource.
 *
 * <p>The application layer sees only its ports and its queries; the jOOQ repositories that implement them, and the
 * {@code DSLContext} they open, are an adapter detail this configuration is the one place to name.
 */
@Configuration(proxyBeanMethods = false)
class CatalogResourceConfiguration {

    @Bean
    ResourceRepository resourceRepository(DSLContext dsl) {
        return new JooqResourceRepository(dsl);
    }

    @Bean
    ResourceReadRepository resourceReadRepository(DSLContext dsl) {
        return new JooqResourceReadRepository(dsl);
    }

    @Bean
    ResourceQueries resourceQueries(UnitOfWork unitOfWork, ResourceReadRepository resources) {
        return new ResourceQueries(unitOfWork, resources);
    }

    @Bean
    CreateResourceUseCase createResourceUseCase(
            UnitOfWork unitOfWork,
            ResourceRepository resources,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new CreateResourceUseCase(unitOfWork, resources, tenantContext, ids, clock);
    }

    @Bean
    PatchResourceUseCase patchResourceUseCase(UnitOfWork unitOfWork, ResourceRepository resources, Clock clock) {
        return new PatchResourceUseCase(unitOfWork, resources, clock);
    }

    @Bean
    ArchiveResourceUseCase archiveResourceUseCase(UnitOfWork unitOfWork, ResourceRepository resources, Clock clock) {
        return new ArchiveResourceUseCase(unitOfWork, resources, clock);
    }

    @Bean
    CalendarRepository calendarRepository(DSLContext dsl, TenantContext tenantContext) {
        return new JooqCalendarRepository(dsl, tenantContext);
    }

    @Bean
    CalendarReadRepository calendarReadRepository(DSLContext dsl) {
        return new JooqCalendarReadRepository(dsl);
    }

    @Bean
    CalendarQueries calendarQueries(
            UnitOfWork unitOfWork, CalendarReadRepository calendar, ResourceReadRepository resources) {
        return new CalendarQueries(unitOfWork, calendar, resources);
    }

    @Bean
    ReplaceWorkingHoursUseCase replaceWorkingHoursUseCase(
            UnitOfWork unitOfWork, ResourceRepository resources, CalendarRepository calendar) {
        return new ReplaceWorkingHoursUseCase(unitOfWork, resources, calendar);
    }

    @Bean
    AddCalendarExceptionUseCase addCalendarExceptionUseCase(
            UnitOfWork unitOfWork,
            ResourceRepository resources,
            CalendarRepository calendar,
            TenantContext tenantContext,
            IdGenerator ids) {
        return new AddCalendarExceptionUseCase(unitOfWork, resources, calendar, tenantContext, ids);
    }

    @Bean
    DeleteCalendarExceptionUseCase deleteCalendarExceptionUseCase(
            UnitOfWork unitOfWork, CalendarRepository calendar) {
        return new DeleteCalendarExceptionUseCase(unitOfWork, calendar);
    }
}
