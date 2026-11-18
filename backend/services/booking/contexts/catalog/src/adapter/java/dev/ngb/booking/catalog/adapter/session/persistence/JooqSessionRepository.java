package dev.ngb.booking.catalog.adapter.session.persistence;

import static dev.ngb.booking_jooq.Tables.SESSIONS;
import static dev.ngb.booking_jooq.Tables.SESSION_POOLS;
import static dev.ngb.booking_jooq.Tables.SESSION_SERIES;

import dev.ngb.booking.catalog.application.session.port.SessionRepository;
import dev.ngb.booking.catalog.domain.session.Pool;
import dev.ngb.booking.catalog.domain.session.PoolCode;
import dev.ngb.booking.catalog.domain.session.SeriesStatus;
import dev.ngb.booking.catalog.domain.session.Session;
import dev.ngb.booking.catalog.domain.session.SessionId;
import dev.ngb.booking.catalog.domain.session.SessionSeries;
import dev.ngb.booking.catalog.domain.session.SessionSeriesId;
import dev.ngb.booking.catalog.domain.session.SessionStatus;
import dev.ngb.booking_jooq.tables.records.SessionSeriesRecord;
import dev.ngb.booking_jooq.tables.records.SessionsRecord;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Stores session series, the sessions they materialize and the pools of each session.
 *
 * <p>Inserting a session does nothing when the series already has one at that start, which is what makes a horizon
 * expanded twice insert nothing the second time; the same pair is a unique key, so the database decides rather than a
 * read that could race. Every statement is tenant-scoped by row-level security, and a child row carries the tenant of
 * the request scope, because it has none of its own.
 */
public class JooqSessionRepository implements SessionRepository {

    private static final String ACTIVE = "ACTIVE";
    private static final String SCHEDULED = "SCHEDULED";

    private final DSLContext dsl;
    private final TenantContext tenantContext;

    public JooqSessionRepository(DSLContext dsl, TenantContext tenantContext) {
        this.dsl = dsl;
        this.tenantContext = tenantContext;
    }

    @Override
    public void insertSeries(SessionSeries series) {
        dsl.insertInto(SESSION_SERIES)
                .set(SESSION_SERIES.TENANT_ID, series.tenantId().value())
                .set(SESSION_SERIES.ID, series.id().value())
                .set(SESSION_SERIES.OFFERING_ID, series.offeringId())
                .set(SESSION_SERIES.ZONE, series.zone())
                .set(SESSION_SERIES.DTSTART, series.dtstart())
                .set(SESSION_SERIES.RULE, series.rule().toArray(String[]::new))
                .set(SESSION_SERIES.DURATION_MINUTES, series.durationMinutes())
                .set(SESSION_SERIES.CAPACITY, series.capacity())
                .set(SESSION_SERIES.STATUS, series.status().name())
                .set(SESSION_SERIES.VERSION, series.version())
                .set(SESSION_SERIES.CREATED_AT, series.createdAt())
                .set(SESSION_SERIES.UPDATED_AT, series.updatedAt())
                .execute();
    }

    @Override
    public Optional<SessionSeries> lockSeriesById(UUID seriesId) {
        return dsl.selectFrom(SESSION_SERIES)
                .where(SESSION_SERIES.ID.eq(seriesId))
                .forUpdate()
                .fetchOptional(JooqSessionRepository::toSeries);
    }

