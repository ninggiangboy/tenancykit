package dev.ngb.booking.catalog.adapter.calendar.persistence;

import static dev.ngb.booking_jooq.Tables.CALENDAR_EXCEPTIONS;
import static dev.ngb.booking_jooq.Tables.WORKING_HOURS;

import dev.ngb.booking.catalog.application.calendar.port.CalendarRepository;
import dev.ngb.booking.catalog.domain.calendar.CalendarException;
import dev.ngb.booking.catalog.domain.calendar.CalendarExceptionId;
import dev.ngb.booking.catalog.domain.calendar.ExceptionKind;
import dev.ngb.booking.catalog.domain.calendar.WorkingHour;
import dev.ngb.booking_jooq.tables.records.CalendarExceptionsRecord;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;

/**
 * Stores a resource's weekly pattern and its dated exceptions. A write replaces the whole weekly pattern, because a
 * caller always states it in full; an exception row points at its resource and cascades with it.
 *
 * <p>Every statement is tenant-scoped by row-level security rather than by a condition here, so a missing tenant scope
 * reads nothing instead of reading every tenant's calendar. The tenant a weekly row is written under comes from the
 * request's scope, because those rows carry no tenant of their own.
 */
public class JooqCalendarRepository implements CalendarRepository {

    private final DSLContext dsl;
    private final TenantContext tenantContext;

    public JooqCalendarRepository(DSLContext dsl, TenantContext tenantContext) {
        this.dsl = dsl;
        this.tenantContext = tenantContext;
    }

    @Override
    public List<WorkingHour> hours(UUID resourceId) {
        return dsl.selectFrom(WORKING_HOURS)
                .where(WORKING_HOURS.RESOURCE_ID.eq(resourceId))
                .orderBy(WORKING_HOURS.WEEKDAY, WORKING_HOURS.OPENS_MINUTE)
                .fetch(row -> new WorkingHour(
                        row.getWeekday().intValue(),
                        row.getOpensMinute().intValue(),
                        row.getClosesMinute().intValue()));
    }

    @Override
    public void replaceHours(UUID resourceId, List<WorkingHour> hours) {
        dsl.deleteFrom(WORKING_HOURS).where(WORKING_HOURS.RESOURCE_ID.eq(resourceId)).execute();
        if (hours.isEmpty()) {
            return;
        }
        UUID tenantId = tenantContext.requireTenant().value();
        var insert = dsl.insertInto(WORKING_HOURS)
                .columns(
                        WORKING_HOURS.TENANT_ID,
                        WORKING_HOURS.ID,
                        WORKING_HOURS.RESOURCE_ID,
                        WORKING_HOURS.WEEKDAY,
                        WORKING_HOURS.OPENS_MINUTE,
                        WORKING_HOURS.CLOSES_MINUTE);
        for (WorkingHour hour : hours) {
            insert = insert.values(
                    tenantId,
                    UUID.randomUUID(),
                    resourceId,
                    (short) hour.weekday(),
                    (short) hour.opensMinute(),
                    (short) hour.closesMinute());
        }
        insert.execute();
    }

    @Override
    public List<CalendarException> exceptions(UUID resourceId) {
        return dsl.selectFrom(CALENDAR_EXCEPTIONS)
                .where(CALENDAR_EXCEPTIONS.RESOURCE_ID.eq(resourceId))
                .orderBy(CALENDAR_EXCEPTIONS.FROM_DATE, CALENDAR_EXCEPTIONS.ID)
                .fetch(JooqCalendarRepository::toDomain);
    }

    @Override
    public void addException(CalendarException exception) {
        dsl.insertInto(CALENDAR_EXCEPTIONS)
                .set(CALENDAR_EXCEPTIONS.TENANT_ID, exception.tenantId().value())
                .set(CALENDAR_EXCEPTIONS.ID, exception.id().value())
                .set(CALENDAR_EXCEPTIONS.RESOURCE_ID, exception.resourceId())
                .set(CALENDAR_EXCEPTIONS.FROM_DATE, exception.fromDate())
                .set(CALENDAR_EXCEPTIONS.TO_DATE, exception.toDate())
                .set(CALENDAR_EXCEPTIONS.KIND, exception.kind().name())
                .set(CALENDAR_EXCEPTIONS.HOURS, JSONB.valueOf(CalendarHoursJson.write(exception.hours())))
                .execute();
    }

    @Override
    public boolean deleteException(UUID resourceId, UUID exceptionId) {
        return dsl.deleteFrom(CALENDAR_EXCEPTIONS)
                        .where(CALENDAR_EXCEPTIONS.ID.eq(exceptionId))
                        .and(CALENDAR_EXCEPTIONS.RESOURCE_ID.eq(resourceId))
                        .execute()
                == 1;
    }

    private static CalendarException toDomain(CalendarExceptionsRecord row) {
        return new CalendarException(
                new CalendarExceptionId(row.getId()),
                new TenantId(row.getTenantId()),
                row.getResourceId(),
                row.getFromDate(),
                row.getToDate(),
                ExceptionKind.valueOf(row.getKind()),
                CalendarHoursJson.read(row.getHours() == null ? null : row.getHours().data()));
    }
}
