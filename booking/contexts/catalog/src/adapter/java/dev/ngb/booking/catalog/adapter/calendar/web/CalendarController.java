package dev.ngb.booking.catalog.adapter.calendar.web;

import dev.ngb.booking.catalog.application.calendar.command.AddCalendarExceptionUseCase;
import dev.ngb.booking.catalog.application.calendar.command.DeleteCalendarExceptionUseCase;
import dev.ngb.booking.catalog.application.calendar.command.ReplaceWorkingHoursUseCase;
import dev.ngb.booking.catalog.application.calendar.query.CalendarExceptionView;
import dev.ngb.booking.catalog.application.calendar.query.CalendarQueries;
import dev.ngb.booking.catalog.application.calendar.query.WorkingHourView;
import dev.ngb.booking.catalog.application.calendar.service.CalendarRules;
import dev.ngb.booking.catalog.application.catalog.exception.CatalogValidationException;
import dev.ngb.booking.catalog.domain.calendar.CalendarException;
import dev.ngb.booking.catalog.domain.calendar.ExceptionKind;
import dev.ngb.booking.catalog.domain.calendar.WorkingHour;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.core.security.RequiresPermission;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * When a resource is open: its weekly wall-clock pattern and the dated exceptions to it.
 *
 * <p>The pattern is stated in full, so a {@code PUT} replaces it; a {@code CLOSED} exception closes a range of dates
 * and an {@code OPEN} one replaces those days' hours. Times cross the wire as {@code HH:mm}, with {@code 24:00} for the
 * end of a day, and are stored as minutes from midnight.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class CalendarController {

    /** One window as the caller sends it: a weekday and two local wall-clock times. */
    public record WorkingHourRequest(int weekday, String opens, String closes) {}

    public record WorkingHoursReplaceRequest(List<WorkingHourRequest> hours) {}

    /** One window as the API returns it; {@code closes} is {@code 24:00} for a close at the end of a day. */
    public record WorkingHourResponse(int weekday, String opens, String closes) {}

    public record WorkingHoursResponse(List<WorkingHourResponse> hours) {}

    /** One exception as the caller sends it; {@code hours} is required when {@code kind} is {@code OPEN}. */
    public record CalendarExceptionCreateRequest(
            LocalDate fromDate, LocalDate toDate, String kind, List<WorkingHourRequest> hours) {}

    public record CalendarExceptionResponse(
            String id,
            LocalDate fromDate,
            LocalDate toDate,
            String kind,
            List<WorkingHourResponse> hours) {

        static CalendarExceptionResponse of(CalendarException exception) {
            return new CalendarExceptionResponse(
                    exception.id().toString(),
                    exception.fromDate(),
                    exception.toDate(),
                    exception.kind().name(),
                    exception.hours().stream().map(CalendarController::of).toList());
        }

        static CalendarExceptionResponse ofView(CalendarExceptionView view) {
            return new CalendarExceptionResponse(
                    view.id().toString(),
                    view.fromDate(),
                    view.toDate(),
                    view.kind(),
                    view.hours().stream().map(CalendarController::ofView).toList());
        }
    }

    public record ExceptionPageResponse(List<CalendarExceptionResponse> data) {}

    private final ReplaceWorkingHoursUseCase replaces;
    private final AddCalendarExceptionUseCase adds;
    private final DeleteCalendarExceptionUseCase deletes;
    private final CalendarQueries calendar;

    public CalendarController(
            ReplaceWorkingHoursUseCase replaces,
            AddCalendarExceptionUseCase adds,
            DeleteCalendarExceptionUseCase deletes,
            CalendarQueries calendar) {
        this.replaces = replaces;
        this.adds = adds;
        this.deletes = deletes;
        this.calendar = calendar;
    }

    /** Replaces the resource's whole weekly pattern with the windows the caller sent. */
    @PutMapping("/resources/{resourceId}/working-hours")
    @RequiresPermission(BookingPermissions.RESOURCE_MANAGE)
    public WorkingHoursResponse replaceWorkingHours(
            @PathVariable UUID resourceId, @RequestBody WorkingHoursReplaceRequest request) {
        List<WorkingHour> hours = parse(request.hours());
        return new WorkingHoursResponse(
                replaces.execute(resourceId, new ReplaceWorkingHoursUseCase.Command(hours)).stream()
                        .map(CalendarController::of)
                        .toList());
    }

    @GetMapping("/resources/{resourceId}/working-hours")
    @RequiresPermission(BookingPermissions.RESOURCE_READ)
    public WorkingHoursResponse getWorkingHours(@PathVariable UUID resourceId) {
        return new WorkingHoursResponse(
                calendar.hours(resourceId).stream().map(CalendarController::ofView).toList());
    }

    @GetMapping("/resources/{resourceId}/exceptions")
    @RequiresPermission(BookingPermissions.RESOURCE_READ)
    public ExceptionPageResponse listExceptions(@PathVariable UUID resourceId) {
        return new ExceptionPageResponse(
                calendar.exceptions(resourceId).stream().map(CalendarExceptionResponse::ofView).toList());
    }

    /** Adds a dated exception, which closes a range of dates or replaces their hours. */
    @PostMapping("/resources/{resourceId}/exceptions")
    @RequiresPermission(BookingPermissions.RESOURCE_MANAGE)
    public ResponseEntity<CalendarExceptionResponse> addException(
            @PathVariable UUID resourceId, @RequestBody CalendarExceptionCreateRequest request) {
        CalendarException exception = adds.execute(
                resourceId,
                new AddCalendarExceptionUseCase.Command(
                        request.fromDate(), request.toDate(), kind(request.kind()), parse(request.hours())));
        return ResponseEntity.status(HttpStatus.CREATED).body(CalendarExceptionResponse.of(exception));
    }

    @DeleteMapping("/resources/{resourceId}/exceptions/{exceptionId}")
    @RequiresPermission(BookingPermissions.RESOURCE_MANAGE)
    public ResponseEntity<Void> deleteException(
            @PathVariable UUID resourceId, @PathVariable UUID exceptionId) {
        deletes.execute(resourceId, exceptionId);
        return ResponseEntity.noContent().build();
    }

    private static List<WorkingHour> parse(List<WorkingHourRequest> sent) {
        if (sent == null) {
            return List.of();
        }
        return sent.stream()
                .map(hour -> CalendarRules.workingHour(hour.weekday(), hour.opens(), hour.closes()))
                .toList();
    }

    private static ExceptionKind kind(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return ExceptionKind.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new CatalogValidationException("An exception kind is CLOSED or OPEN.");
        }
    }

    private static WorkingHourResponse of(WorkingHour hour) {
        return new WorkingHourResponse(
                hour.weekday(), CalendarRules.text(hour.opensMinute()), CalendarRules.text(hour.closesMinute()));
    }

    private static WorkingHourResponse ofView(WorkingHourView view) {
        return new WorkingHourResponse(
                view.weekday(), CalendarRules.text(view.opensMinute()), CalendarRules.text(view.closesMinute()));
    }
}