    @Override
    public void updateSeries(SessionSeries series) {
        int updated = dsl.update(SESSION_SERIES)
                .set(SESSION_SERIES.RULE, series.rule().toArray(String[]::new))
                .set(SESSION_SERIES.DURATION_MINUTES, series.durationMinutes())
                .set(SESSION_SERIES.CAPACITY, series.capacity())
                .set(SESSION_SERIES.STATUS, series.status().name())
                .set(SESSION_SERIES.UPDATED_AT, series.updatedAt())
                .set(SESSION_SERIES.VERSION, series.version())
                .where(SESSION_SERIES.ID.eq(series.id().value()))
                .and(SESSION_SERIES.VERSION.lt(series.version()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("session series " + series.id() + " was not updated: version "
                    + series.version() + " is not newer");
        }
    }

    @Override
    public List<SessionSeries> activeSeries() {
        return dsl.selectFrom(SESSION_SERIES)
                .where(SESSION_SERIES.STATUS.eq(ACTIVE))
                .orderBy(SESSION_SERIES.CREATED_AT, SESSION_SERIES.ID)
                .fetch(JooqSessionRepository::toSeries);
    }

    @Override
    public boolean insertSession(Session session) {
        return dsl.insertInto(
                                SESSIONS,
                                SESSIONS.TENANT_ID,
                                SESSIONS.ID,
                                SESSIONS.SERIES_ID,
                                SESSIONS.OFFERING_ID,
                                SESSIONS.STARTS_AT,
                                SESSIONS.ENDS_AT,
                                SESSIONS.STATUS,
                                SESSIONS.CAPACITY,
                                SESSIONS.CREATED_AT,
                                SESSIONS.UPDATED_AT)
                        .values(
                                session.tenantId().value(),
                                session.id().value(),
                                session.seriesId() == null ? null : session.seriesId().value(),
                                session.offeringId(),
                                session.startsAt(),
                                session.endsAt(),
                                session.status().name(),
                                session.capacity(),
                                session.createdAt(),
                                session.updatedAt())
                        .onConflict(SESSIONS.SERIES_ID, SESSIONS.STARTS_AT)
                        .doNothing()
                        .execute()
                == 1;
    }

    @Override
    public void insertPools(UUID sessionId, List<Pool> pools) {
        UUID tenantId = tenantContext.requireTenant().value();
        for (Pool pool : pools) {
            dsl.insertInto(
                            SESSION_POOLS,
                            SESSION_POOLS.TENANT_ID,
                            SESSION_POOLS.SESSION_ID,
                            SESSION_POOLS.CODE,
                            SESSION_POOLS.CAPACITY)
                    .values(tenantId, sessionId, pool.code().value(), pool.capacity())
                    .onConflict(SESSION_POOLS.TENANT_ID, SESSION_POOLS.SESSION_ID, SESSION_POOLS.CODE)
                    .doNothing()
                    .execute();
        }
    }

    @Override
    public void deleteFutureScheduledSessions(UUID seriesId, Instant from) {
        dsl.deleteFrom(SESSIONS)
                .where(SESSIONS.SERIES_ID.eq(seriesId))
                .and(SESSIONS.STARTS_AT.ge(from))
                .and(SESSIONS.STATUS.eq(SCHEDULED))
                .execute();
    }

    @Override
    public Optional<Session> findSessionById(UUID sessionId) {
        return dsl.selectFrom(SESSIONS).where(SESSIONS.ID.eq(sessionId)).fetchOptional(JooqSessionRepository::toSession);
    }

    @Override
    public Optional<Session> lockSessionById(UUID sessionId) {
        return dsl.selectFrom(SESSIONS)
                .where(SESSIONS.ID.eq(sessionId))
                .forUpdate()
                .fetchOptional(JooqSessionRepository::toSession);
    }

    @Override
    public void markCancelled(Session session) {
        dsl.update(SESSIONS)
                .set(SESSIONS.STATUS, session.status().name())
                .set(SESSIONS.UPDATED_AT, session.updatedAt())
                .where(SESSIONS.ID.eq(session.id().value()))
                .execute();
    }

    @Override
    public void replacePools(UUID sessionId, List<Pool> pools) {
        dsl.deleteFrom(SESSION_POOLS).where(SESSION_POOLS.SESSION_ID.eq(sessionId)).execute();
        insertPools(sessionId, pools);
    }

    @Override
    public List<Pool> poolsOfSeries(UUID seriesId) {
        Map<String, Pool> byCode = new LinkedHashMap<>();
        dsl.select(SESSION_POOLS.CODE, SESSION_POOLS.CAPACITY)
                .from(SESSION_POOLS)
                .join(SESSIONS)
                .on(SESSIONS.ID.eq(SESSION_POOLS.SESSION_ID))
                .where(SESSIONS.SERIES_ID.eq(seriesId))
                .orderBy(SESSION_POOLS.CODE)
                .fetch()
                .forEach(row -> byCode.putIfAbsent(
                        row.get(SESSION_POOLS.CODE),
                        new Pool(new PoolCode(row.get(SESSION_POOLS.CODE)), row.get(SESSION_POOLS.CAPACITY))));
        return List.copyOf(byCode.values());
    }

    @Override
    public boolean hasHeldOrBookedPlaces(UUID sessionId) {
        return false;
    }

    private static SessionSeries toSeries(SessionSeriesRecord row) {
        String[] rule = row.getRule();
        return SessionSeries.restore(
                new SessionSeriesId(row.getId()),
                new TenantId(row.getTenantId()),
                row.getOfferingId(),
                row.getZone(),
                row.getDtstart(),
                rule == null ? List.of() : new ArrayList<>(Arrays.asList(rule)),
                row.getDurationMinutes(),
                row.getCapacity(),
                SeriesStatus.valueOf(row.getStatus()),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }

    private static Session toSession(SessionsRecord row) {
        return new Session(
                new SessionId(row.getId()),
                new TenantId(row.getTenantId()),
                row.getSeriesId() == null ? null : new SessionSeriesId(row.getSeriesId()),
                row.getOfferingId(),
                row.getStartsAt(),
                row.getEndsAt(),
                SessionStatus.valueOf(row.getStatus()),
                row.getCapacity(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
