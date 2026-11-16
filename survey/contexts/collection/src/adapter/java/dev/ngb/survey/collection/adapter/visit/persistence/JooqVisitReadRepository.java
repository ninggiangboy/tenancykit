package dev.ngb.survey.collection.adapter.visit.persistence;

import static dev.ngb.survey_jooq.Tables.VISITS;
import static dev.ngb.survey_jooq.Tables.VISIT_PAGE_EVENTS;

import dev.ngb.survey.collection.application.visit.query.VisitReadRepository;
import dev.ngb.survey.collection.application.visit.query.VisitView;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * The read side of visits, next to its write repository.
 *
 * <p>It selects into a view rather than into the aggregate, because a read never drives a write: the public endpoints
 * need the pinned version and nothing else.
 */
public class JooqVisitReadRepository implements VisitReadRepository {

    private final DSLContext dsl;

    public JooqVisitReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<VisitView> findByTokenHash(byte[] tokenHash) {
        return dsl.selectFrom(VISITS)
                .where(VISITS.TOKEN_HASH.eq(tokenHash))
                .fetchOptional(this::toView);
    }

    @Override
    public int eventCount(UUID visitId) {
        return dsl.fetchCount(VISIT_PAGE_EVENTS, VISIT_PAGE_EVENTS.VISIT_ID.eq(visitId));
    }

    private VisitView toView(Record row) {
        return new VisitView(
                row.get(VISITS.ID),
                row.get(VISITS.TENANT_ID),
                row.get(VISITS.SURVEY_ID),
                row.get(VISITS.LINK_ID),
                row.get(VISITS.VERSION_NO),
                row.get(VISITS.SOURCE),
                row.get(VISITS.STATE),
                row.get(VISITS.RESPONSE_ID),
                row.get(VISITS.OPENED_AT),
                row.get(VISITS.LAST_ACTIVITY_AT));
    }
}
