package dev.ngb.booking.catalog.application.session.service;

import dev.ngb.booking.catalog.api.session.SessionPlaces;
import dev.ngb.booking.catalog.application.offering.query.OfferingReadRepository;
import dev.ngb.booking.catalog.application.offering.query.OfferingView;
import dev.ngb.booking.catalog.application.recurrence.service.RecurrenceValidator;
import dev.ngb.booking.catalog.application.session.port.SessionRepository;
import dev.ngb.booking.catalog.domain.session.Pool;
import dev.ngb.booking.catalog.domain.session.PoolCode;
import dev.ngb.booking.catalog.domain.session.Session;
import dev.ngb.booking.catalog.domain.session.SessionId;
import dev.ngb.booking.catalog.domain.session.SessionSeries;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns a series into the session rows a Tenant can sell places in.
 *
 * <p>One pass expands the series' rule from now to the offering's horizon and inserts every occurrence that is not
 * already there: the pair of the series and the start instant is unique, so a second run of the same horizon inserts
 * nothing and a cancelled occurrence, which the series excludes, is never recreated. Every session of one series
 * carries the same pools, which is what a booking will later claim a place from, and each pool's places are created
 * with the session it belongs to, so a session that exists always has places to sell.
 */
public class SessionMaterializer {

    private final RecurrenceValidator recurrence;
    private final SessionRepository sessions;
    private final SessionPlaces places;
    private final OfferingReadRepository offerings;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final int fallbackHorizonDays;

    public SessionMaterializer(
            RecurrenceValidator recurrence,
            SessionRepository sessions,
            SessionPlaces places,
            OfferingReadRepository offerings,
            TenantContext tenantContext,
            IdGenerator ids,
            int fallbackHorizonDays) {
        this.recurrence = recurrence;
        this.sessions = sessions;
        this.places = places;
        this.offerings = offerings;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.fallbackHorizonDays = fallbackHorizonDays;
    }

    /**
     * @param pools the places each session offers; the series' own pools when it has some, else one default pool
     * @return the sessions this call inserted, which is empty when the horizon was already materialized
     */
    public List<Session> materialize(SessionSeries series, List<Pool> pools, Instant now) {
        Instant horizonEnd = horizonEnd(series.offeringId(), now);
        List<Instant> starts =
                recurrence.expandSessions(series.zone(), series.dtstart(), series.rule(), now, horizonEnd);
        List<Pool> stored = SessionRules.pools(pools, series.capacity());
        long durationSeconds = series.durationMinutes() * 60L;
        List<Session> inserted = new ArrayList<>(starts.size());
        for (Instant start : starts) {
            Session session = Session.materialized(
                    new SessionId(ids.newId()),
                    tenantContext.requireTenant(),
                    series.id(),
                    series.offeringId(),
                    start,
                    start.plus(Duration.ofSeconds(durationSeconds)),
                    series.capacity(),
                    now);
            if (sessions.insertSession(session)) {
                sessions.insertPools(session.id().value(), stored);
                for (Pool pool : stored) {
                    places.createPlaces(session.id().value(), pool.code().value(), pool.capacity());
                }
                inserted.add(session);
            }
        }
        return List.copyOf(inserted);
    }

    /**
     * The pools a series' sessions carry, or one default pool holding the whole capacity.
     *
     * <p>A series that named no pool carries one {@code default} pool, and its capacity is the series' own, so a
     * changed capacity moves that pool with it rather than leaving the two to disagree.
     */
    public List<Pool> poolsOf(UUID seriesId, int capacity) {
        List<Pool> stored = sessions.poolsOfSeries(seriesId);
        if (stored.isEmpty()) {
            return List.of(new Pool(PoolCode.defaultPool(), capacity));
        }
        if (stored.size() == 1 && stored.get(0).code().value().equals(PoolCode.DEFAULT)) {
            return List.of(new Pool(PoolCode.defaultPool(), capacity));
        }
        return stored;
    }

    /** How far ahead a series is materialized: the offering's own horizon, or the configured default without one. */
    private Instant horizonEnd(UUID offeringId, Instant now) {
        int days = offerings.findById(offeringId).map(OfferingView::horizonDays).orElse(fallbackHorizonDays);
        return now.plus(Duration.ofDays(days));
    }
}
