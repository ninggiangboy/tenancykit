package dev.ngb.booking.catalog.adapter.calendar.persistence;

import static dev.ngb.booking_jooq.Tables.CALENDAR_EXCEPTIONS;
import static dev.ngb.booking_jooq.Tables.WORKING_HOURS;

import dev.ngb.booking.catalog.application.calendar.query.CalendarExceptionView;
import dev.ngb.booking.catalog.application.calendar.query.CalendarReadRepository;
import dev.ngb.booking.catalog.application.calendar.query.WorkingHourView;
import dev.ngb.booking_jooq.tables.records.CalendarExceptionsRecord;
import dev.ngb.booking_jooq.tables.records.WorkingHoursRecord;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/** Selects a resource's calendar straight into the views; row-level security narrows every statement to the tenant. */
public class JooqCalendarReadRepository implements CalendarReadRepository {

    private final DSLContext dsl;

    public JooqCalendarReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<WorkingHourView> hours(UUID resourceId) {
        return dsl.selectFrom(WORKING_HOURS)
                .where(WORKING_HOURS.RESOURCE_ID.eq(resourceId))
                .orderBy(WORKING_HOURS.WEEKDAY, WORKING_HOURS.OPENS_MINUTE)
                .fetch(JooqCalendarReadRepository::toHourView);
    }

    @Override
    public List<CalendarExceptionView> exceptions(UUID resourceId) {
        return dsl.selectFrom(CALENDAR_EXCEPTIONS)
                .where(CALENDAR_EXCEPTIONS.RESOURCE_ID.eq(resourceId))
                .orderBy(CALENDAR_EXCEPTIONS.FROM_DATE, CALENDAR_EXCEPTIONS.ID)
                .fetch(JooqCalendarReadRepository::toExceptionView);
    }

    private static WorkingHourView toHourView(WorkingHoursRecord row) {
        return new WorkingHourView(
                row.getWeekday().intValue(),
                row.getOpensMinute().intValue(),
                row.getClosesMinute().intValue());
    }

    private static CalendarExceptionView toExceptionView(CalendarExceptionsRecord row) {
        List<WorkingHourView> hours =
                CalendarHoursJson.read(row.getHours() == null ? null : row.getHours().data()).stream()
                        .map(hour -> new WorkingHourView(hour.weekday(), hour.opensMinute(), hour.closesMinute()))
                        .toList();
        return new CalendarExceptionView(
                row.getId(), row.getFromDate(), row.getToDate(), row.getKind(), hours);
    }
}
