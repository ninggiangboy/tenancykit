package dev.ngb.survey.collection.adapter.link.persistence;

import static dev.ngb.survey_jooq.Tables.SURVEY_LINKS;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.survey.collection.application.link.query.LinkListing;
import dev.ngb.survey.collection.application.link.query.LinkReadRepository;
import dev.ngb.survey.collection.application.link.query.LinkView;
import dev.ngb.survey_jooq.tables.records.SurveyLinksRecord;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The read side of a tenant's links, next to its write repository.
 *
 * <p>It selects the stored columns only; the URL is not among them, because it is derived from the id and the app's
 * keys. A caller that needs it adds it, which is why this repository returns the row and a query class builds the view.
 */
public class JooqLinkReadRepository implements LinkReadRepository {

    /** The link fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(LinkListing.SCHEMA)
            .column("id", SURVEY_LINKS.ID)
            .column("survey_id", SURVEY_LINKS.SURVEY_ID)
            .column("kind", SURVEY_LINKS.KIND)
            .column("source", SURVEY_LINKS.SOURCE)
            .column("external_ref", SURVEY_LINKS.EXTERNAL_REF)
            .column("expires_at", SURVEY_LINKS.EXPIRES_AT)
            .column("status", SURVEY_LINKS.STATUS)
            .column("created_at", SURVEY_LINKS.CREATED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the link listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqLinkReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<LinkView> list(ListQuery query, UUID surveyId) {
        return JooqLists.fetch(dsl, SURVEY_LINKS, SURVEY_LINKS.SURVEY_ID.eq(surveyId), BINDING, query, this::toView);
    }

    @Override
    public Optional<LinkView> find(UUID linkId) {
        return dsl.selectFrom(SURVEY_LINKS)
                .where(SURVEY_LINKS.ID.eq(linkId))
                .fetchOptional(this::toView);
    }

    private LinkView toView(SurveyLinksRecord row) {
        return new LinkView(
                row.getId(),
                row.getSurveyId(),
                row.getKind(),
                row.getSource(),
                row.getExternalRef(),
                row.getExpiresAt(),
                row.getStatus(),
                row.getCreatedAt(),
                null);
    }
}
