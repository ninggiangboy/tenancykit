package dev.ngb.booking.catalog.adapter.session.web;

import dev.ngb.booking.catalog.application.session.command.CancelSessionUseCase;
import dev.ngb.booking.catalog.application.session.command.CreateSessionSeriesUseCase;
import dev.ngb.booking.catalog.application.session.command.PatchSessionSeriesUseCase;
import dev.ngb.booking.catalog.application.session.command.ResizePoolsUseCase;
import dev.ngb.booking.catalog.application.session.query.PoolView;
import dev.ngb.booking.catalog.application.session.query.SessionListing;
import dev.ngb.booking.catalog.application.session.query.SessionQueries;
import dev.ngb.booking.catalog.application.session.query.SessionSeriesView;
import dev.ngb.booking.catalog.application.session.query.SessionView;
import dev.ngb.booking.catalog.application.session.service.SessionRules;
import dev.ngb.booking.catalog.domain.session.Pool;
import dev.ngb.booking.catalog.domain.session.SeriesStatus;
import dev.ngb.booking.catalog.domain.session.Session;
import dev.ngb.booking.catalog.domain.session.SessionSeries;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.shared.ConditionalRequests;
import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Capacity sessions: the series that repeat, the sessions a series materializes, and the places each session offers
 * per pool.
 *
 * <p>Creating or changing a series materializes it inside the offering's horizon, so the sessions exist as rows before
 * anyone books one. Cancelling marks one occurrence and appends a cancellation to its series, so the next expansion
 * does not recreate it, and resizing replaces a session's pools whole.
 */
