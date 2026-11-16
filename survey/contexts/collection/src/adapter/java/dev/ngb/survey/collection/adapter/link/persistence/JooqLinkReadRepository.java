package dev.ngb.survey.collection.adapter.link.persistence;

import static dev.ngb.survey_jooq.Tables.SURVEY_LINKS;

import dev.ngb.survey.collection.application.link.query.LinkReadRepository;
import dev.ngb.survey.collection.application.link.query.LinkView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * The read side of a tenant's links, next to its write repository.
 *
 * <p>It selects the stored columns only; the URL is not among them, because it is derived from the id and the app's
 * keys. A caller that needs it adds it, which is why this repository returns the row and a query class builds the view.
 */
public class JooqLinkReadRepository implements LinkReadRepository {

    private final DSLContext dsl;

    public JooqLinkReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<LinkView> list(UUID surveyId) {
        return dsl.selectFrom(SURVEY_LINKS)
                .where(SURVEY_LINKS.SURVEY_ID.eq(surveyId))
                .orderBy(SURVEY_LINKS.CREATED_AT.desc())
                .fetch(this::toView);
    }

    @Override
    public Optional<LinkView> find(UUID linkId) {
        return dsl.selectFrom(SURVEY_LINKS)
                .where(SURVEY_LINKS.ID.eq(linkId))
                .fetchOptional(this::toView);
    }

    private LinkView toView(Record row) {
        return new LinkView(
                row.get(SURVEY_LINKS.ID),
                row.get(SURVEY_LINKS.SURVEY_ID),
                row.get(SURVEY_LINKS.KIND),
                row.get(SURVEY_LINKS.SOURCE),
                row.get(SURVEY_LINKS.EXTERNAL_REF),
                row.get(SURVEY_LINKS.EXPIRES_AT),
                row.get(SURVEY_LINKS.STATUS),
                row.get(SURVEY_LINKS.CREATED_AT),
                null);
    }
}
