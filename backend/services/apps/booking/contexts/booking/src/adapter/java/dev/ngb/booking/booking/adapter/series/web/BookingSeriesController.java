package dev.ngb.booking.booking.adapter.series.web;

import dev.ngb.booking.booking.adapter.booking.web.BookingController.BookingResponse;
import dev.ngb.booking.booking.application.series.command.CancelSeriesUseCase;
import dev.ngb.booking.booking.application.series.command.ConfirmSeriesUseCase;
import dev.ngb.booking.booking.application.series.command.CreateSeriesUseCase;
import dev.ngb.booking.booking.application.series.exception.SeriesConflictException;
import dev.ngb.booking.booking.application.series.query.SeriesQueries;
import dev.ngb.booking.booking.application.series.query.SeriesView;
import dev.ngb.booking.booking.domain.booking.Booking;
import dev.ngb.booking.booking.domain.series.BookingSeries;
import dev.ngb.booking.booking.domain.series.SeriesMode;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Booking series: one rule, one row, and one hold per occurrence.
 *
 * <p>Creating a series in {@code ALL_OR_NOTHING} is one transaction, so the answer is the whole series or the list of
 * occurrences that stopped it; {@code SKIP_CONFLICTS} books what the calendar allows and names what it did not.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class BookingSeriesController {

    /** A series as the caller sends it; the rule is expanded in the zone of {@code dtstart}. */
    public record SeriesCreateRequest(
            UUID offeringId,
            String customerRef,
            String zone,
            String dtstart,
            List<String> rule,
            Integer durationMinutes,
            String poolCode,
            SeriesMode mode) {}

    /** An occurrence the recurrence named and the series did not book. */
    public record SkippedOccurrenceResponse(Instant startsAt, String reason) {

        static SkippedOccurrenceResponse of(SeriesConflictException.Conflict conflict) {
            return new SkippedOccurrenceResponse(conflict.startsAt(), conflict.reason());
        }

        static SkippedOccurrenceResponse of(SeriesView.SkippedOccurrenceView view) {
            return new SkippedOccurrenceResponse(view.startsAt(), view.reason());
        }
    }

    public record SeriesResponse(
            UUID id,
            UUID offeringId,
            String customerRef,
            String mode,
            String status,
            List<BookingResponse> occurrences,
            List<SkippedOccurrenceResponse> skipped,
            long version) {

        static SeriesResponse of(SeriesView view) {
            return new SeriesResponse(
                    view.id(),
                    view.offeringId(),
                    view.customerRef(),
                    view.mode(),
                    view.status(),
                    view.occurrences().stream().map(BookingResponse::ofView).toList(),
                    view.skipped().stream().map(one -> SkippedOccurrenceResponse.of(one)).toList(),
                    view.version());
        }

        static SeriesResponse of(
                BookingSeries series, List<Booking> bookings, List<SeriesConflictException.Conflict> skipped) {
            return new SeriesResponse(
                    series.id(),
                    series.offeringId(),
                    series.customerRef(),
                    series.mode().name(),
                    series.status().name(),
                    bookings.stream().map(BookingResponse::of).toList(),
                    skipped.stream().map(one -> SkippedOccurrenceResponse.of(one)).toList(),
                    series.version());
        }
    }

    public record SeriesPage(List<SeriesResponse> data) {}

    private final CreateSeriesUseCase creates;
    private final ConfirmSeriesUseCase confirms;
    private final CancelSeriesUseCase cancels;
    private final SeriesQueries series;

    public BookingSeriesController(
            CreateSeriesUseCase creates,
            ConfirmSeriesUseCase confirms,
            CancelSeriesUseCase cancels,
            SeriesQueries series) {
        this.creates = creates;
        this.confirms = confirms;
        this.cancels = cancels;
        this.series = series;
    }

    @GetMapping("/series")
    @RequiresPermission(BookingPermissions.SERIES_READ)
    public SeriesPage listSeries() {
        return new SeriesPage(series.list().stream().map(view -> SeriesResponse.of(view)).toList());
    }

    /** Books every occurrence the rule names, or reports the ones that stopped an all-or-nothing series. */
    @PostMapping("/series")
    @RequiresPermission(BookingPermissions.SERIES_MANAGE)
    public ResponseEntity<SeriesResponse> createSeries(@RequestBody SeriesCreateRequest request) {
        CreateSeriesUseCase.Result result = creates.execute(new CreateSeriesUseCase.Command(
                request.offeringId(),
                request.customerRef(),
                request.zone(),
                request.dtstart(),
                request.rule(),
                request.durationMinutes(),
                request.poolCode(),
                request.mode()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SeriesResponse.of(result.series(), result.bookings(), result.skipped()));
    }

    @GetMapping("/series/{seriesId}")
    @RequiresPermission(BookingPermissions.SERIES_READ)
    public SeriesResponse getSeries(@PathVariable UUID seriesId) {
        return SeriesResponse.of(series.get(seriesId));
    }

    @PostMapping("/series/{seriesId}/confirm")
    @RequiresPermission(BookingPermissions.SERIES_MANAGE)
    public SeriesResponse confirmSeries(@PathVariable UUID seriesId) {
        confirms.execute(seriesId);
        return SeriesResponse.of(series.get(seriesId));
    }

    @PostMapping("/series/{seriesId}/cancel")
    @RequiresPermission(BookingPermissions.SERIES_MANAGE)
    public SeriesResponse cancelSeries(@PathVariable UUID seriesId) {
        cancels.execute(seriesId);
        return SeriesResponse.of(series.get(seriesId));
    }
}
