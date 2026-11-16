package dev.ngb.survey.collection.adapter.visit.persistence;

import static dev.ngb.survey_jooq.Tables.VISITS;
import static dev.ngb.survey_jooq.Tables.VISIT_PAGE_EVENTS;

import dev.ngb.survey.collection.application.visit.port.VisitRepository;
import dev.ngb.survey.collection.domain.visit.Visit;
import dev.ngb.survey.collection.domain.visit.VisitState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The visit store: one row per opening, moved along by a compare-and-swap on its state so two calls cannot both move
 * the same visit.
 *
 * <p>Only the token's hash is written, so a copy of the database cannot be replayed against the public API. The UTM
 * labels are bounded before they reach here and are stored as the JSON they are.
 */
public class JooqVisitRepository implements VisitRepository {

    private static final TypeReference<Map<String, String>> UTM = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqVisitRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public void insert(Visit visit) {
        dsl.insertInto(VISITS)
                .set(VISITS.ID, visit.id())
                .set(VISITS.TENANT_ID, visit.tenantId())
                .set(VISITS.SURVEY_ID, visit.surveyId())
                .set(VISITS.LINK_ID, visit.linkId())
                .set(VISITS.VERSION_ID, visit.versionId())
                .set(VISITS.VERSION_NO, visit.versionNo())
                .set(VISITS.TOKEN_HASH, visit.tokenHash())
                .set(VISITS.SOURCE, visit.source())
                .set(VISITS.UTM, JSONB.valueOf(json.writeValueAsString(visit.utm())))
                .set(VISITS.REFERRER_DOMAIN, visit.referrerDomain())
                .set(VISITS.DEVICE_CLASS, visit.deviceClass())
                .set(VISITS.BROWSER_FAMILY, visit.browserFamily())
                .set(VISITS.COUNTRY, visit.country())
                .set(VISITS.TRAFFIC_CLASS, visit.trafficClass())
                .set(VISITS.STATE, visit.state().name())
                .set(VISITS.RESPONSE_ID, visit.responseId())
                .set(VISITS.OPENED_AT, visit.openedAt())
                .set(VISITS.STARTED_AT, visit.startedAt())
                .set(VISITS.LAST_ACTIVITY_AT, visit.lastActivityAt())
                .set(VISITS.COMPLETED_AT, visit.completedAt())
                .execute();
    }

    @Override
    public Optional<Visit> findById(UUID visitId) {
        return dsl.selectFrom(VISITS).where(VISITS.ID.eq(visitId)).fetchOptional(this::toVisit);
    }

    @Override
    public Optional<Visit> findByTokenHash(byte[] tokenHash) {
        return dsl.selectFrom(VISITS)
                .where(VISITS.TOKEN_HASH.eq(tokenHash))
                .fetchOptional(this::toVisit);
    }

    @Override
    public boolean save(Visit visit, String expectedState) {
        return dsl.update(VISITS)
                .set(VISITS.STATE, visit.state().name())
                .set(VISITS.RESPONSE_ID, visit.responseId())
                .set(VISITS.STARTED_AT, visit.startedAt())
                .set(VISITS.LAST_ACTIVITY_AT, visit.lastActivityAt())
                .set(VISITS.COMPLETED_AT, visit.completedAt())
                .where(VISITS.ID.eq(visit.id()))
                .and(VISITS.STATE.eq(expectedState))
                .execute()
                > 0;
    }

    @Override
    public int eventCount(UUID visitId) {
        return dsl.fetchCount(VISIT_PAGE_EVENTS, VISIT_PAGE_EVENTS.VISIT_ID.eq(visitId));
    }

    @Override
    public void recordPageView(UUID id, UUID tenantId, UUID visitId, UUID pageId, Instant viewedAt) {
        dsl.insertInto(VISIT_PAGE_EVENTS)
                .set(VISIT_PAGE_EVENTS.ID, id)
                .set(VISIT_PAGE_EVENTS.TENANT_ID, tenantId)
                .set(VISIT_PAGE_EVENTS.VISIT_ID, visitId)
                .set(VISIT_PAGE_EVENTS.PAGE_ID, pageId)
                .set(VISIT_PAGE_EVENTS.VIEWED_AT, viewedAt)
                .execute();
    }

    @Override
    public List<Visit> idleSince(Instant before, int limit) {
        return dsl.selectFrom(VISITS)
                .where(VISITS.STATE.in(VisitState.OPENED.name(), VisitState.STARTED.name()))
                .and(VISITS.LAST_ACTIVITY_AT.lt(before))
                .orderBy(VISITS.LAST_ACTIVITY_AT.asc())
                .limit(limit)
                .fetch(this::toVisit);
    }

    @Override
    public Optional<Visit> openVisitOfLink(UUID linkId) {
        return dsl.selectFrom(VISITS)
                .where(VISITS.LINK_ID.eq(linkId))
                .and(VISITS.STATE.in(VisitState.OPENED.name(), VisitState.STARTED.name()))
                .orderBy(VISITS.LAST_ACTIVITY_AT.desc())
                .limit(1)
                .fetchOptional(this::toVisit);
    }

    @Override
    public void detachResponse(UUID visitId) {
        dsl.update(VISITS)
                .setNull(VISITS.RESPONSE_ID)
                .where(VISITS.ID.eq(visitId))
                .execute();
    }

    @Override
    public boolean completedForLink(UUID linkId) {
        return dsl.fetchCount(VISITS, VISITS.LINK_ID.eq(linkId).and(VISITS.STATE.eq(VisitState.COMPLETED.name()))) > 0;
    }

    private Visit toVisit(Record row) {
        return new Visit(
                row.get(VISITS.ID),
                row.get(VISITS.TENANT_ID),
                row.get(VISITS.SURVEY_ID),
                row.get(VISITS.LINK_ID),
                row.get(VISITS.VERSION_ID),
                row.get(VISITS.VERSION_NO),
                row.get(VISITS.TOKEN_HASH),
                row.get(VISITS.SOURCE),
                readUtm(row.get(VISITS.UTM)),
                row.get(VISITS.REFERRER_DOMAIN),
                row.get(VISITS.DEVICE_CLASS),
                row.get(VISITS.BROWSER_FAMILY),
                row.get(VISITS.COUNTRY),
                row.get(VISITS.TRAFFIC_CLASS),
                VisitState.valueOf(row.get(VISITS.STATE)),
                row.get(VISITS.RESPONSE_ID),
                row.get(VISITS.OPENED_AT),
                row.get(VISITS.STARTED_AT),
                row.get(VISITS.LAST_ACTIVITY_AT),
                row.get(VISITS.COMPLETED_AT));
    }

    private Map<String, String> readUtm(JSONB stored) {
        if (stored == null) {
            return Map.of();
        }
        Map<String, String> utm = json.readValue(stored.data(), UTM);
        return utm == null ? Map.of() : utm;
    }
}