@Profile("api")
@RestController
@RequestMapping
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class SessionController {

    /** One pool as the caller sends it. */
    public record PoolRequest(String code, Integer capacity) {}

    /** One pool as the API returns it; {@code remaining} is how many places are still free. */
    public record PoolResponse(String code, int capacity, int remaining) {

        static PoolResponse of(Pool pool) {
            return new PoolResponse(pool.code().value(), pool.capacity(), pool.capacity());
        }

        static PoolResponse ofView(PoolView view) {
            return new PoolResponse(view.code(), view.capacity(), view.remaining());
        }
    }

    public record SessionSeriesCreateRequest(
            UUID offeringId,
            String zone,
            String dtstart,
            List<String> rule,
            Integer durationMinutes,
            Integer capacity,
            List<PoolRequest> pools) {}

    public record SessionSeriesPatchRequest(
            List<String> rule, Integer durationMinutes, Integer capacity, SeriesStatus status) {}

    public record PoolResizeRequest(List<PoolRequest> pools) {}

    public record SessionSeriesResponse(
            String id,
            UUID offeringId,
            String zone,
            String dtstart,
            List<String> rule,
            int durationMinutes,
            int capacity,
            List<PoolResponse> pools,
            long version) {

        static SessionSeriesResponse of(SessionSeries series, List<Pool> pools) {
            return new SessionSeriesResponse(
                    series.id().toString(),
                    series.offeringId(),
                    series.zone(),
                    series.dtstart(),
                    series.rule(),
                    series.durationMinutes(),
                    series.capacity(),
                    pools.stream().map(PoolResponse::of).toList(),
                    series.version());
        }

        static SessionSeriesResponse ofView(SessionSeriesView view) {
            return new SessionSeriesResponse(
                    view.id().toString(),
                    view.offeringId(),
                    view.zone(),
                    view.dtstart(),
                    view.rule(),
                    view.durationMinutes(),
                    view.capacity(),
                    view.pools().stream().map(PoolResponse::ofView).toList(),
                    view.version());
        }
    }

    public record SessionResponse(
            String id,
            UUID offeringId,
            UUID seriesId,
            Instant startsAt,
            Instant endsAt,
            String status,
            int capacity,
            List<PoolResponse> pools) {

        static SessionResponse of(Session session, List<Pool> pools) {
            return new SessionResponse(
                    session.id().toString(),
                    session.offeringId(),
                    session.seriesId() == null ? null : session.seriesId().value(),
                    session.startsAt(),
                    session.endsAt(),
                    session.status().name(),
                    session.capacity(),
                    pools.stream().map(PoolResponse::of).toList());
        }

        static SessionResponse ofView(SessionView view) {
            return new SessionResponse(
                    view.id().toString(),
                    view.offeringId(),
                    view.seriesId(),
                    view.startsAt(),
                    view.endsAt(),
                    view.status(),
                    view.capacity(),
                    view.pools().stream().map(PoolResponse::ofView).toList());
        }
    }

    public record SessionSeriesCreatedResponse(
            SessionSeriesResponse series, List<SessionResponse> sessions, List<UUID> untouchedSessions) {}

    public record SessionSeriesPageResponse(List<SessionSeriesResponse> data) {}

    private final CreateSessionSeriesUseCase creates;
    private final PatchSessionSeriesUseCase patches;
    private final CancelSessionUseCase cancels;
    private final ResizePoolsUseCase resizes;
    private final SessionQueries sessions;

    public SessionController(
            CreateSessionSeriesUseCase creates,
            PatchSessionSeriesUseCase patches,
            CancelSessionUseCase cancels,
            ResizePoolsUseCase resizes,
            SessionQueries sessions) {
        this.creates = creates;
        this.patches = patches;
        this.cancels = cancels;
        this.resizes = resizes;
        this.sessions = sessions;
    }

    @GetMapping("/session-series")
    @RequiresPermission(BookingPermissions.CALENDAR_READ)
    public SessionSeriesPageResponse listSeries() {
        return new SessionSeriesPageResponse(
                sessions.series().stream().map(SessionSeriesResponse::ofView).toList());
    }

    @PostMapping("/session-series")
    @RequiresPermission(BookingPermissions.CALENDAR_MANAGE)
    public ResponseEntity<SessionSeriesCreatedResponse> createSessionSeries(
            @RequestBody SessionSeriesCreateRequest request) {
        CreateSessionSeriesUseCase.Result created = creates.execute(new CreateSessionSeriesUseCase.Command(
                request.offeringId(),
                request.zone(),
                request.dtstart(),
                request.rule(),
                request.durationMinutes(),
                request.capacity(),
                pools(request.pools())));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ExpectedVersion.etag(created.series().version()))
                .body(created(created.series(), created.sessions(), created.untouchedSessions(), created.pools()));
    }

    /** Changes only the properties that are present; the version the caller pinned must still be the current one. */
    @PatchMapping("/session-series/{seriesId}")
    @ConditionalUpdate
    @RequiresPermission(BookingPermissions.CALENDAR_MANAGE)
    public ResponseEntity<SessionSeriesCreatedResponse> patchSessionSeries(
            @PathVariable UUID seriesId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody SessionSeriesPatchRequest request) {
        PatchSessionSeriesUseCase.Result patched = patches.execute(
                seriesId,
                new PatchSessionSeriesUseCase.Change(
                        request.rule(), request.durationMinutes(), request.capacity(), request.status()),
                ConditionalRequests.requireIfMatch(ifMatch));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(patched.series().version()))
                .body(created(patched.series(), patched.sessions(), patched.untouchedSessions(), patched.pools()));
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link SessionListing}. */
    @GetMapping("/sessions")
    @Listing(SessionListing.class)
    @RequiresPermission(BookingPermissions.OFFERING_READ)
    public Page<SessionResponse> listSessions(ListQuery query) {
        return sessions.list(query).map(SessionResponse::ofView);
    }

    @GetMapping("/sessions/{sessionId}")
    @RequiresPermission(BookingPermissions.OFFERING_READ)
    public SessionResponse getSession(@PathVariable UUID sessionId) {
        return SessionResponse.ofView(sessions.get(sessionId));
    }

    /** Marks one occurrence cancelled and excludes it from its series, so it is not materialized again. */
    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(BookingPermissions.CALENDAR_MANAGE)
    public void cancelSession(@PathVariable UUID sessionId) {
        cancels.execute(sessionId);
    }

    @PutMapping("/sessions/{sessionId}/pools")
    @RequiresPermission(BookingPermissions.BOOKING_MANAGE)
    public SessionResponse resizePools(@PathVariable UUID sessionId, @RequestBody PoolResizeRequest request) {
        ResizePoolsUseCase.Result resized =
                resizes.execute(sessionId, new ResizePoolsUseCase.Command(pools(request.pools())));
        return SessionResponse.of(resized.session(), resized.pools());
    }

    private static SessionSeriesCreatedResponse created(
            SessionSeries series, List<Session> materialized, List<UUID> untouched, List<Pool> pools) {
        return new SessionSeriesCreatedResponse(
                SessionSeriesResponse.of(series, pools),
                materialized.stream().map(session -> SessionResponse.of(session, pools)).toList(),
                untouched);
    }

    private static List<Pool> pools(List<PoolRequest> raw) {
        if (raw == null) {
            return List.of();
        }
        List<Pool> pools = new ArrayList<>(raw.size());
        for (PoolRequest request : raw) {
            pools.add(SessionRules.pool(request.code(), request.capacity()));
        }
        return pools;
    }
}
