package dev.ngb.booking.catalog.adapter.session.persistence;

import static dev.ngb.booking_jooq.Tables.SESSIONS;
import static dev.ngb.booking_jooq.Tables.SESSION_POOLS;
import static dev.ngb.booking_jooq.Tables.SESSION_SERIES;

import dev.ngb.booking.catalog.application.session.query.PoolView;
import dev.ngb.booking.catalog.application.session.query.SessionListing;
import dev.ngb.booking.catalog.application.session.query.SessionReadRepository;
import dev.ngb.booking.catalog.application.session.query.SessionSeriesView;
import dev.ngb.booking.catalog.application.session.query.SessionView;
import dev.ngb.booking_jooq.tables.records.SessionSeriesRecord;
import dev.ngb.booking_jooq.tables.records.SessionsRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * Selects sessions and session series straight into their views; row-level security narrows every statement to the
 * current tenant.
 *
 * <p>A page's pools are read in one statement for the whole page rather than one per session, so the list stays one
 * query plus one lookup whatever the page size. A series' pools are the ones its sessions carry, which is where they
 * are stored.
 */
public class JooqSessionReadRepository implements SessionReadRepository {

    /** The session fields a caller may narrow and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(SessionListing.SCHEMA)
            .column("id", SESSIONS.ID)
            .column("offering_id", SESSIONS.OFFERING_ID)
            .column("status", SESSIONS.STATUS)
            .column("starts_at", SESSIONS.STARTS_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the session listing, for a test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqSessionReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<SessionView> list(ListQuery query) {
        return JooqLists.fetchPage(dsl, SESSIONS, DSL.noCondition(), BINDING, query, this::toViews);
    }

    @Override
    public Optional<SessionView> findById(UUID sessionId) {
        return dsl.selectFrom(SESSIONS)
                .where(SESSIONS.ID.eq(sessionId))
                .fetchOptional(row -> toView(
                        row, poolsOf(List.of(sessionId)).getOrDefault(sessionId, List.of())));
    }

    @Override
    public List<SessionSeriesView> series() {
        List<SessionSeriesRecord> rows = dsl.selectFrom(SESSION_SERIES)
                .orderBy(SESSION_SERIES.CREATED_AT.desc(), SESSION_SERIES.ID.desc())
                .fetch();
        List<UUID> ids = rows.stream().map(SessionSeriesRecord::getId).toList();
        Map<UUID, List<PoolView>> pools = poolsBySeries(ids);
        List<SessionSeriesView> views = new ArrayList<>(rows.size());
        for (SessionSeriesRecord row : rows) {
            String[] rule = row.getRule();
            views.add(new SessionSeriesView(
                    row.getId(),
                    row.getOfferingId(),
                    row.getZone(),
                    row.getDtstart(),
                    rule == null ? List.of() : Arrays.asList(rule),
                    row.getDurationMinutes(),
                    row.getCapacity(),
                    pools.getOrDefault(row.getId(), List.of()),
                    row.getVersion()));
        }
        return views;
    }

    private List<SessionView> toViews(List<SessionsRecord> rows) {
        List<UUID> ids = rows.stream().map(SessionsRecord::getId).toList();
        Map<UUID, List<PoolView>> pools = poolsOf(ids);
        List<SessionView> views = new ArrayList<>(rows.size());
        for (SessionsRecord row : rows) {
            views.add(toView(row, pools.getOrDefault(row.getId(), List.of())));
        }
        return views;
    }

    private static SessionView toView(SessionsRecord row, List<PoolView> pools) {
        return new SessionView(
                row.getId(),
                row.getOfferingId(),
                row.getSeriesId(),
                row.getStartsAt(),
                row.getEndsAt(),
                row.getStatus(),
                row.getCapacity(),
                pools);
    }

    private Map<UUID, List<PoolView>> poolsOf(Collection<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<PoolView>> bySession = new HashMap<>();
        dsl.select(SESSION_POOLS.SESSION_ID, SESSION_POOLS.CODE, SESSION_POOLS.CAPACITY)
                .from(SESSION_POOLS)
                .where(SESSION_POOLS.SESSION_ID.in(sessionIds))
                .orderBy(SESSION_POOLS.SESSION_ID, SESSION_POOLS.CODE)
                .fetch()
                .forEach(row -> bySession
                        .computeIfAbsent(row.get(SESSION_POOLS.SESSION_ID), id -> new ArrayList<>())
                        .add(new PoolView(
                                row.get(SESSION_POOLS.CODE),
                                row.get(SESSION_POOLS.CAPACITY),
                                row.get(SESSION_POOLS.CAPACITY))));
        return bySession;
    }

    private Map<UUID, List<PoolView>> poolsBySeries(Collection<UUID> seriesIds) {
        if (seriesIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<String, PoolView>> bySeries = new HashMap<>();
        dsl.select(SESSIONS.SERIES_ID, SESSION_POOLS.CODE, SESSION_POOLS.CAPACITY)
                .from(SESSION_POOLS)
                .join(SESSIONS)
                .on(SESSIONS.ID.eq(SESSION_POOLS.SESSION_ID))
                .where(SESSIONS.SERIES_ID.in(seriesIds))
                .orderBy(SESSIONS.SERIES_ID, SESSION_POOLS.CODE)
                .fetch()
                .forEach(row -> bySeries
                        .computeIfAbsent(row.get(SESSIONS.SERIES_ID), id -> new LinkedHashMap<>())
                        .putIfAbsent(
                                row.get(SESSION_POOLS.CODE),
                                new PoolView(
                                        row.get(SESSION_POOLS.CODE),
                                        row.get(SESSION_POOLS.CAPACITY),
                                        row.get(SESSION_POOLS.CAPACITY))));
        Map<UUID, List<PoolView>> pools = new HashMap<>();
        bySeries.forEach((seriesId, byCode) -> pools.put(seriesId, List.copyOf(byCode.values())));
        return pools;
    }
}
